# Budget Owl web app — static assets behind nginx.

FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# Trivy fails the build on HIGH/CRITICAL OS package CVEs, so this tag is a moving target that
# has to be bumped when its Alpine base goes stale — 1.27 accumulated openssl, libxml2, musl and
# zlib findings, all fixed upstream. That the scan forces the bump is the point.
FROM nginx:1.31-alpine AS runtime
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# nginx's own user, and a writable pid path so the image can run non-root.
RUN touch /var/run/nginx.pid \
  && chown -R nginx:nginx /var/run/nginx.pid /var/cache/nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/ >/dev/null || exit 1
