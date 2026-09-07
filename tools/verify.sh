#!/usr/bin/env bash
# The gate. If this does not pass, the work is not done.
# Usage: tools/verify.sh [backend|frontend|mobile|all|mutation]
#
#   all (default)  everything fast enough to run on every change
#   mutation       mutation testing — SLOW, opt-in, not part of `all` (ADR-0024)
set -uo pipefail
cd "$(dirname "$0")/.."

TARGET="${1:-all}"
FAILED=0
SKIPPED=()

pass() { printf '  \033[32m✓\033[0m %s\n' "$1"; }
fail() { printf '  \033[31m✗\033[0m %s\n' "$1"; FAILED=1; }

# Two kinds of "did not run", deliberately distinguished.
#
# skip()    — legitimately absent: a stack that has not been built yet. Benign everywhere.
# missing() — SHOULD have been available and was not (Docker, installed deps). Locally this is a
#             warning so you can still get partial signal; in CI it is a FAILURE, because a
#             silent skip is exactly how integration tests stop running and nobody notices.
#             "A skip is not a pass" was printed advice until this existed; now it is enforced.
skip() { printf '  \033[33m-\033[0m %s (skipped: %s)\n' "$1" "$2"; SKIPPED+=("$1"); }
missing() {
  if [ -n "${CI:-}" ]; then
    fail "$1 — REQUIRED in CI but unavailable: $2"
  else
    printf '  \033[33m!\033[0m %s (unavailable: %s) — this would FAIL in CI\n' "$1" "$2"
    SKIPPED+=("$1")
  fi
}
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

run() { # run <label> <command...>
  local label="$1"; shift
  if "$@" >/tmp/verify-out.txt 2>&1; then pass "$label"
  else fail "$label"; sed 's/^/      /' /tmp/verify-out.txt | tail -40; fi
}

section "Repository hygiene"
if git ls-files | grep -qE '(^|/)\.env$|\.pem$|\.p12$|(^|/)id_rsa'; then
  fail "no secret-shaped files tracked in git"
else
  pass "no secret-shaped files tracked in git"
fi

