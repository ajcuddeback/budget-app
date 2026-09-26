# Budget Owl web app — static assets behind nginx.

FROM node:24-alpine AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginx:1.31-alpine AS runtime

# Apply Alpine's current security updates at build time.
#
# Bumping the base tag alone does not work: an image's rebuild cadence lags the distro's package
# updates, so even the newest nginx:alpine ships packages that already have patches published.
# 1.27 failed the scan on openssl, libxml2, musl, nghttp2 and zlib; bumping to 1.31 cleared all
# five and left libexpat, patched after 1.31 was built. Chasing the tag is a treadmill.
#
# The cost is that two builds of the same commit can contain different package versions. For an
# image that holds people's financial data and is gated on a vulnerability scan, taking the
# patches is worth more than byte-identical rebuilds.
RUN apk upgrade --no-cache
COPY --from=build /build/dist/frontend/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

# nginx's own user, and a writable pid path so the image can run non-root.
RUN touch /var/run/nginx.pid \
  && chown -R nginx:nginx /var/run/nginx.pid /var/cache/nginx /usr/share/nginx/html
USER nginx

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -qO- http://localhost:8080/ >/dev/null || exit 1
