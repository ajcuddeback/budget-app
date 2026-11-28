#!/usr/bin/env bash
# First-boot set-up INSIDE the Budget Owl VM. cloud-init runs it once, as root; you can run it
# again by hand and it picks up where it left off (every step checks before it acts).
#
#   /usr/local/sbin/budgetowl-bootstrap              # what cloud-init runs
#   /usr/local/sbin/budgetowl-bootstrap --no-start   # everything except building and starting
#
# Reads /etc/budgetowl/bootstrap.conf (written by deploy/proxmox/create-vm.sh; no secrets in it):
#   BUDGETOWL_USER     the operator's login, who owns the checkout and runs the backups
#   BUDGETOWL_REPO     git URL to clone
#   BUDGETOWL_REF      branch or tag to check out
#   BUDGETOWL_COMMIT   the commit that ref must be at: the one the operator reviewed on the
#                      Proxmox host. Empty means "not checked" (create-vm.sh --no-commit-check)
#   BUDGETOWL_DIR      where the checkout goes (default /opt/budget-owl)
#   BACKUP_DIR         where the nightly backup writes (default /srv/budgetowl-backups)
#
# What it does:
#   1. Docker Engine and the Compose plugin, from Debian's own signed archive (Debian 13 ships
#      Compose v2 as a CLI plugin), plus the tools the deploy scripts use;
#   2. a firewall that lets in SSH and nothing else, and drops packets from the network that
#      claim to be for 127.0.0.1 (which Docker before 28 would otherwise forward to the app);
#   3. automatic security updates;
#   4. clones Budget Owl and generates its .env HERE, with passwords from openssl that never leave
#      this machine (mode 0600);
#   5. builds and starts the stack;
#   6. makes this VM's backup-signing key and installs a nightly backup timer writing to
#      BACKUP_DIR; prints the key's public half for the operator's password manager.

set -euo pipefail

start=1
case "${1:-}" in
  --no-start) start=0 ;;
  -h|--help) sed -n '2,/^[^#]/{/^#/!q;s/^# \{0,1\}//;p;}' "$0"; exit 0 ;;
  "") ;;
  *) echo "unknown option: $1" >&2; exit 2 ;;
esac

[ "$(id -u)" -eq 0 ] || { echo "run as root" >&2; exit 1; }
conf=/etc/budgetowl/bootstrap.conf
# shellcheck source=/dev/null
[ -f "$conf" ] && . "$conf"
: "${BUDGETOWL_USER:?set BUDGETOWL_USER in $conf}"
: "${BUDGETOWL_REPO:?set BUDGETOWL_REPO in $conf}"
: "${BUDGETOWL_REF:?set BUDGETOWL_REF in $conf}"
BUDGETOWL_DIR="${BUDGETOWL_DIR:-/opt/budget-owl}"
BACKUP_DIR="${BACKUP_DIR:-/srv/budgetowl-backups}"
id "$BUDGETOWL_USER" >/dev/null 2>&1 || { echo "no such user: $BUDGETOWL_USER" >&2; exit 1; }

say() { printf '\n== %s\n' "$*"; }
export DEBIAN_FRONTEND=noninteractive

say "1. Docker Engine, Compose, and the tools the deploy scripts use"
# Debian's own packages: signed by the Debian archive key the image already trusts, and kept
# patched by the security updates below. No third-party apt repository, no key to pin.
# docker-cli is its own package in Debian 13 and only a Recommends of docker.io, so it is named.
apt-get update -q
apt-get install -y -q --no-install-recommends \
  docker.io docker-cli docker-compose docker-buildx \
  git openssl age openssh-client ca-certificates curl nftables qemu-guest-agent \
  unattended-upgrades apt-listchanges
docker compose version >/dev/null || { echo "docker compose is not available" >&2; exit 1; }
# The docker group is root on this machine in all but name. Only the operator is in it.
usermod -aG docker "$BUDGETOWL_USER"
systemctl enable --now qemu-guest-agent >/dev/null 2>&1 || true

