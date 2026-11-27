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

1. On the Proxmox host, get Budget Owl (for its scripts) and copy your SSH **public** key over:

   ```bash
   git clone https://github.com/ajcuddeback/budget-app.git /root/budget-owl
   cd /root/budget-owl
   ```

2. See what it would do. Nothing is downloaded or created:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub --dry-run
   ```

   It lists every command, starting with the Debian download and ending with `qm start 9310`.

3. Change anything that does not suit your host. The options you are most likely to need:

   | Option                     | Default       | Change it when                                         |
   | -------------------------- | ------------- | ------------------------------------------------------ |
   | `--vmid`                   | `9310`        | that number is taken (the script refuses it if so)     |
   | `--storage`                | `local-lvm`   | your VM disks live elsewhere, e.g. a ZFS pool          |
   | `--bridge`                 | `vmbr0`       | your network bridge has another name                   |
   | `--ip` and `--gw`          | DHCP          | you want a fixed address: `--ip 192.168.1.50/24 --gw 192.168.1.1` |
   | `--memory`, `--cores`, `--disk` | `4096`, `2`, `32` | you have more to spare                          |
   | `--snippets-storage`       | `local`       | another storage holds your snippets                    |
   | `--ref`                    | `main`        | you want a particular version of Budget Owl            |

   `--help` lists the rest.

4. Make it:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub
   ```

   It downloads Debian 13 and checks the download against Debian's published SHA-512 checksums.
   It stops if they differ. Then it creates and starts the VM, and prints what to do next.

5. Wait for the first boot to finish. It installs Docker and builds Budget Owl, which takes **5 to
   15 minutes**. Find the VM's address in the Proxmox web UI (the VM's **Summary** shows it once
   the guest agent is running), or with:

   ```bash
   qm agent 9310 network-get-interfaces
   ```

6. From your own computer, connect and watch it finish:

   ```bash
   ssh owl@<the VM's address>
   sudo tail -f /var/log/budgetowl-bootstrap.log
   ```

   It is done when the last lines say:

   ```
   == Done
   /srv/budgetowl-backups is on this VM's own disk until you mount storage there that lives somewhere else.
   ```

7. Check Budget Owl is running:

   ```bash
   cd /opt/budget-owl
   docker compose ps --format '{{.Service}}   {{.Status}}'
   ```

   All three say `Up` and `(healthy)`.

## What the VM is set up with

- **Debian 13**, with Docker and Docker Compose from Debian's own packages and security
  updates installed automatically.
- **A firewall that lets in SSH and nothing else.** Budget Owl itself answers only inside the VM,
  on `127.0.0.1:8080`.
- **No password login.** You sign in with your SSH key as `owl`, who can use `sudo`.
- **Budget Owl in `/opt/budget-owl`**, owned by `owl`. Its `.env` was made **on the VM**, with
  passwords that never passed through your Proxmox host. Copy it into your password manager:

  ```bash
  cat /opt/budget-owl/.env
  ```

- **A nightly backup at about 03:17**, into `/srv/budgetowl-backups`, keeping the newest 14.

`owl` is in the `docker` group, which means it can do anything on the VM. Do not add anyone else.

## Then

1. **Give it an HTTPS address**: [Give your server an HTTPS address](give-your-server-an-https-address.md).
   Run those steps inside the VM. If you choose the reverse proxy on the VM itself, open its port
   in the firewall: in `/etc/nftables.conf`, add `tcp dport 443 accept` under the
   `tcp dport 22 accept` line, then `sudo systemctl restart nftables`. The Tailscale option needs
   no change.
2. **Create the first account**: open the HTTPS address, and continue with section 3 of
   [Installing Budget Owl](installing.md#3-create-the-first-account).

## Backups on Proxmox

**Snapshots and Proxmox backups (vzdump) are worth having, but not on their own.** They copy the
VM's disk while the database is running. Most of the time that restores fine, because PostgreSQL
recovers from a crash. But "most of the time" is not what you want for the only copy of your
finances, and you can only restore the whole VM, not your Budget Owl data into a new install.
Keep using them, and keep Budget Owl's own backups as well.

**The nightly backup is on the VM's own disk to begin with.** Lose the VM and you lose those
too. Put real storage that lives elsewhere at `/srv/budgetowl-backups`, for example an NFS share
from your NAS:

```bash
sudo apt install nfs-common
echo 'nas.home:/export/budgetowl  /srv/budgetowl-backups  nfs  defaults,_netdev  0 0' | sudo tee -a /etc/fstab
sudo mount /srv/budgetowl-backups
sudo chown owl:owl /srv/budgetowl-backups && sudo chmod 700 /srv/budgetowl-backups
```

To **encrypt** the nightly backups, make an age key on your own computer (see
[Encrypt your backups](installing.md#encrypt-your-backups)), then on the VM put the **public** key
in `/etc/budgetowl/backup.env`:

```bash
BACKUP_AGE_RECIPIENT=age1…
```

Check the timer is running, and run one now:

```bash
systemctl list-timers budgetowl-backup.timer
sudo systemctl start budgetowl-backup.service
ls -l /srv/budgetowl-backups
```

Restoring and testing a restore work as in
[Installing Budget Owl, section 5](installing.md#restore-from-a-backup), from `/opt/budget-owl`.

## Updates

- **Budget Owl**: inside the VM,
  `cd /opt/budget-owl && deploy/upgrade.sh --backup-dir /srv/budgetowl-backups`. See
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
   apt install docker.io docker-cli docker-compose docker-buildx git openssl age qemu-guest-agent unattended-upgrades
   ```

3. Follow [Installing Budget Owl](installing.md) from section 2.

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