if git grep -nIE '(password|secret|api[_-]?key|access[_-]?token)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"']{8,}["'"'"']' \
     -- ':!docs' ':!.claude' ':!.agents' 2>/dev/null \
   | grep -vE '\$\{|process\.env|System\.getenv|example|EXAMPLE|changeme|placeholder' | grep -q .; then
  fail "no hardcoded credentials in tracked source"
else
  pass "no hardcoded credentials in tracked source"
fi

# ---------------------------------------------------------------- backend
if [ "$TARGET" = "all" ] || [ "$TARGET" = "backend" ]; then
  section "Backend (Java / Spring Boot)"
  if [ ! -f backend/pom.xml ]; then
    skip "backend build" "backend/ does not exist yet"
  else
    run "format (spotless:check)"  mvn -q -f backend spotless:check
    run "compile"                  mvn -q -f backend compile
    # Architecture tests (ArchUnit) run as ordinary JUnit tests inside this step: no `web` ->
    # `persistence` calls, every financial repository method takes a householdId, no double for
    # money, no entities in controller signatures (ADR-0024).
    run "unit + slice + architecture tests" mvn -q -f backend test

    if docker info >/dev/null 2>&1; then
      run "integration tests + migrations (Testcontainers)" mvn -q -f backend verify -DskipUnitTests
      run "backend coverage threshold (JaCoCo)" mvn -q -f backend jacoco:check
    else
      missing "integration tests + coverage" "Docker not available for Testcontainers (ADR-0009)"
    fi

    run "dependency vulnerability scan" mvn -q -f backend dependency-check:check
  fi
fi

# --------------------------------------------------------------- frontend
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then
  section "Frontend (Angular)"
  if [ ! -f frontend/package.json ]; then
    skip "frontend build" "frontend/ does not exist yet"
  else
    [ -d frontend/node_modules ] || run "install deps" npm --prefix frontend ci
    run "lint"        npm --prefix frontend run lint
    run "typecheck"   npx --prefix frontend tsc -p frontend/tsconfig.json --noEmit
    run "unit tests + coverage threshold" npm --prefix frontend test -- --watch=false --coverage
    run "build"       npm --prefix frontend run build
    run "npm audit (high+)" npm --prefix frontend audit --audit-level=high
  fi
fi

# ---------------------------------------------------------------- mutation
# Deliberately NOT part of `all`: PIT and Stryker take minutes, and a slow gate is a gate people
# stop running. Coverage says a line was executed; mutation testing says a test would FAIL if
# that line were wrong (ADR-0024). CI runs it scoped to changed classes on PRs and fully on a
# schedule.
if [ "$TARGET" = "mutation" ]; then
  section "Mutation testing (slow)"
  if [ -f backend/pom.xml ]; then
    run "backend mutation score (PIT)" mvn -q -f backend org.pitest:pitest-maven:mutationCoverage
  else
    skip "backend mutation" "backend/ does not exist yet"
  fi
  if [ -f frontend/package.json ]; then
    run "frontend mutation score (Stryker)" npm --prefix frontend run test:mutation
  else
    skip "frontend mutation" "frontend/ does not exist yet"
  fi
  section "Result"
  if [ "$FAILED" -eq 0 ]; then printf '  \033[32mPASSED\033[0m\n'; exit 0
  else printf '  \033[31mFAILED\033[0m\n'; exit 1; fi
fi

# -------------------------------------------------------------------- ui
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then
  section "UI validation harness"
  if [ ! -d tools/ui/node_modules ]; then
    skip "UI harness" "dependencies not installed (cd tools/ui && npm ci)"
  elif [ ! -f frontend/package.json ]; then
    # No app yet: at least prove the harness itself still works.
    run "harness self-check (no app yet)" tools/ui-check.sh --selfcheck
  elif curl -fsS --max-time 2 "${UI_BASE_URL:-http://localhost:4200}" >/dev/null 2>&1; then
    run "live UI checks" tools/ui-check.sh
    printf '      screenshots for review: tools/ui/artifacts/REVIEW.md\n'
  else
    skip "live UI checks" "app not serving — run tools/ui-check.sh --serve to include them"
  fi
fi

# ---------------------------------------------------------------- mobile
if [ "$TARGET" = "all" ] || [ "$TARGET" = "mobile" ]; then
  section "Mobile (Flutter)"
  if [ ! -f mobile/pubspec.yaml ]; then
    skip "mobile build" "mobile/ does not exist yet"
  elif ! command -v flutter >/dev/null 2>&1; then
    missing "mobile build" "flutter not on PATH"
  else
    run "analyze"                 flutter analyze --no-pub
    run "tests + coverage"        flutter test --coverage
  fi
fi

# ------------------------------------------------------------ user guide
section "User guide"
if [ -d userguide ]; then
  run "user guide integrity" tools/userguide-check.sh
else
  skip "user guide" "userguide/ does not exist"
fi

# ------------------------------------------------------------------ docs
section "Documentation"
missing=0
for f in CLAUDE.md docs/README.md docs/architecture/security-model.md docs/adr/README.md docs/features/README.md; do
  [ -f "$f" ] || { fail "missing $f"; missing=1; }
done
[ "$missing" -eq 0 ] && pass "required docs present"

# broken relative links inside docs/
broken=0
while IFS= read -r src; do
  while IFS= read -r link; do
    case "$link" in http*|\#*|mailto:*) continue ;; esac
    target="$(dirname "$src")/${link%%#*}"
    [ -e "$target" ] || { fail "broken link in $src -> $link"; broken=1; }
  done < <(grep -oE '\]\([^)]+\)' "$src" 2>/dev/null | sed 's/^](//;s/)$//')
done < <(find docs -name '*.md')
[ "$broken" -eq 0 ] && pass "no broken relative links in docs/"

# ---------------------------------------------------------------- summary
section "Result"
if [ ${#SKIPPED[@]} -gt 0 ]; then
  printf '  \033[33mskipped:\033[0m %s\n' "$(IFS=,; echo "${SKIPPED[*]}")"
  printf '  A skip is not a pass. Say so when you report this.\n'
fi
if [ "$FAILED" -eq 0 ]; then
  printf '  \033[32mPASSED\033[0m\n'; exit 0
else
  printf '  \033[31mFAILED\033[0m — fix it, or say plainly what is broken.\n'; exit 1
fi
