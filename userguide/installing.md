# Installing Budget Owl

> **Status: outline.** Budget Owl is not released yet and these steps do not exist to follow. This
> file is the planned shape of the install guide; it gets written, with real commands and real
> output, when slice 1 ships the Compose file.
>
> **Do not write steps here from the architecture docs.** Write them after actually installing on
> a clean machine and recording what happened — including what went wrong.

Budget Owl runs on your own hardware. You will need a machine that stays on — a home server, a
NAS, a mini PC, a VPS — with Docker installed.

You do not need to be an expert. If you have run anything with Docker Compose before, this will
be familiar; if you have not, the steps below are meant to be followable anyway.

## 1. What you need

*Awaiting slice 1.*

Will cover: Docker and Docker Compose, roughly how much disk and memory, and what to decide
before starting (where data lives, whether it will be reachable outside your network).

## 2. Get it running

*Awaiting slice 1.*

Will cover: the `docker-compose.yml`, the handful of environment variables that matter, setting a
database password, and starting it for the first time.

## 3. Create the first account

Open Budget Owl in a web browser at the address of your server. A new instance shows **Set up
Budget Owl**.

1. Fill in **Your name**, **Email address**, **Password** (at least 12 characters) and **Household
   name**.

2. Select **Create account**.

   You are signed in and land on the **Household** screen. Your account is the **owner** of a new
   household.

![The Set up Budget Owl screen with an empty form and a notice that you will be able to see everything anyone in your household records.](images/create-your-account--setup-form.png)

**You are now the person who runs this instance, and you can see everything.** Every transaction,
every balance and every note anyone in your household records is stored on your server, and you
can see all of it. Budget Owl does not hide it from you. Anyone you invite is told this before
they join; it is worth telling them yourself too.

This screen only appears on a new instance. After the first account exists, everyone else joins
by invitation: see [Invite someone to your household](features/invite-someone.md). The full
walkthrough is in [Create your account and household](features/create-your-account.md).

## 4. Reach it from your phone

*Awaiting slice 15.*

Will cover: pointing the mobile app at your server, what to do about certificates on a local
network, and reaching it from outside your house safely.

## 5. Back it up

*Awaiting slice 1.*

Will cover: what to back up, how to restore, and testing the restore — because a backup nobody
has restored is a hope, not a backup. This matters more here than in most apps: nobody else has a
copy of your data.

## 5b. Run the instance

*Awaiting slice 9.*

Will cover: the admin console — checking the instance is healthy, inviting the rest of your
household, watching the sync queue, and where the logs are when something looks wrong.

## 6. Keep it up to date

*Awaiting slice 1.*

Will cover: pulling a new version, what happens to the database, and what to do if an upgrade
goes wrong.

## Things that commonly go wrong

*To be written from real installs, not imagined.*