say "2. Firewall: SSH in, nothing else"
# Our own table, never `flush ruleset`: Docker keeps its NAT and forwarding rules in nftables
# too (through iptables-nft), and flushing the whole ruleset would wipe them.
#
# Two chains:
# - input: what reaches this VM's own sockets. SSH only.
# - prerouting, at raw priority, before Docker's DNAT. A published port is NOT protected by the
#   input chain: Docker rewrites the destination in prerouting and the packet is then FORWARDED
#   to the container, never "input". Docker before 28 (Debian 13 ships 26.1) DNATs
#   127.0.0.1:<port> whatever interface the packet came in on, so a machine on the same network
#   segment that sends a packet addressed to 127.0.0.1 to this VM's MAC address reaches the app.
#   Nothing legitimate arrives from the network addressed to loopback, so drop it all.
cat > /etc/nftables.conf <<'NFT'
#!/usr/sbin/nft -f
# Budget Owl VM firewall (deploy/proxmox/bootstrap-vm.sh). Inbound SSH only.
# Budget Owl itself listens on 127.0.0.1 and is reached through the HTTPS route you set up
# (userguide/give-your-server-an-https-address.md). Open 443 here only if that route is a
# reverse proxy on this VM: add   tcp dport 443 accept   below the ssh line, then
#   sudo systemctl reload nftables
# NEVER `systemctl restart` or `stop` nftables: Debian's unit flushes the WHOLE ruleset on stop,
# Docker's rules included. If that ever happens: sudo systemctl restart docker
table inet budgetowl
delete table inet budgetowl
table inet budgetowl {
  chain prerouting {
    type filter hook prerouting priority raw; policy accept;
    iifname != "lo" ip daddr 127.0.0.0/8 drop
    iifname != "lo" ip6 daddr ::1 drop
  }
  chain input {
    type filter hook input priority filter; policy drop;
    iif lo accept
    ct state established,related accept
    ct state invalid drop
    meta l4proto { icmp, ipv6-icmp } accept
    udp sport 67 udp dport 68 accept
    udp sport 547 udp dport 546 accept
    tcp dport 22 accept
  }
}
NFT
chmod 644 /etc/nftables.conf
nft -c -f /etc/nftables.conf
systemctl enable nftables >/dev/null 2>&1 || true
# reload or start, never restart: stopping Debian's nftables.service runs `nft flush ruleset`.
if systemctl is-active --quiet nftables 2>/dev/null; then
  systemctl reload nftables
elif ! systemctl start nftables 2>/dev/null; then
  nft -f /etc/nftables.conf 2>/dev/null \
    || echo "warning: could not load the firewall now; it loads at the next boot"
fi

say "3. Automatic security updates"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'APT'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT
# SSH: keys only, no root. cloud-init already sets this; stating it here means it survives
# someone editing cloud-init's file.
mkdir -p /etc/ssh/sshd_config.d
cat > /etc/ssh/sshd_config.d/10-budgetowl.conf <<'SSH'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
SSH
systemctl reload ssh 2>/dev/null || true

say "4. Budget Owl, at $BUDGETOWL_REF"
if [ ! -d "$BUDGETOWL_DIR/.git" ]; then
  install -d -o "$BUDGETOWL_USER" -g "$BUDGETOWL_USER" -m 755 "$BUDGETOWL_DIR"
  runuser -u "$BUDGETOWL_USER" -- git clone --branch "$BUDGETOWL_REF" -- "$BUDGETOWL_REPO" "$BUDGETOWL_DIR"
else
  echo "already cloned: $BUDGETOWL_DIR ($(git -C "$BUDGETOWL_DIR" rev-parse --short HEAD))"
fi
cd "$BUDGETOWL_DIR"
# The code the VM runs must be the code the operator looked at on the Proxmox host. A branch can
# move between the two (a push, or someone else's), so the commit is checked, not the name.
head=$(runuser -u "$BUDGETOWL_USER" -- git -C "$BUDGETOWL_DIR" rev-parse HEAD)
if [ -n "${BUDGETOWL_COMMIT:-}" ] && [ "$head" != "$BUDGETOWL_COMMIT" ]; then
  echo "error: $BUDGETOWL_REF is at $head here, but the Proxmox host's checkout was at" >&2
  echo "       $BUDGETOWL_COMMIT. Not building code nobody reviewed. Update the host's checkout and" >&2
  echo "       make the VM again, or edit BUDGETOWL_COMMIT in $conf and run budgetowl-bootstrap." >&2
  exit 1
fi
matched=""
[ -z "${BUDGETOWL_COMMIT:-}" ] || matched=" (the same commit as the checkout on the Proxmox host)"
echo "at commit $head$matched"

# The passwords are made here, on this VM, and written only to .env. They are not in the
# cloud-init snippet, not on the Proxmox host, and not in any log. An existing .env is never
# replaced: a new DB_PASSWORD would not match the database that is already there.
if [ ! -f .env ]; then
  ( umask 077
    {
      echo "# Generated on this VM by budgetowl-bootstrap on $(date -u +%F). Keep a copy somewhere safe."
      echo "DB_PASSWORD=$(openssl rand -base64 32)"
      echo "DB_APP_USER=budgetowl_app"
      echo "DB_APP_PASSWORD=$(openssl rand -base64 32)"
    } > .env.partial
    mv .env.partial .env )
  chown "$BUDGETOWL_USER:$BUDGETOWL_USER" .env
  echo "generated .env (mode $(stat -c %a .env))"
