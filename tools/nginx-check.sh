#!/usr/bin/env bash
# Sends a request body just under and just over the import cap through the REAL frontend/nginx.conf,
# in the nginx image the frontend ships on (docs/features/file-import.md rule 3, layer a).
#
# Why this exists: nginx's default client_max_body_size is 1 MiB, and a statement of up to 5 MiB is
# ~7 MiB as base64 in JSON. Without the `location /api/imports` block every real import would get
# nginx's own 413 page — and `ng serve`'s proxy would never show it. A limit in a config file is a
# claim; this is the evidence (docs/memory/gotchas.md, "a tool that silently discards unknown
# configuration").
#
# A stand-in upstream named `api` runs beside it on a private Docker network, so the config's
# resolver and proxy_pass are exercised as Compose would: under the cap the body reaches it (200),
# over the cap nginx answers 413 with the API's own problem code — with and without Content-Length
# — and other /api routes keep the 1 MiB default.
set -euo pipefail
cd "$(dirname "$0")/.."

image=$(sed -n 's/^FROM \(nginx:[^ ]*\).*/\1/p' frontend/Dockerfile | head -1)
[ -n "$image" ] || { echo "could not read the nginx image from frontend/Dockerfile" >&2; exit 1; }

name="budgetowl-nginx-check-$$"
upstream="budgetowl-nginx-check-api-$$"
network="budgetowl-nginx-check-$$"
work=$(mktemp -d)
trap 'docker rm -f "$name" "$upstream" >/dev/null 2>&1 || true; docker network rm "$network" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT

# The stand-in API: accepts any body of any size and answers 200, reading it all.
cat > "$work/api.conf" <<'CONF'
server {
    listen 8080;
    client_max_body_size 0;
    location / { proxy_pass http://127.0.0.1:8081; }
}
server {
    listen 8081;
    client_max_body_size 0;
    location / { return 200 'upstream'; }
}
CONF
chmod 644 "$work/api.conf"

docker network create "$network" >/dev/null
docker run -d --rm --name "$upstream" --network "$network" --network-alias api \
  -v "$work/api.conf:/etc/nginx/conf.d/default.conf:ro" "$image" >/dev/null
docker run -d --rm --name "$name" --network "$network" \
  -v "$PWD/frontend/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  -p 127.0.0.1::8080 "$image" >/dev/null
docker exec "$name" nginx -t -q

port=$(docker port "$name" 8080/tcp | head -1 | sed 's/.*://')
for _ in $(seq 1 50); do
  curl -s -o /dev/null "http://127.0.0.1:$port/index.html" && break
  sleep 0.1
done

body() { # body <bytes> <file>
  head -c "$1" /dev/zero | tr '\0' 'A' > "$2"
}
status() { # status <path> <file> [curl args...]
  local path="$1" file="$2"; shift 2
  curl -s -o "$work/out" -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
    --data-binary "@$file" "$@" "http://127.0.0.1:$port$path"
}

failed=0
check() { # check <label> <condition-result>
  if [ "$2" = ok ]; then printf '  ok   %s\n' "$1"; else printf '  FAIL %s\n' "$1"; failed=1; fi
}

body $((7 * 1024 * 1024 - 4096)) "$work/under"
body $((7 * 1024 * 1024 + 1)) "$work/over"
body $((1024 * 1024 + 4096)) "$work/elsewhere"

code=$(status /api/imports/preview "$work/under")
check "just under the cap reaches the API (got $code)" "$([ "$code" = 200 ] && echo ok)"

code=$(status /api/imports/preview "$work/under" -H 'Transfer-Encoding: chunked')
check "just under the cap, chunked, reaches the API (got $code)" "$([ "$code" = 200 ] && echo ok)"

code=$(status /api/imports/preview "$work/over")
check "just over the cap is 413 (got $code)" "$([ "$code" = 413 ] && echo ok)"
check "  ... with the API's own problem code" "$(grep -q '"code":"import-file-too-large"' "$work/out" && echo ok)"

code=$(status /api/imports "$work/over" -H 'Transfer-Encoding: chunked')
check "just over the cap with no Content-Length is 413 (got $code)" "$([ "$code" = 413 ] && echo ok)"

code=$(status /api/accounts "$work/elsewhere")
check "every other route keeps the 1 MiB default (got $code)" "$([ "$code" = 413 ] && echo ok)"

spooled=$(docker exec "$name" sh -c 'find /var/cache/nginx /tmp -type f 2>/dev/null | wc -l')
check "nothing spooled to /var/cache/nginx ($spooled files)" "$([ "$spooled" -eq 0 ] && echo ok)"

# A spooled body is deleted when the request ends, so an empty directory afterwards proves little.
# nginx says so in its error log at the moment it does it: "a client request body is buffered to a
# temporary file". With proxy_request_buffering off on /api/imports, that line never appears for
# an import, however large the body. (Found by sabotage: turning buffering back on passed the
# directory check above.)
buffered=$(docker logs "$name" 2>&1 | grep -c 'buffered to a temporary file' || true)
check "no import body was buffered to a temporary file ($buffered log lines)" \
  "$([ "$buffered" -eq 0 ] && echo ok)"

exit "$failed"
