# Run it on Proxmox

**For whoever runs the server.** This guide puts Budget Owl in its own virtual machine on a
Proxmox VE host. One script makes the VM, and the VM installs and starts Budget Owl the first
time it boots.

## Why a virtual machine

A VM has its own kernel. If anything in Budget Owl or Docker is ever broken into, the VM's
boundary is between it and your Proxmox host. A VM is also the setup Docker expects, so nothing
needs special settings.

**Why not an LXC container?** Docker can run in an unprivileged LXC with `nesting=1` (and often
`keyctl=1`). But the container shares the Proxmox host's kernel, and Docker inside LXC is a
setup Proxmox itself does not recommend. Container updates on the host can also break it. For
software holding your household's finances, the few hundred MB a VM costs is worth it.

## Before you start

- A Proxmox VE host with internet access, and a shell on it as `root`.
- About **2 cores, 4 GB of memory and 32 GB of disk** free. Budget Owl uses about 600 MB of
  memory once running. The rest is room for building it on the VM, for Debian and for updates.
- A storage that accepts **snippets**, which hold the VM's first-boot settings. `local` usually
  can, once it is turned on: **Datacenter → Storage → local → Edit → Content**, and add
  **Snippets**.
- An **SSH key** on the computer you will connect from. The VM has no password login at all, so
  this is the only way in. If you do not have one, `ssh-keygen -t ed25519` makes one; the file
  you give the script is the one ending `.pub`.

## Make the VM

1. From your own computer, copy your SSH **public** key to the Proxmox host. Use your host's
   address:

   ```bash
   scp ~/.ssh/id_ed25519.pub root@proxmox.home:/root/id_ed25519.pub
   ```

2. On the Proxmox host, get Budget Owl (for its scripts):

   ```bash
   git clone --branch claude/budget-app-ai-harness-32d4cj https://github.com/ajcuddeback/budget-app.git /root/budget-owl
   cd /root/budget-owl
   ```

   There is no release yet, and these scripts are not on `main` until they are merged there,
   so clone that branch. The VM checks out **the same branch and the same commit** as this
   folder: it refuses to build anything else, so what runs is what you have here to read.
   Look through `deploy/proxmox/` before you go on; it runs as root on your host.

3. See what it would do. Nothing is downloaded or created:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub --dry-run
   ```

   It lists every command, starting with the Debian download and ending with `qm start 9310`.

4. Change anything that does not suit your host. The options you are most likely to need:

   | Option                           | Default               | Change it when                                                                                                                                              |
   | -------------------------------- | --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
   | `--vmid`                         | `9310`                | that number is taken (the script refuses it if so)                                                                                                          |
   | `--storage`                      | `local-lvm`           | your VM disks live elsewhere, e.g. a ZFS pool                                                                                                               |
   | `--bridge`                       | `vmbr0`               | your network bridge has another name                                                                                                                        |
   | `--ip` and `--gw`                | DHCP                  | you want a fixed address: `--ip 192.168.1.50/24 --gw 192.168.1.1`                                                                                           |
   | `--memory`, `--cores`, `--disk`  | `4096`, `2`, `32`     | you have more to spare                                                                                                                                      |
   | `--snippets-storage`             | `local`               | another storage holds your snippets                                                                                                                         |
   | `--ref`                          | this folder's branch  | you want a particular version of Budget Owl (then also `--commit`, or `--no-commit-check`)                                                                  |
   | `--image-base`, `--image-sha512` | Debian's latest build | you want to pin one Debian build you have checked: use a dated folder such as `https://cloud.debian.org/images/cloud/trixie/20261123-2671` with its SHA-512 |

   `--help` lists the rest.

5. Make it:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub
   ```

   It downloads Debian 13 and checks the download against Debian's published SHA-512 checksums.
   It stops if they differ. Then it creates and starts the VM, and prints what to do next.

6. Wait for the first boot to finish. It installs Docker and builds Budget Owl, which takes **5 to
   15 minutes**. Find the VM's address in the Proxmox web UI (the VM's **Summary** shows it once
   the guest agent is running), or with:

   ```bash
   qm agent 9310 network-get-interfaces
   ```

7. From your own computer, connect and watch it finish:

   ```bash
   ssh owl@<the VM's address>
   sudo tail -f /var/log/budgetowl-bootstrap.log
   ```

   It is done when the last lines say:

   ```
   This VM signs its backups. Keep this line in your password manager, next to your age key;
   restoring onto a new server needs it (restore.sh --signers):
     budgetowl-backup namespaces="budgetowl-backup" ssh-ed25519 AAAAC3Nz…
   ```

   **Copy that `budgetowl-backup namespaces=…` line into your password manager.** If this VM is
   ever lost, the new one needs it to trust these backups
   ([why backups are signed](installing.md#why-backups-are-signed)).

   If it stops at `error: … is at …, but the Proxmox host's checkout was at …`, the branch moved
   after you cloned it on the host. Update the host's folder (`git pull`), read what changed, and
   make the VM again.