else
  echo "keeping the existing .env"
fi

if [ "$start" -eq 1 ]; then
  say "5. Build and start (several minutes on a small VM)"
  docker compose build
  docker compose up -d
  for _ in $(seq 1 100); do
    status=$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q api)" 2>/dev/null || true)
    [ "$status" = healthy ] && break
    sleep 3
  done
  docker compose ps
  [ "${status:-}" = healthy ] || echo "warning: the api is not healthy yet: docker compose logs api"
else
  say "5. Skipped (--no-start): docker compose build && docker compose up -d"
fi

say "6. Backup signing key, and the nightly backup to $BACKUP_DIR"
install -d -o "$BUDGETOWL_USER" -g "$BUDGETOWL_USER" -m 700 "$BACKUP_DIR"
install -d -m 755 /etc/budgetowl
# Every backup is signed with this key, and a restore refuses anything it did not sign: a restore
# runs the backup's contents as the database superuser. Made here, readable by the operator
# account only, and never written into the backup folder. Its public half goes in the operator's
# password manager, because a NEW server after a disaster has a new key and needs the old one's
# line to trust the old backups (restore.sh --signers).
key=/etc/budgetowl/backup-signing-key
if [ ! -f "$key" ]; then
  ssh-keygen -q -t ed25519 -N '' -C "budgetowl-backup@$(hostname)" -f "$key" </dev/null >/dev/null
  printf 'budgetowl-backup namespaces="budgetowl-backup" %s\n' "$(cut -d' ' -f1,2 "$key.pub")" \
    > "$key.allowed_signers"
fi
chown "$BUDGETOWL_USER:$BUDGETOWL_USER" "$key"
chmod 600 "$key"
chmod 644 "$key.pub" "$key.allowed_signers"
if [ ! -f /etc/budgetowl/backup.env ]; then
  cat > /etc/budgetowl/backup.env <<ENV
# Read by budgetowl-backup.service, and by deploy/backup.sh, restore.sh and upgrade.sh.
# Public keys and paths only: never a private key or password.
BACKUP_DIR=$BACKUP_DIR
BACKUP_SIGNING_KEY=$key
BACKUP_KEEP=14
BACKUP_KEEP_WEEKLY=8
BACKUP_KEEP_MONTHLY=12
# Encrypt every backup to your age PUBLIC key (the private half stays off this machine):
#BACKUP_AGE_RECIPIENT=age1...
ENV
  chmod 644 /etc/budgetowl/backup.env
fi
if [ -x "$BUDGETOWL_DIR/deploy/backup.sh" ]; then
  cat > /etc/systemd/system/budgetowl-backup.service <<UNIT
[Unit]
Description=Budget Owl database backup
Wants=docker.service
After=docker.service

[Service]
Type=oneshot
User=$BUDGETOWL_USER
WorkingDirectory=$BUDGETOWL_DIR
EnvironmentFile=/etc/budgetowl/backup.env
ExecStart=$BUDGETOWL_DIR/deploy/backup.sh
UMask=0077
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=full
UNIT
  cat > /etc/systemd/system/budgetowl-backup.timer <<'UNIT'
[Unit]
Description=Nightly Budget Owl database backup

[Timer]
OnCalendar=*-*-* 03:17:00
RandomizedDelaySec=20m
Persistent=true

[Install]
WantedBy=timers.target
UNIT
  systemctl daemon-reload 2>/dev/null || true
  systemctl enable --now budgetowl-backup.timer 2>/dev/null \
    || echo "warning: could not enable the timer now (no systemd?): systemctl enable --now budgetowl-backup.timer"
else
  echo "warning: $BUDGETOWL_REF has no deploy/backup.sh, so no backup timer was installed."
  echo "         Check out a version that has it and run this again."
fi

say "Done"
echo "$BACKUP_DIR is on this VM's own disk until you mount storage there that lives somewhere else."
echo "A backup on the machine it protects does not survive losing that machine."
echo "If this VM replaces one that died, restore BEFORE you mount the old backups at that path,"
echo "or stop the timer until then (sudo systemctl disable --now budgetowl-backup.timer)."
echo
echo "This VM signs its backups. Keep this line in your password manager, next to your age key;"
echo "restoring onto a new server needs it (restore.sh --signers):"
echo "  $(cat "$key.allowed_signers")"
