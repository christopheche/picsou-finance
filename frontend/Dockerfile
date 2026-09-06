# ─── Stage 1: Build ────────────────────────────────────────────────────────
# The lock file is bun.lock (no package-lock.json exists), so the build must
# use bun with --frozen-lockfile — npm would resolve the `^` ranges afresh on
# every build and ship a dependency set nothing was tested against. Mirrors
# the frontend-build stage of docker/Dockerfile (all-in-one image).
FROM oven/bun:1-alpine AS build

# Allow overriding demo mode at build time (default: false for production)
ARG VITE_DEMO_MODE=false
ENV VITE_DEMO_MODE=$VITE_DEMO_MODE

WORKDIR /app

COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

COPY . .
RUN bun run build

# ─── Stage 2: Serve with Nginx (non-root) ──────────────────────────────────
# nginx-unprivileged runs entirely as the nginx user (no root), listening on 8080
FROM nginxinc/nginx-unprivileged:1.27-alpine

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
