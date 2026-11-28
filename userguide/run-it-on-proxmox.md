# Run it on Proxmox

**For whoever runs the server.** One script on your Proxmox host makes a virtual machine for
Budget Owl. The first time the VM boots, it installs Docker, builds Budget Owl and starts it.

It uses a VM rather than an LXC container. A VM has its own kernel, so a break-in to Budget Owl or
Docker stops at the VM, and Docker needs no special settings there.

## Before you start

- A Proxmox VE host with internet access, and a shell on it as `root`.
- **2 cores, 4 GB of memory and 32 GB of disk** free. Budget Owl itself uses about 600 MB of
  memory; the rest is room to build it on the VM.
- **Snippets** turned on for the `local` storage: **Datacenter → Storage → local → Edit →
  Content**, then add **Snippets**. The VM's first-boot settings are stored there.
- An **SSH key** on your own computer. The VM has no password login, so this key is the only way
  in. If you don't have one, run `ssh-keygen -t ed25519`.

## Make the VM

1. On your own computer, copy your SSH **public** key to the Proxmox host. Use your host's
   address:

   ```bash
   scp ~/.ssh/id_ed25519.pub root@proxmox.home:/root/id_ed25519.pub
   ```

2. On the Proxmox host, get Budget Owl:

   ```bash
   git clone https://github.com/ajcuddeback/budget-app.git /root/budget-owl
   cd /root/budget-owl
   ```

   The VM builds exactly the commit you have here, and refuses to build anything else.

