# Memory

Knowledge that accumulates. Not big enough for an ADR, too valuable to lose.

| File | Holds |
|---|---|
| [conventions.md](conventions.md) | Small agreed choices — naming, patterns, "we do it this way here" |
| [glossary.md](glossary.md) | Domain vocabulary. What a word means *in this app* |
| [gotchas.md](gotchas.md) | Traps, surprises, and things that cost someone an hour |
| [decision-log.md](decision-log.md) | Running log of smaller decisions, newest first |

## How to use it

Write with `/remember`, which picks the right file and appends with a date.

**The trigger to write is: "huh, I didn't know that."** If something surprised you, took real
effort to work out, or you had to decide between two reasonable options — capture it before you
move on. The cost of writing it is thirty seconds. The cost of rediscovering it is however long
it took the first time, every time.

## What goes where

- Named a thing and want it named that way consistently → **conventions**
- Discovered a word means something specific here → **glossary**
- Fought a tool, a library, or an environment and won → **gotchas**
- Chose A over B and it wasn't obvious → **decision-log** (or an ADR if it's structural)

## What does *not* go here

- Anything with a reversal cost or rejected alternatives worth preserving → **ADR**
- How a feature behaves → **feature doc**
- Style rules → **guides**
- Secrets, credentials, personal data → nowhere in this repo
