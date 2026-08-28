# Writing for people who use the app

This is a different craft from the developer docs in `docs/`. Someone reading this is trying to
get something done, is possibly stressed about money, and does not care how the app is built.

## Who you are writing for

Someone who has never seen the app, does not know what a "transaction entity" is, and wants to
know **what to click**. They are not reading for pleasure — they have a task and a problem.

Assume: intelligent, in a hurry, not technical, possibly on a phone.
Never assume: they know finance jargon, they read the previous page, or they will experiment.

## Rules

**Write in the second person, in the present tense.** "You'll see your balance update."
Not "the user will be able to observe that the balance has been updated."

**Lead with the task, not the feature.** "Split a transaction between two categories" beats
"Transaction splitting". People search for what they're trying to do.

**One action per numbered step.** If a step contains "and then", it is two steps.

**Say what they'll see after each action.** A step that says what to click but not what happens
leaves the reader unsure whether it worked.

> 3. Select **Save**.
>    Your new account appears in the list with a balance of 0.00.

**Name things exactly as the screen names them.** If the button says "Add account", write
**Add account** — not "the add button" or "Create Account". Bold for anything they click or type
into.

**Cut the throat-clearing.** No "In order to be able to add an account, you will first need to
navigate to...". Just "Go to **Accounts**."

## Words

| Don't write | Write |
|---|---|
| entity, record, object, field | account, transaction, name, amount |
| authenticate, credentials | sign in, password |
| navigate to | go to |
| utilize, leverage | use |
| persisted, submitted successfully | saved |
| invalid input | we couldn't read that amount |
| an error occurred | what actually went wrong, and what to do |

Avoid "simply", "just", "easy", and "obviously". When someone is stuck, being told it's easy is
insulting. Delete the word — the sentence is always better without it.

## Screenshots

- Every screenshot comes from `tools/userguide-capture.sh`, which renders the real UI against
  the demo fixtures in `tools/ui/fixtures/demo-data.ts`. Never mock one up by hand.
- Put it **after** the step it illustrates, not before.
- Every image needs alt text describing what it shows — people using screen readers are using
  this app too.
- Highlight the thing the step refers to (the capture helper draws a numbered ring).
- The names and amounts you see (Alex Rivera, Everyday Checking, 2,480.15) come from that
  fixture file. If a guide needs a scenario the fixtures don't cover, **add it there** — that
  keeps every screenshot consistent and reproducible.

## Structure of a guide

1. **What this lets you do** — one or two sentences, in their language.
2. **Before you start** — anything that must already be true. Omit if nothing.
3. **Steps** — numbered, one action each, with screenshots.
4. **What you'll see** — how they know it worked.
5. **Things that can go wrong** — the real ones, with the fix.
6. **Related** — links to the next thing they'll want.

## Money

Show amounts formatted the way the app shows them, including the currency. Never write an amount
in a guide that the app would display differently — the reader is comparing your text to their
screen, and any mismatch makes them doubt both.

## The test

Read it back as someone who has never seen the app. If at any point you'd have to guess what to
do next, or where you are, rewrite that step.