8. Check Budget Owl is running:

   ```bash
   cd /opt/budget-owl
   docker compose ps --format '{{.Service}}   {{.Status}}'
   ```

   All three say `Up` and `(healthy)`.

## What the VM is set up with

- **Debian 13**, with Docker and Docker Compose from Debian's own packages and security
  updates installed automatically.
- **A firewall that lets in SSH and nothing else.** Budget Owl itself answers only inside the VM,
  on `127.0.0.1:8080`. The firewall also drops anything from the network addressed to
  `127.0.0.1`. Without that, the version of Docker that Debian 13 ships would pass such a
  packet, sent by another machine on your network, straight to Budget Owl.
- **No password login.** You sign in with your SSH key as `owl`, who can use `sudo`.
- **Budget Owl in `/opt/budget-owl`**, owned by `owl`, at the commit you cloned on the host. Its
  `.env` was made **on the VM**, with passwords that never passed through your Proxmox host. Copy
  it into your password manager:

  ```bash
  cat /opt/budget-owl/.env
  ```

- **A backup-signing key** in `/etc/budgetowl/backup-signing-key`, readable by `owl` only.
- **A nightly backup at about 03:17**, into `/srv/budgetowl-backups`. It keeps the newest 14,
  one a week for 8 weeks and one a month for 12 months. The settings are in
  `/etc/budgetowl/backup.env`.

`owl` is in the `docker` group, which means it can do anything on the VM. Do not add anyone else.

## Then

1. **Give it an HTTPS address**: [Give your server an HTTPS address](give-your-server-an-https-address.md).
   Run those steps inside the VM. The Tailscale option needs no firewall change. If you choose
   the reverse proxy on the VM itself, open its port:
   1. In `/etc/nftables.conf`, add `tcp dport 443 accept` under the `tcp dport 22 accept` line.
   2. Load it:

      ```bash
      sudo systemctl reload nftables
      ```

   Use `reload`, **never `restart`**: stopping the firewall wipes Docker's own rules as well, and
   Budget Owl stops being reachable. If that has happened, `sudo systemctl restart docker` puts
   them back.

2. **Create the first account**: open the HTTPS address, and continue with section 3 of
   [Installing Budget Owl](installing.md#3-create-the-first-account).

## Backups on Proxmox

**Snapshots and Proxmox backups (vzdump) are worth having, but not on their own.** They copy the
VM's disk while the database is running. Most of the time that restores fine, because PostgreSQL
recovers from a crash. But "most of the time" is not what you want for the only copy of your
finances, and you can only restore the whole VM, not your Budget Owl data into a new install.
Keep using them, and keep Budget Owl's own backups as well.

**A vzdump of this VM is worth more to a thief than any one backup.** It holds three things
together:

- the database;
- the `.env` with its passwords;
- the **signing key**, with which anyone could make a backup your restore would trust.

Keep vzdump archives somewhere other than the Budget Owl backup share. Encrypt them: Proxmox
Backup Server can encrypt on the Proxmox host before anything is sent, with a key you keep
offline.

**The nightly backup is on the VM's own disk to begin with.** Lose the VM and you lose those
too. Put real storage that lives elsewhere at `/srv/budgetowl-backups`, for example an NFS share
from your NAS. On the NAS:

- **export the share to this VM's address only**, not to your whole network. Anyone who can write
  to it can delete your backups (restore refuses ones they forge, because they are signed);
- **encrypt the backups** (below), because anyone who can read the share can read them.

Then, on the VM:

```bash
sudo apt install nfs-common
echo 'nas.home:/export/budgetowl  /srv/budgetowl-backups  nfs  defaults,_netdev  0 0' | sudo tee -a /etc/fstab
sudo mount /srv/budgetowl-backups
sudo chown owl:owl /srv/budgetowl-backups && sudo chmod 700 /srv/budgetowl-backups
```

The backup warns you that NFS permissions are the NAS's to enforce; that is expected. Check that
`ls -ld /srv/budgetowl-backups` shows `owl` as the owner. If the NAS squashes everyone to one user,
set the export's `anonuid` and `anongid` to `owl`'s numbers (`id owl`). On Synology, the NFS
permission rule's **Squash** setting decides which NAS user owns the files; map them to a user with
`owl`'s numbers (menu names differ between versions; see your NAS's help). The backup refuses a folder
that belongs to anybody else.

