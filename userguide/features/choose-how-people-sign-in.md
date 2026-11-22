# Choose how people sign in to your server

Decide whether anyone who can reach your server may create an account, whether people sign in
with an email address and a password, and whether they can sign in through a sign-in provider you
already run. It is all in the **Signing in** block on the **Admin** screen.

None of this changes what anybody can see once they are signed in. Who sees your household's
money is decided by [who is in your household](manage-members.md), not here.

## Before you start

You need to be the person who looks after this server — the one who created the very first
account on it. If you have no **Admin** link, see
[See who can sign in to your server](see-who-can-sign-in.md).

Everything below starts the same way: select **Admin**, then scroll to **Signing in**.

![The Signing in block: a Registration group, an Email and password group, a Single sign-on group, and a Save button.](../images/choose-how-people-sign-in--signing-in.png)

Each group is a set of choices. Nothing takes effect until you select **Save**, and **Save**
sends all three groups at once.

## Let anyone create an account

Normally a new person gets into Budget Owl because you invited them. Opening registration drops
that requirement: anybody who can reach your server can make themselves an account without asking
you.

**Read the warning on the screen before you do this.** In its words: _While registration is open,
anybody who can reach this server over the network can create themselves an account. If it is
reachable from the internet, that means anybody at all._ A new account sees none of your money
until somebody invites it into a household — but it is an account on your server, it is there
until you deal with it, and the **Accounts** list has no button for dealing with it.

1. Go to **Admin**.

2. Under **Registration**, tick **Let anyone create an account**.

   ![The Signing in block with the Registration box unticked, circled and numbered 2, above a note explaining what opening registration means.](../images/choose-how-people-sign-in--registration-off.png)

   A line appears under the warning: **Saving now will open registration.**

   ![The Registration group with the box ticked and the line "Saving now will open registration." added under the warning.](../images/choose-how-people-sign-in--registration-opening.png)

3. Select **Save**.

   **Saved.** appears at the top of the **Signing in** block.

   ![The Signing in block with a green Saved. message at the top and the Registration box ticked.](../images/choose-how-people-sign-in--saved.png)

To close registration again, clear the tick and select **Save**. Accounts that were created while
it was open stay.

## Email and password sign-in

This is how everybody signs in to a new server, and it never goes away: in the screen's words,
_Email and password is a permanent part of Budget Owl._ Once another way in is working you can
stop offering it, and you can always offer it again.

### Why there is no switch yet

On a server with no sign-in provider set up, the **Email and password** group has no tick box at
all. It states what is true — **Email and password sign-in is on.** — and then why you cannot
change it.

![The Email and password group with no tick box, showing "Email and password sign-in is on." and a panel headed "This cannot be turned off yet".](../images/choose-how-people-sign-in--password-locked.png)

This is a guard, not a fault. Budget Owl will not let you put away the way in that works until
somebody has come in through the new one and proved it works. Otherwise a provider that is not
quite configured leaves nobody able to reach their own records, on hardware in their own house,
with nobody to ring.

