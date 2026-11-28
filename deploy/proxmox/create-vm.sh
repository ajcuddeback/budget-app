#!/usr/bin/env bash
# Create a Debian 13 VM on a Proxmox VE host that installs and starts Budget Owl on first boot.
# Run as root ON THE PROXMOX HOST, from a checkout of Budget Owl (it reads bootstrap-vm.sh beside it).
#
#   deploy/proxmox/create-vm.sh --ssh-key ~/.ssh/id_ed25519.pub --dry-run    # look first
#   deploy/proxmox/create-vm.sh --ssh-key ~/.ssh/id_ed25519.pub              # then do it
#   deploy/proxmox/create-vm.sh --ssh-key KEY.pub --ip 192.168.1.50/24 --gw 192.168.1.1
#
# Options (defaults in brackets):
#   --ssh-key FILE         REQUIRED. Your SSH PUBLIC key. The VM has no password login at all.
#   --vmid N               [9310] refused if a VM or container with this id exists.
#   --name NAME            [budgetowl] VM name and hostname.
#   --storage ID           [local-lvm] where the VM's disk goes.
#   --bridge NAME          [vmbr0] network bridge.
#   --cores N              [2]
#   --memory MiB           [4096] the app needs ~600 MiB running; building it needs more.
#   --disk GiB             [32]
#   --ip CIDR --gw ADDR    a static address. DHCP if you give neither.
#   --user NAME            [owl] the login created on the VM.
#   --repo URL             [https://github.com/ajcuddeback/budget-app.git]
#   --ref BRANCH|TAG       [the branch this checkout is on] what to check out on the VM.
#   --commit SHA           [this checkout's HEAD] the commit --ref must be at on the VM. The VM
#                          refuses to build anything else: it runs the code you looked at here.
#   --no-commit-check      skip that check (a ref you chose deliberately, e.g. a moving branch).
#   --snippets-storage ID  [local] a storage with the "snippets" content type enabled.
#   --image-dir DIR        [/var/lib/vz/template/iso] where the Debian image is kept.
#   --image-base URL       [https://cloud.debian.org/images/cloud/trixie/latest] the Debian folder
#                          to download from. "latest" moves with every Debian build; to pin a
#                          hash, use a dated build folder, e.g.
#                          https://cloud.debian.org/images/cloud/trixie/20261123-2671
#   --image-sha512 HEX     also require the image to have exactly this SHA-512 (one you checked
#                          yourself), on top of Debian's SHA512SUMS. Pair it with a dated
#                          --image-base, or it breaks at Debian's next build.
#   --no-start             create the VM but do not start it.
#   --print-user-data      print the cloud-init snippet and exit (nothing is created).
#   --dry-run              print every qm/pvesm/download command instead of running it.
#   -h, --help             this text.
#
# The cloud-init snippet it writes holds your SSH PUBLIC key, a user name, a git URL, a ref and
# a commit id.
# It holds no password and no private key: the database passwords are generated inside the VM.

set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

vmid=9310
name=budgetowl
storage=local-lvm
bridge=vmbr0
cores=2
memory=4096
disk=32
ip=""
gw=""
user=owl
repo="https://github.com/ajcuddeback/budget-app.git"
ref=""
commit=""
commit_check=1
snippets_storage=local
image_dir=/var/lib/vz/template/iso
image_sha512=""
ssh_key=""
image_base=https://cloud.debian.org/images/cloud/trixie/latest
start=1
dry_run=0
print_only=0

image_name=debian-13-genericcloud-amd64.qcow2

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
need() { [ -n "${2:-}" ] || die "$1 needs a value"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --ssh-key) need "$1" "${2:-}"; ssh_key="$2"; shift 2 ;;
    --vmid) need "$1" "${2:-}"; vmid="$2"; shift 2 ;;
    --name) need "$1" "${2:-}"; name="$2"; shift 2 ;;
    --storage) need "$1" "${2:-}"; storage="$2"; shift 2 ;;
    --bridge) need "$1" "${2:-}"; bridge="$2"; shift 2 ;;
    --cores) need "$1" "${2:-}"; cores="$2"; shift 2 ;;
    --memory) need "$1" "${2:-}"; memory="$2"; shift 2 ;;
    --disk) need "$1" "${2:-}"; disk="$2"; shift 2 ;;
    --ip) need "$1" "${2:-}"; ip="$2"; shift 2 ;;
    --gw) need "$1" "${2:-}"; gw="$2"; shift 2 ;;
    --user) need "$1" "${2:-}"; user="$2"; shift 2 ;;
    --repo) need "$1" "${2:-}"; repo="$2"; shift 2 ;;
    --ref) need "$1" "${2:-}"; ref="$2"; shift 2 ;;
    --snippets-storage) need "$1" "${2:-}"; snippets_storage="$2"; shift 2 ;;
    --image-dir) need "$1" "${2:-}"; image_dir="$2"; shift 2 ;;
    --image-base) need "$1" "${2:-}"; image_base="$2"; shift 2 ;;
    --commit) need "$1" "${2:-}"; commit="$2"; shift 2 ;;
    --no-commit-check) commit_check=0; shift ;;
    --image-sha512) need "$1" "${2:-}"; image_sha512="$2"; shift 2 ;;
    --no-start) start=0; shift ;;
    --print-user-data) print_only=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) sed -n '2,/^[^#]/{/^#/!q;s/^# \{0,1\}//;p;}' "$0"; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# Everything below ends up in a qm argument or a YAML file, so it is held to a strict shape
