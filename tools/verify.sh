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

# A Claude Design export ships the design project's uploaded attachments next to the canvas, and
# what people upload to a budgeting design project is their own budget. .gitignore covers the
# usual shapes; this catches a `git add -f`, an unusual extension, or a file that was already
# tracked before the rule existed. Publishing the maintainer's finances would contradict the
# product's entire premise (ADR-0016), and git history does not forget. See design/README.md.
if git ls-files -- design | grep -vE '(^|/)_ds/' \
   | grep -qiE '\.(xlsx?|csv|tsv|ofx|qfx|qif|numbers|json)$|(^|/)uploads/'; then
  fail "no financial-data files tracked under design/"
else
  pass "no financial-data files tracked under design/"
fi

# A font CDN is a mandatory internet dependency and an IP leak to a third party on every page
# load (non-negotiable #9). Fonts are vendored in design/fonts/. design/canvas/ is exempt: it is
# a verbatim Claude Design export we preserve as-is, and a re-export would reintroduce the link
# there — this check is what stops it spreading into our own code.
if git grep -nI -E 'fonts\.(googleapis|gstatic)\.com|use\.typekit|fonts\.bunny\.net' \
     -- ':!design/canvas' ':!*.md' ':!tools/verify.sh' 2>/dev/null | grep -q .; then
  fail "no webfont CDN references outside design/canvas/"
else
  pass "no webfont CDN references outside design/canvas/"
fi

# ---------------------------------------------------------------- backend
if [ "$TARGET" = "all" ] || [ "$TARGET" = "backend" ]; then
  section "Backend (Java / Spring Boot)"
  if [ ! -f backend/pom.xml ]; then
    skip "backend build" "backend/ does not exist yet"
  else
    run "format (spotless:check)" mvn -q -f backend spotless:check

    if docker info >/dev/null 2>&1; then
      # One pass: compile, unit + architecture tests, then integration tests against a real
      # PostgreSQL via Testcontainers (ADR-0009). ArchUnit runs inside the unit phase.
      run "compile + unit + architecture + integration tests" mvn -q -f backend verify
      run "coverage threshold (JaCoCo)" mvn -q -f backend jacoco:check
    else
      # Without Docker the integration tests cannot run at all, and a coverage number taken
      # without them is meaningless rather than merely lower.
      run "compile + unit + architecture tests" mvn -q -f backend test
      missing "integration tests + coverage" "Docker not available for Testcontainers (ADR-0009)"
    fi

    run "static analysis (SpotBugs)" mvn -q -f backend com.github.spotbugs:spotbugs-maven-plugin:4.10.4.1:check
    run "static analysis (PMD)"      mvn -q -f backend org.apache.maven.plugins:maven-pmd-plugin:check

    # The NVD feed needs an API key to be usable; without one the scan is rate-limited into
    # uselessness and would report "no vulnerabilities" because it never finished. Silence is
    # not a pass, so say which happened.
    if [ -n "${NVD_API_KEY:-}" ]; then
      run "dependency vulnerabilities (OWASP)" mvn -q -f backend dependency-check:check
    else
      missing "dependency vulnerabilities (OWASP)" "NVD_API_KEY is not set"
    fi
  fi
fi

# --------------------------------------------------------------- frontend
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then
  section "Frontend (Angular)"
  if [ ! -f frontend/package.json ]; then
    skip "frontend build" "frontend/ does not exist yet"
  else
    [ -d frontend/node_modules ] || run "install deps" npm --prefix frontend ci --no-audit --no-fund
    run "lint"                            npm --prefix frontend run lint
    run "typecheck"                       npm --prefix frontend run typecheck
    run "unit tests + coverage threshold" npm --prefix frontend run test:coverage
    run "build"                           npm --prefix frontend run build
    run "npm audit (high+)"               npm --prefix frontend audit --audit-level=high
  fi
fi

# ------------------------------------------------------------- packaging
if [ "$TARGET" = "all" ] || [ "$TARGET" = "packaging" ]; then
  section "Packaging (Docker Compose)"
  if [ ! -f docker-compose.yml ]; then
    skip "compose validation" "docker-compose.yml does not exist yet"
  else
    # The compose file IS the product for a self-hoster (ADR-0016), so a typo in it is a shipped
    # bug. A dummy password only satisfies interpolation; nothing is started.
    run "compose file is valid" env DB_PASSWORD=verify-only docker compose config --quiet

    # First run must fail loudly with no password rather than defaulting to something weak.
    if DB_PASSWORD= docker compose config --quiet >/dev/null 2>&1; then
      fail "compose refuses to start without DB_PASSWORD"
    else
      pass "compose refuses to start without DB_PASSWORD"
    fi

    # Only the web port reaches the host, and only on loopback by default.
    published=$(env DB_PASSWORD=verify-only docker compose config 2>/dev/null | grep -c 'published:' || true)
    if [ "$published" -eq 1 ]; then
      pass "only one port is published to the host"
    else
      fail "only one port is published to the host (found $published)"
    fi
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