To get the switch, [set up single sign-on](#use-your-own-sign-in-provider) and have an owner of
your household sign in through it once.

### Stop offering email and password sign-in

1. Go to **Admin**.

2. Under **Email and password**, clear **Offer sign-in with an email address and a password**.

   ![The Email and password group with the switch ticked, circled and numbered 2, on a server whose provider has been proved.](../images/choose-how-people-sign-in--password-switch.png)

   A panel appears naming the command that switches it back on from the server itself.

   ![The Email and password group with the switch cleared, showing the recovery panel naming deploy/restore-password-login.sh.](../images/choose-how-people-sign-in--password-off-recovery.png)

3. Select **Save**.

   **Saved.** appears, and the sign-in page stops offering an email address and a password.

**Write that command down somewhere that is not Budget Owl.** If your provider stops working
afterwards, the sign-in page has nothing left to offer and no screen can help you — see
[Nobody can sign in](../installing.md#nobody-can-sign-in).

## Use your own sign-in provider

If you already run something your household signs in to, Budget Owl can use it as a second way
in. You will need three things from that provider before you start: the address it publishes its
configuration at, the identifier it issued for Budget Owl, and the secret that goes with that
identifier.

1. Go to **Admin**.

2. Under **Single sign-on**, tick **Offer sign-in through my provider**.

   ![The Signing in block with the Single sign-on box unticked, circled and numbered 2.](../images/choose-how-people-sign-in--sso-off.png)

   Four more settings appear.

   ![The Single sign-on group showing empty Issuer URL and Client ID boxes, an empty Client secret box reading "No secret set.", and an unticked box for creating accounts.](../images/choose-how-people-sign-in--sso-fields.png)

3. Enter the address your provider publishes its configuration at in **Issuer URL**.

   Use `https`. The screen says why: your client secret is sent to this address.

4. Enter the identifier your provider issued for Budget Owl in **Client ID**.

5. Enter the secret that goes with it in **Client secret**.

   What you type is hidden as dots. The line underneath still says **No secret set.** until you
   save — it describes what is stored, not what you have typed.

6. Decide whether to tick **Create an account for anybody who signs in through the provider**.

   Ticked, anybody your provider recognises gets an account here the first time they sign in.
   That account sees nothing on its own — joining your household still needs an invitation.
   Unticked, people need an account here already.

   ![The Single sign-on fields filled in with an issuer address, a client ID, a hidden client secret, and the account-creation box ticked.](../images/choose-how-people-sign-in--sso-filled.png)

7. Select **Save**.

   **Saved.** appears, the **Client secret** box is empty again, and the line under it now reads
   **A secret is set.**

   ![The saved Single sign-on settings: the client secret box empty with "A secret is set." beneath it, and a line saying no owner has signed in through the provider yet.](../images/choose-how-people-sign-in--sso-saved.png)

Under the tick box, Budget Owl tracks whether anyone has actually arrived this way:

- **No owner has signed in through your provider yet. Until one has, email and password sign-in
  stays on.**
- Once one has: **An owner signed in through your provider on …, so email and password sign-in
  can now be turned off.**

![The Single sign-on group on a server where an owner has signed in through the provider, with the date they did it.](../images/choose-how-people-sign-in--provider-proven.png)

## The client secret, and why the box is always empty

**Budget Owl never shows a stored client secret back — not to anybody, including you.** The box
is empty every time you open the screen and empty again after every save. All the screen will
ever tell you is **A secret is set.** or **No secret set.**

![The Client secret box, empty, with "A secret is set." beneath it and a note that Budget Owl never shows a stored secret back.](../images/choose-how-people-sign-in--secret-field.png)

This is deliberate. A secret that gets filled back into a web page ends up in a browser's cache,
in the screenshot somebody takes of the screen, and in the bug report they attach it to. Budget
Owl would rather not be able to show it to you at all.

So:

- **Leave the box empty** to keep the secret you already have. Saving the screen with an empty
  box changes nothing about it.
- **Type a new one** to replace it.
- **If you cannot remember what it is, replace it.** Checking is not something you can do.

### Replacing the secret turns email and password sign-in back on

This is the one thing on this screen most likely to look like a fault. It is not.

1. Type the new secret in **Client secret**.

2. Select **Save**.

   **Saved.** appears — and the **Email and password** group changes. It now reads **Email and
   password sign-in is on.**, with **This cannot be turned off yet** beneath it, and the line
   under the single sign-on tick box goes back to **No owner has signed in through your provider
   yet.**

   ![The Signing in block after replacing the client secret: email and password sign-in is on and cannot be turned off, and no owner has signed in through the provider yet.](../images/choose-how-people-sign-in--secret-rotated.png)

Why: the secret is half of what proves Budget Owl is allowed to talk to your provider. The moment
you change it, nobody has yet come in using the new one, so Budget Owl treats the provider as
unproven again and puts the familiar way in back. If the new secret has a typo in it, that choice
is the difference between an annoyance and nobody being able to sign in.

To hide email and password sign-in again, have an owner sign in through the provider once with
the new secret in place, then clear the switch and select **Save** as before.

Changing the **Issuer URL** or the **Client ID** does the same thing, for the same reason. Saving
the screen without changing any of the three leaves everything alone.

## Turn single sign-on off

1. Go to **Admin**.

2. Under **Single sign-on**, clear **Offer sign-in through my provider**.

   A panel warns you that email and password sign-in comes back on.

   ![The Single sign-on group with the box cleared and a panel saying that turning this off also switches email and password sign-in back on.](../images/choose-how-people-sign-in--sso-turning-off.png)

3. Select **Save**.

   A window asks **Turn off single sign-on?** and explains that your provider's details are
   cleared and anybody who signed in through it will need a password instead.

   ![The window "Turn off single sign-on?" with Cancel and Turn it off buttons.](../images/choose-how-people-sign-in--sso-off-confirm.png)

4. Select **Turn it off**.

   **Saved.** appears. The **Issuer URL**, **Client ID** and client secret are gone, and email and
   password sign-in is on again.

To change your mind, select **Cancel**. Nothing is sent and single sign-on stays on — but the
screen still shows the box cleared and the warning under it, so tick **Offer sign-in through my
provider** again before you leave the page.

![The Single sign-on group after cancelling: the box is still cleared and the warning is still showing.](../images/choose-how-people-sign-in--sso-off-cancelled.png)

## Things that can go wrong

**Somebody else saved first.**
Two people had this screen open and the other one saved while you were typing. Nothing of yours
has been saved and nothing of yours has been lost — what you entered is still in the boxes, and
the notice lists which settings the other person changed.

![The Signing in block with a panel headed "Somebody else saved first", listing Registration as what changed, with Take the latest and Save my changes anyway buttons.](../images/choose-how-people-sign-in--conflict.png)

Select **Take the latest** to throw your changes away and start from what is now saved, or **Save
my changes anyway** to send yours on top. Read the list first: if the other person changed the
same setting you did, **Save my changes anyway** undoes their change.

**I selected Save and it said "Nothing had changed, so nothing was saved."**
Exactly what it says — every setting matched what was already stored, so nothing was sent.
Leaving the **Client secret** box empty is not a change.

![The Signing in block with the message "Nothing had changed, so nothing was saved." at the top.](../images/choose-how-people-sign-in--nothing-changed.png)

**Nobody can sign in.**
If you hid email and password sign-in and your provider has since stopped working, no screen can
help, because the screen is the thing you cannot reach. The fix runs on the machine — see
[Nobody can sign in](../installing.md#nobody-can-sign-in).

## Related

- [See who can sign in to your server](see-who-can-sign-in.md)
- [Invite someone to your household](invite-someone.md)
- [Sign in](sign-in.md)
- [Installing Budget Owl](../installing.md)
