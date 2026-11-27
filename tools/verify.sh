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
      # Deliberately not a hard local failure. The key is a repository SECRET (CI reads
      # secrets.NVD_API_KEY), so it is absent by design on a cloud session and on any machine
      # whose owner has not set one — making every such run red would train people to ignore a
      # red gate, which costs more than it buys. CI fails without it; here it is loud.
      missing "dependency vulnerabilities (OWASP)" \
        "NVD_API_KEY is not set — export it to run this locally (free key: https://nvd.nist.gov/developers/request-an-api-key). CI reads it from the repository secret"
    fi
  fi
fi

# --------------------------------------------------------------- frontend
if [ "$TARGET" = "all" ] || [ "$TARGET" = "frontend" ]; then
  section "Frontend (Angular)"
  if [ ! -f frontend/package.json ]; then
    skip "frontend build" "frontend/ does not exist yet"
  else
    # The Angular CLI refuses to run at all on an unsupported Node and says so in its own words,
    # which surfaces here as three separate red checks that look like code failures. Check it once,
    # up front, and name the actual problem.
    required_node=$(node -p "require('./frontend/node_modules/@angular/cli/package.json').engines.node" 2>/dev/null || echo '')
    running_node=$(node -v 2>/dev/null || echo 'none')
    if [ -n "$required_node" ] && ! npx --prefix frontend semver -r "$required_node" "${running_node#v}" >/dev/null 2>&1; then
      missing "frontend toolchain" "Node $running_node does not satisfy Angular's requirement ($required_node)"
    else

    [ -d frontend/node_modules ] || run "install deps" npm --prefix frontend ci --no-audit --no-fund
    run "lint"                            npm --prefix frontend run lint
    # The backend has had spotless:check in this gate since slice 1; the frontend had no
    # equivalent, so 43 files had drifted from the house style and the format hook rewrote them
    # piecemeal inside whatever commit happened to touch them next.
    run "format (prettier)"               npm --prefix frontend run format:check
    run "typecheck"                       npm --prefix frontend run typecheck
    run "unit tests + coverage threshold" npm --prefix frontend run test:coverage
    run "build"                           npm --prefix frontend run build
    run "npm audit (high+)"               npm --prefix frontend audit --audit-level=high
    fi
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

    # The import size cap in nginx is tested, not just configured (file-import.md rule 3): a body
    # just under and just over it, through the real frontend/nginx.conf in the shipped image.
    if docker info >/dev/null 2>&1; then
      run "nginx passes a statement under the import cap and refuses one over it" tools/nginx-check.sh
    else
      missing "nginx import cap" "Docker is not running"
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
  # Stryker is an ADR-0024 follow-up that has not been done. Without this check the command
  # fails on a missing npm script, which reads as "mutation testing is broken" rather than
  # "it was never wired" — and a command that always fails is one nobody runs.
  if [ ! -f frontend/package.json ]; then
    skip "frontend mutation" "frontend/ does not exist yet"
  elif ! npm --prefix frontend run 2>/dev/null | grep -q '^  test:mutation$'; then
    skip "frontend mutation" "Stryker cannot drive ng test's in-memory vitest config — see the 2026-11-16 amendment to ADR-0024. Not a to-do."
  else
    run "frontend mutation score (Stryker)" npm --prefix frontend run test:mutation
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
  else
    # The harness has had a tsconfig since it was written and nothing that ran it, so a type
    # error sat in mock-api.ts across several slices — found by an agent reading the file, not by
    # the gate. The harness is what checks the app; it is worth checking too. Its own failure is
    # recorded but does not stop the checks below: a type error in one mock route should not
    # hide a real regression in the page.
    run "harness typecheck" bash -c "cd tools/ui && npx tsc -p tsconfig.json --noEmit"
  fi

  if [ ! -d tools/ui/node_modules ]; then
    : # already reported above
  elif [ ! -f frontend/package.json ]; then
    # No app yet: at least prove the harness itself still works.
    run "harness self-check (no app yet)" tools/ui-check.sh --selfcheck
  elif curl -fsS --max-time 2 "${UI_BASE_URL:-http://localhost:4200}" >/dev/null 2>&1; then
    # Something is already serving — a developer with `ng serve` open, or UI_BASE_URL pointing
    # somewhere deliberate. Use it, and say so, because whose build it is matters.
    printf '      against the app already serving at %s\n' "${UI_BASE_URL:-http://localhost:4200}"
    run "live UI checks" tools/ui-check.sh
    printf '      screenshots for review: tools/ui/artifacts/REVIEW.md\n'
  else
    # Nothing serving: START one rather than skipping.
    #
    # This used to skip, and that was the hole. Non-negotiable #7 says this script must pass
    # before the work is done; non-negotiable #8 says frontend changes are checked in a real
    # browser. With a skip here, #7 passed while #8's check never ran — and on a clean machine,
    # a fresh clone or CI, skipping was the DEFAULT. The one thing the project calls
    # non-negotiable was the one most likely to be absent, and the output said PASSED.
    #
    # It costs a dev-server start-up on a run that would otherwise have skipped. That is the
    # price of the check being real, and it is small beside shipping a page nobody looked at.
    printf '      no app serving — starting one (adds a dev-server start to this run)\n'
    run "live UI checks (own server)" tools/ui-check.sh --serve
    printf '      screenshots for review: tools/ui/artifacts/REVIEW.md\n'
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
    # docs/features/mobile-app.md, Testing. Every step runs inside mobile/.
    # The flutter and dart tools report usage to Google unless told not to. A gate run (here or
    # in CI) must not phone home on anyone's behalf (ADR-0016), so every step suppresses it.
    export FLUTTER_SUPPRESS_ANALYTICS=true DART_SUPPRESS_ANALYTICS=true
    mob() { bash -c "cd mobile && $1"; }
    run "dependencies, from the lockfile only" mob "flutter pub get --enforce-lockfile"
    # Generated sources are left as their generators write them; everything else is formatted.
    run "format (dart format)" mob "find lib test tool -name '*.dart' -not -path 'lib/l10n/*' \
      -not -path 'lib/core/theme/tokens.dart' -print0 \
      | xargs -0 dart format --output=none --set-exit-if-changed"
    run "design tokens are current (tools/mobile-tokens.sh)" tools/mobile-tokens.sh --check
    run "strings are current (tools/mobile-l10n.sh)"        tools/mobile-l10n.sh --check
    run "analyze"                 mob "flutter analyze --no-pub"
    # The guards: no double for money, the token only in TokenStore, no redirects, no cleartext,
    # no trust-anyway, nothing printed, no address shipped, tokens-only styling, the platform
    # configs. They also run in the suite below; named here so a failure says which rule broke.
    run "architecture and platform guards" mob "flutter test --no-pub test/architecture"
    run "tests + coverage"        mob "flutter test --no-pub --coverage"
    run "lib/core line coverage >= 80%" tools/mobile-coverage.sh 80
    # The real Android build: manifest, network security config and backup rules must compile into
    # an APK, and the native SQLCipher build hook must succeed. Needs the Android SDK.
    if [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}" ]; then
      run "Android debug build (flutter build apk --debug)" mob "flutter build apk --debug --no-pub"
    else
      missing "Android debug build" "ANDROID_HOME is not set to an Android SDK"
    fi
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
# A green run here is not a green CI run, and saying so once is cheaper than the round trip.
# Slice 2 passed this script and then failed CI on two gates it does not contain.
if [ "$TARGET" = "all" ]; then
  printf '  \033[33mnot run here:\033[0m mutation testing (tools/verify.sh mutation), CodeQL, Trivy image scan\n'
  printf '  CI runs those. PASSED below means this script, not the pull request.\n'
fi
if [ "$FAILED" -eq 0 ]; then
  printf '  \033[32mPASSED\033[0m\n'; exit 0
else
  printf '  \033[31mFAILED\033[0m — fix it, or say plainly what is broken.\n'; exit 1
fi
