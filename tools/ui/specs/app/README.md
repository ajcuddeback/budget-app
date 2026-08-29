# App specs

Specs for the real application. They run only when the app is serving.

Start from `../_TEMPLATE.spec.ts.example`. One file per feature, named to match the feature doc
in `docs/features/`.

Two rules that matter more than the rest:

1. **Query by role, label, and text** — what a user perceives. Never by CSS class or a test id
   used as a crutch. A spec coupled to styling fails on every redesign and teaches people to
   ignore red.
2. **Screenshot the states that are easy to get wrong**: empty, loading, error, and the
   long-content case. Those are where UIs actually look broken, and where nobody looks.