**If this VM replaces one that died, restore first** (below). Only then mount the old backups at
`/srv/budgetowl-backups`, or turn the nightly backup off until you have:

```bash
sudo systemctl disable --now budgetowl-backup.timer
```

To **encrypt** the nightly backups, make an age key on your own computer (see
[Encrypt your backups](installing.md#encrypt-your-backups)), then on the VM put the **public** key
in `/etc/budgetowl/backup.env`:

```bash
BACKUP_AGE_RECIPIENT=age1…
```

Every backup on the VM then uses it: nightly, before an upgrade, and before a restore.

Check the timer is running, run one now, and see how it went (`journalctl` is where a failed
night says why):

```bash
systemctl list-timers budgetowl-backup.timer
sudo systemctl start budgetowl-backup.service
systemctl status budgetowl-backup          # status=0/SUCCESS
journalctl -u budgetowl-backup -n 50
ls -l /srv/budgetowl-backups
```

Restoring and testing a restore work as in
[Installing Budget Owl, section 5](installing.md#restore-from-a-backup), from `/opt/budget-owl`.
On a **new** VM, give the old VM's line with `--signers`, as that section shows.

## Updates

- **Budget Owl**: inside the VM, `cd /opt/budget-owl && deploy/upgrade.sh`. It takes the backup
  folder, encryption and signing key from `/etc/budgetowl/backup.env`. See
  [Keep it up to date](installing.md#6-keep-it-up-to-date).
- **Debian and Docker** update themselves with security fixes. To take everything else:
  `sudo apt update && sudo apt full-upgrade`.
- **Proxmox** is updated on the host, as usual. Take a snapshot of the VM before a major
  Proxmox upgrade, and a Budget Owl backup too.

## If you would rather do it by hand

1. Make a VM in the Proxmox web UI: Debian 13, 2 cores, 4 GB of memory, a 32 GB disk on
   **VirtIO SCSI single**, **QEMU Guest Agent** ticked, and **Start at boot** ticked.
2. Install Debian with SSH and no desktop. Then, as root:

   ```bash
   apt install docker.io docker-cli docker-compose docker-buildx git openssl age openssh-client nftables qemu-guest-agent unattended-upgrades
   ```

3. Set up the firewall: copy the `/etc/nftables.conf` that `deploy/proxmox/bootstrap-vm.sh`
   writes (between `cat > /etc/nftables.conf <<'NFT'` and `NFT`), then
   `systemctl enable --now nftables`. Do not leave out its `prerouting` part: it is what stops
   other machines on your network reaching Budget Owl through Docker.
4. Follow [Installing Budget Owl](installing.md) from section 2.

## Things that can go wrong

**"storage 'local' does not accept snippets".** Turn on **Snippets** for that storage (Before you
start), or give another with `--snippets-storage`.

**"VM/CT 9310 already exists. Nothing was changed."** Choose another number with `--vmid`.

**"the download does not match SHA512SUMS. Deleted it."** The download was damaged or altered.
Run the script again. If it happens twice, stop and find out why before going further.

**The log stops at "Cloning into '/opt/budget-owl'...".** The VM cannot reach the repository.
Check the VM's internet access, or point `--repo` at a copy it can reach.

**You can't connect with SSH.** Use the private key that matches the `.pub` file you gave the
script. The VM accepts no passwords, on SSH or on the Proxmox console, which is deliberate. If that
key is lost, the dependable way back is a new VM made with a new key, and your latest backup
restored into it (section 5 of the install guide). The **Cloud-Init** keys in the Proxmox web UI
do not apply to this VM, because the script's own first-boot settings replace them.

## Related

- [Installing Budget Owl](installing.md): sections 3 to 6 apply to the VM unchanged.
- [Give your server an HTTPS address](give-your-server-an-https-address.md)