# rather than quoted and hoped for.
[[ "$vmid" =~ ^[1-9][0-9]{2,8}$ ]] || die "--vmid must be a number of at least 100"
[[ "$name" =~ ^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$ ]] || die "--name must be a hostname (a-z, 0-9, -)"
[[ "$storage" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "--storage is not a storage id"
[[ "$snippets_storage" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || die "--snippets-storage is not a storage id"
[[ "$bridge" =~ ^[A-Za-z0-9._-]+$ ]] || die "--bridge is not a bridge name"
[[ "$cores" =~ ^[1-9][0-9]?$ ]] || die "--cores must be 1-99"
[[ "$memory" =~ ^[1-9][0-9]{2,6}$ ]] || die "--memory is in MiB, e.g. 4096"
[ "$memory" -ge 2048 ] || die "--memory below 2048 MiB will not build the app"
[[ "$disk" =~ ^[1-9][0-9]{0,4}$ ]] || die "--disk is in GiB, e.g. 32"
[ "$disk" -ge 16 ] || die "--disk below 16 GiB will not hold the images and their build"
[[ "$user" =~ ^[a-z_][a-z0-9_-]{0,31}$ ]] || die "--user must be a Linux user name"
[ "$user" != root ] || die "--user cannot be root"
[[ "$repo" =~ ^https://[A-Za-z0-9./_~-]+$ ]] || die "--repo must be an https:// git URL"
# What the VM checks out, and the commit it must be at: by default, exactly this checkout.
checkout="$(cd "$HERE/../.." && pwd)"
if [ -z "$ref" ]; then
  ref=$(git -C "$checkout" symbolic-ref --quiet --short HEAD 2>/dev/null || true)
  [ -n "$ref" ] || die "this checkout is not on a branch: give --ref"
fi
[[ "$ref" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] && [[ "$ref" != *..* ]] || die "--ref is not a branch or tag name"
if [ "$commit_check" -eq 1 ]; then
  [ -n "$commit" ] || commit=$(git -C "$checkout" rev-parse HEAD 2>/dev/null || true)
  [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "--commit must be a full 40-character commit id (or use --no-commit-check)"
else
  commit=""
fi
[[ "$image_dir" =~ ^/[A-Za-z0-9._/-]+$ ]] && [[ "$image_dir" != *..* ]] \
  || die "--image-dir must be an absolute path of letters, digits, . _ - and /"
[[ "$image_base" =~ ^https://(cloud|cdimage)\.debian\.org/[A-Za-z0-9._/-]+$ ]] && [[ "$image_base" != *..* ]] \
  || die "--image-base must be an https:// folder on cloud.debian.org or cdimage.debian.org"
image_base="${image_base%/}"
# A dated build folder names its files after the build: debian-13-genericcloud-amd64-<build>.qcow2
build_id=$(basename "$image_base")
if [[ "$build_id" =~ ^[0-9]{8}-[0-9]+$ ]]; then
  image_name="debian-13-genericcloud-amd64-$build_id.qcow2"
fi
[ -z "$image_sha512" ] || [[ "$image_sha512" =~ ^[0-9a-f]{128}$ ]] || die "--image-sha512 is 128 hex digits"
if [ -n "$ip$gw" ]; then
  [[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$ ]] || die "--ip must look like 192.168.1.50/24"
  [[ "$gw" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || die "--gw must look like 192.168.1.1 (and --ip is needed with it)"
  ipconfig="ip=$ip,gw=$gw"
else
  ipconfig="ip=dhcp"
fi

[ -n "$ssh_key" ] || die "--ssh-key FILE is required: the VM has no password login"
[ -r "$ssh_key" ] || die "cannot read $ssh_key"
grep -q 'PRIVATE KEY' "$ssh_key" && die "$ssh_key is a PRIVATE key. Give the .pub file."
key_line=$(grep -m1 -E '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh.com|sk-ecdsa-sha2-nistp256@openssh.com) [A-Za-z0-9+/=]+( [^"\\]*)?$' "$ssh_key" || true)
[ -n "$key_line" ] || die "$ssh_key does not look like an SSH public key"

bootstrap="$HERE/bootstrap-vm.sh"
[ -r "$bootstrap" ] || die "cannot find $bootstrap (run this from a Budget Owl checkout)"

user_data() {
  cat <<YAML
#cloud-config
# Budget Owl VM ($name), written by deploy/proxmox/create-vm.sh.
# NO SECRETS: an SSH public key, a user name, a git URL and a ref. The database passwords are
# generated inside the VM by budgetowl-bootstrap and never pass through this file or the host.
hostname: $name
manage_etc_hosts: true
users:
  - name: $user
    gecos: Budget Owl operator
    shell: /bin/bash
    groups: [sudo]
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    lock_passwd: true
    ssh_authorized_keys:
      - "$key_line"
disable_root: true
ssh_pwauth: false
package_update: true
package_upgrade: true
packages: [qemu-guest-agent]
write_files:
  - path: /etc/budgetowl/bootstrap.conf
    permissions: "0644"
    content: |
      BUDGETOWL_USER=$user
      BUDGETOWL_REPO=$repo
      BUDGETOWL_REF=$ref
      BUDGETOWL_COMMIT=$commit
      BUDGETOWL_DIR=/opt/budget-owl
      BACKUP_DIR=/srv/budgetowl-backups
  - path: /usr/local/sbin/budgetowl-bootstrap
    permissions: "0755"
    content: |
YAML
  sed 's/^/      /; s/^ *$//' "$bootstrap"
  cat <<'YAML'
runcmd:
  - [systemctl, enable, --now, qemu-guest-agent]
  - [sh, -c, "/usr/local/sbin/budgetowl-bootstrap > /var/log/budgetowl-bootstrap.log 2>&1"]
YAML
}

if [ "$print_only" -eq 1 ]; then user_data; exit 0; fi

run() { # run <command...> — or, with --dry-run, print it
  if [ "$dry_run" -eq 1 ]; then
    printf '  '; printf '%q ' "$@"; printf '\n'
  else
    "$@"
  fi
}

if [ "$dry_run" -eq 0 ]; then
  [ "$(id -u)" -eq 0 ] || die "run as root on the Proxmox host (or add --dry-run to look first)"
  if ! command -v qm >/dev/null 2>&1 || ! command -v pvesm >/dev/null 2>&1; then
    die "qm and pvesm are not here: this runs on a Proxmox VE host"
  fi
  # Any guest id: qm for VMs, pct for containers. The cluster-wide list covers both.
  if qm status "$vmid" >/dev/null 2>&1 || pvesh get /cluster/resources --type vm --output-format json \
       2>/dev/null | grep -q "\"vmid\":${vmid}[,}]"; then
    die "VM/CT $vmid already exists. Nothing was changed. Pick another --vmid."
  fi
  pvesm status --content snippets 2>/dev/null | awk 'NR > 1 {print $1}' | grep -qx "$snippets_storage" \
    || die "storage '$snippets_storage' does not accept snippets. Enable it in the web UI
       (Datacenter > Storage > $snippets_storage > Content: Snippets), or: pvesm set $snippets_storage --content <its current list>,snippets"
  pvesm status 2>/dev/null | awk 'NR > 1 {print $1}' | grep -qx "$storage" || die "no storage called '$storage'"
  ip link show "$bridge" >/dev/null 2>&1 || die "no bridge called '$bridge'"
else
  echo "Dry run: nothing is downloaded, created or changed. These are the commands."
fi

echo
echo "1. The Debian 13 cloud image, verified"
image="$image_dir/$image_name"
sums="$image_dir/$image_name.SHA512SUMS"
run mkdir -p "$image_dir"
run curl --proto '=https' --tlsv1.2 -fsSL -o "$sums.partial" "$image_base/SHA512SUMS"
if [ "$dry_run" -eq 0 ]; then
  mv "$sums.partial" "$sums"
  expected=$(awk -v f="$image_name" '$2 == f {print $1}' "$sums")
  [[ "$expected" =~ ^[0-9a-f]{128}$ ]] || die "SHA512SUMS has no entry for $image_name"
  if [ -n "$image_sha512" ] && [ "$image_sha512" != "$expected" ]; then
    die "Debian's SHA512SUMS lists a different image than the --image-sha512 you gave.
       Debian may have published a new build; check, and run again with the new value."
  fi
  # Debian publishes no signature for the cloud images' checksums (SHA512SUMS.sign is absent for
  # trixie; Debian's own guidance is to fetch the image and checksums over TLS from
  # cloud.debian.org). If one appears, it is checked against Debian's keyring; if it does not
  # verify, nothing goes further.
  if curl --proto '=https' --tlsv1.2 -fsSL -o "$sums.sign" "$image_base/SHA512SUMS.sign" 2>/dev/null; then
    keyring=/usr/share/keyrings/debian-role-keys.gpg
    [ -r "$keyring" ] || die "SHA512SUMS is signed, but $keyring is missing: apt install debian-keyring"
    gpgv --keyring "$keyring" "$sums.sign" "$sums" || die "SHA512SUMS's signature does NOT verify. Stopping."
    echo "   SHA512SUMS signature verified against $keyring"
  else
    rm -f "$sums.sign"
    echo "   Debian publishes no signature for these checksums. Trust rests on TLS to"
    echo "   cloud.debian.org${image_sha512:+ and the --image-sha512 you checked yourself}."
  fi
  if [ -f "$image" ] && [ "$(sha512sum < "$image" | awk '{print $1}')" = "$expected" ]; then
    echo "   already downloaded and matching: $image"
  else
    curl --proto '=https' --tlsv1.2 -fL --progress-bar -o "$image.partial" "$image_base/$image_name"
    actual=$(sha512sum < "$image.partial" | awk '{print $1}')
    [ "$actual" = "$expected" ] || { rm -f "$image.partial"; die "the download does not match SHA512SUMS. Deleted it."; }
    mv "$image.partial" "$image"
  fi
  echo "   SHA-512 matches: $expected"
else
  echo "  # compare the SHA-512 of $image with its line in SHA512SUMS; download it only if it differs"
  run curl --proto '=https' --tlsv1.2 -fL -o "$image.partial" "$image_base/$image_name"
fi

echo
echo "2. The cloud-init snippet (no secrets)"
if [ "$dry_run" -eq 0 ]; then
  snippet_path=$(pvesm path "$snippets_storage:snippets/budgetowl-$vmid-user.yaml")
  mkdir -p "$(dirname "$snippet_path")"
  ( umask 022; user_data > "$snippet_path.partial" ) && mv "$snippet_path.partial" "$snippet_path"
  echo "   $snippet_path"
else
  echo "  # user_data > \$(pvesm path $snippets_storage:snippets/budgetowl-$vmid-user.yaml)"
  echo "  # (see it with --print-user-data)"
fi

echo
echo "3. The VM"
run qm create "$vmid" --name "$name" --ostype l26 --machine q35 \
  --cores "$cores" --cpu host --memory "$memory" \
  --net0 "virtio,bridge=$bridge,firewall=1" \
  --scsihw virtio-scsi-single \
  --agent enabled=1 --onboot 1 \
  --serial0 socket --vga serial0 \
  --description "Budget Owl. Created by deploy/proxmox/create-vm.sh."
run qm importdisk "$vmid" "$image" "$storage" --format raw
# importdisk attaches the new disk as unused0; take its volume id from the config.
if [ "$dry_run" -eq 0 ]; then
  volume=$(qm config "$vmid" | awk -F': ' '$1 == "unused0" {print $2}')
  [ -n "$volume" ] || die "could not find the imported disk in qm config $vmid"
else
  volume="$storage:vm-$vmid-disk-0"
  echo "  # volume=\$(qm config $vmid | awk -F': ' '\$1 == \"unused0\" {print \$2}')"
fi
run qm set "$vmid" --scsi0 "$volume,discard=on,iothread=1,ssd=1"
run qm resize "$vmid" scsi0 "${disk}G"
run qm set "$vmid" --boot order=scsi0
run qm set "$vmid" --ide2 "$storage:cloudinit"
run qm set "$vmid" --ipconfig0 "$ipconfig"
run qm set "$vmid" --cicustom "user=$snippets_storage:snippets/budgetowl-$vmid-user.yaml"
if [ "$start" -eq 1 ]; then run qm start "$vmid"; fi

host="$name"
[ -z "$ip" ] || host="${ip%/*}"
cat <<NEXT

Next:
  1. Wait for first boot to finish installing (5-15 minutes; it builds the app). From here:
       qm agent $vmid network-get-interfaces     # the VM's address, once the agent is up
  2. SSH in and check it:
       ssh $user@$host
       sudo tail -f /var/log/budgetowl-bootstrap.log    # until it says "Done"
       cd /opt/budget-owl && docker compose ps           # three services, api "healthy"
  3. Keep a copy of /opt/budget-owl/.env somewhere safe (a password manager), and mount storage
     that lives OFF this VM at /srv/budgetowl-backups for the nightly backup.
  4. Give it an HTTPS address: userguide/give-your-server-an-https-address.md
  5. Open that address and create the first account (userguide/installing.md, section 3).
NEXT