3. See what the script would do. Nothing is downloaded or created:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub --dry-run
   ```

   It prints every command, from the Debian download to `qm start 9310`.

4. If something doesn't suit your host, add the option to the command:

   | Option                          | Default           | Change it when                                             |
   | ------------------------------- | ----------------- | ---------------------------------------------------------- |
   | `--vmid`                        | `9310`            | that number is taken                                       |
   | `--storage`                     | `local-lvm`       | your VM disks live elsewhere, such as a ZFS pool           |
   | `--bridge`                      | `vmbr0`           | your network bridge has another name                       |
   | `--ip` and `--gw`               | DHCP              | you want a fixed address: `--ip 192.168.1.50/24 --gw 192.168.1.1` |
   | `--memory`, `--cores`, `--disk` | `4096`, `2`, `32` | you have more to spare                                     |
   | `--snippets-storage`            | `local`           | another storage holds your snippets                        |

   `--help` lists the rest.

5. Make the VM:

   ```bash
   deploy/proxmox/create-vm.sh --ssh-key /root/id_ed25519.pub
   ```

   It downloads Debian 13, checks it against Debian's published checksums, then creates and
   starts the VM.

6. Find the VM's address. It shows on the VM's **Summary** page in the Proxmox web UI after a
   minute or so, or run:

   ```bash
   qm agent 9310 network-get-interfaces
   ```

7. From your own computer, connect and watch the first boot. It takes **5 to 15 minutes**:

   ```bash
   ssh owl@<the VM's address>
   sudo tail -f /var/log/budgetowl-bootstrap.log
   ```

   It is done when the log ends with a line starting `budgetowl-backup namespaces=`.

8. **Copy that `budgetowl-backup namespaces=…` line into your password manager.** It is the
   public half of the key that signs your backups. If this VM is ever lost, a new one needs it to
   trust your old backups.

9. Copy your settings file into your password manager too. It holds the database passwords, which
   were made on the VM:

   ```bash
   cat /opt/budget-owl/.env
   ```

10. Check Budget Owl is running:

    ```bash
    cd /opt/budget-owl
    docker compose ps --format '{{.Service}}   {{.Status}}'
    ```

    All three lines say `Up` and `(healthy)`.

## Open it

Budget Owl answers only inside the VM, at `127.0.0.1:8080`, and the VM's firewall lets in SSH and
nothing else.

1. **Give it an HTTPS address.** Follow
   [Give your server an HTTPS address](give-your-server-an-https-address.md), running its steps on
   the VM. Tailscale needs no firewall change.

   If you put a reverse proxy on the VM instead, open its port:

   1. In `/etc/nftables.conf`, add `tcp dport 443 accept` on the line under `tcp dport 22 accept`.
   2. Load the change:

      ```bash
      sudo systemctl reload nftables
      ```

      Use `reload`, never `restart`. Restarting the firewall also wipes Docker's rules, and Budget
      Owl stops answering. If that happens, `sudo systemctl restart docker` puts them back.

2. **Create your account.** Open the HTTPS address and follow
   [Create the first account](installing.md#3-create-the-first-account).

3. **Connect your phone**, if you use the app:
   [Use Budget Owl on your phone](features/use-budget-owl-on-your-phone.md).

## Backups

The VM backs up every night at about 03:17 into `/srv/budgetowl-backups`. It keeps the newest 14,
one a week for 8 weeks, and one a month for a year. Every backup is signed by the VM, and a restore
refuses a backup that isn't.

**Make one now and check it worked:**

```bash
sudo systemctl start budgetowl-backup.service
systemctl status budgetowl-backup
ls -l /srv/budgetowl-backups
```

The status shows `status=0/SUCCESS`, and the folder holds a `.dump` file with a `.sig` beside it.
If a night ever fails, `journalctl -u budgetowl-backup -n 50` says why.

**Then do a test restore.** It brings the backup up as a separate copy beside the real one, so you
know the backup works before you need it:

```bash
cd /opt/budget-owl
deploy/test-restore.sh /srv/budgetowl-backups/<the newest .dump>
deploy/test-restore.sh --clean
```

The first command prints where the test copy answers. Once you've encrypted your backups (below),
add `--age-identity <your private key file>`.
[Test your backups](installing.md#test-your-backups) and
[Restore from a backup](installing.md#restore-from-a-backup) cover both in full.

### Keep a copy off the VM

Until you do this, your backups are on the same disk as the database, and losing the VM loses
both. An NFS share on your NAS works well:

1. On the NAS, export a share **to this VM's address only**.
2. On the VM, mount it:

   ```bash
   sudo apt install nfs-common
   echo 'nas.home:/export/budgetowl  /srv/budgetowl-backups  nfs  defaults,_netdev  0 0' | sudo tee -a /etc/fstab
   sudo mount /srv/budgetowl-backups
   sudo chown owl:owl /srv/budgetowl-backups && sudo chmod 700 /srv/budgetowl-backups
   ```

3. Check `ls -ld /srv/budgetowl-backups` shows `owl` as the owner, then run a backup as above.

   If the backup says the folder belongs to someone else, your NAS is mapping file owners to its
   own user. Set the export so files belong to `owl`'s user and group numbers (`id owl` shows
   them). On Synology this is the NFS rule's **Squash** setting.

The backup prints a warning that NFS permissions are up to the NAS. That is expected.

### Encrypt them

Anyone who can read the share can read an unencrypted backup. To encrypt every backup the VM makes:

1. Make an age key on your own computer, as in
   [Encrypt your backups](installing.md#encrypt-your-backups). Keep the private key off the VM.
2. On the VM, add the **public** key to `/etc/budgetowl/backup.env`:

   ```bash
   BACKUP_AGE_RECIPIENT=age1…
   ```

### Proxmox's own backups

Snapshots and vzdump backups of the VM are fine to keep as well, but they don't replace Budget
Owl's own backups. They copy the disk while the database is running, and they restore only the
whole VM.

A vzdump of this VM holds the database, the passwords and the signing key together. Keep it
somewhere other than the backup share, and encrypt it if you can. Proxmox Backup Server can
encrypt it on the host.

## Updates

- **Budget Owl:** on the VM, `cd /opt/budget-owl && deploy/upgrade.sh`. It backs up first, then
  updates and restarts. See [Keep it up to date](installing.md#6-keep-it-up-to-date).
- **Debian and Docker** install security fixes by themselves. For everything else:
  `sudo apt update && sudo apt full-upgrade`.
- **Before a major Proxmox upgrade**, take a Budget Owl backup and a VM snapshot.

## If the VM is lost

1. Make a new VM as above.
2. Before you mount the old backup share, turn off the nightly backup, so the new, empty install
   doesn't start filling the share with backups of nothing:

   ```bash
   sudo systemctl disable --now budgetowl-backup.timer
   ```

3. Mount the share, then restore your newest backup. Give the old VM's signing line from your
   password manager with `--signers`, as
   [Installing Budget Owl, section 5](installing.md#restore-from-a-backup) shows.
4. Turn the nightly backup back on:

   ```bash
   sudo systemctl enable --now budgetowl-backup.timer
   ```

## Things that can go wrong

**"storage 'local' does not accept snippets".** Turn on **Snippets** for that storage (see Before
you start), or name another with `--snippets-storage`.

**"VM/CT 9310 already exists. Nothing was changed."** Choose another number with `--vmid`.

**"the download does not match SHA512SUMS. Deleted it."** The download was damaged. Run the script
again. If it happens twice, stop and find out why.

**The log stops at "Cloning into '/opt/budget-owl'...".** The VM can't reach GitHub. Check its
internet access.

**The log says the VM's checkout "is at …, but the Proxmox host's checkout was at …".** New
commits landed on `main` between your clone and the VM's. Run `git pull` in `/root/budget-owl`
on the host, destroy the VM, and make it again.

**You can't connect with SSH.** Use the private key that matches the `.pub` file you gave the
script. The VM accepts no passwords, on SSH or on the Proxmox console. If the key is lost, make a
new VM with a new key and restore your latest backup into it. The **Cloud-Init** settings in the
Proxmox web UI don't apply to this VM.

**A command says "Run this as the operator (owl), not root".** Run it as `owl` without `sudo`.

## Related

- [Installing Budget Owl](installing.md): sections 3 to 6 apply to the VM unchanged.
- [Give your server an HTTPS address](give-your-server-an-https-address.md)
