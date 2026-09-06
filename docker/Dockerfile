# ============================================================
# Picsou — All-in-one Docker image (frontend + backend)
# Build context: project root (..)
# ============================================================

# ---------- Stage 1: Build frontend ----------
# Major-pinned: an unversioned `oven/bun:alpine` would let a bun 2.x release
# change bun.lock semantics and break --frozen-lockfile on a rebuild of the
# same git revision.
FROM oven/bun:1-alpine AS frontend-build

WORKDIR /build/frontend

ARG VITE_DEMO_MODE=false
ARG APP_VERSION=dev
ENV VITE_DEMO_MODE=$VITE_DEMO_MODE
ENV VITE_APP_VERSION=$APP_VERSION

COPY frontend/package.json frontend/bun.lock ./
RUN bun install --frozen-lockfile

COPY frontend/ .
RUN bun run build

# ---------- Stage 2: Build backend ----------
FROM maven:3.9-eclipse-temurin-21-alpine AS backend-build

WORKDIR /build/backend

COPY backend/pom.xml .
RUN mvn dependency:go-offline -q

COPY backend/src ./src
RUN mvn package -DskipTests -q

# ---------- Stage 3: Runtime ----------
FROM eclipse-temurin:21-jre-jammy

ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION

# Install Nginx + supervisor + openssl (entrypoint auto-generates secrets on
# first boot) + curl (HEALTHCHECK probe below)
RUN apt-get update && apt-get install -y --no-install-recommends \
        nginx \
        supervisor \
        openssl \
        curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Copy built frontend
COPY --from=frontend-build /build/frontend/dist /usr/share/nginx/html

# Copy built backend
COPY --from=backend-build /build/backend/target/*.jar /app/app.jar

# Copy configs
COPY docker/nginx.conf /etc/nginx/sites-available/picsou
RUN rm -f /etc/nginx/sites-enabled/default \
    && ln -s /etc/nginx/sites-available/picsou /etc/nginx/sites-enabled/picsou \
    # nginx.conf includes this snippet unconditionally; entrypoint.sh rewrites
    # it on every boot from HSTS_ENABLED. Created empty here so the config is
    # valid even if nginx is exercised without the entrypoint (e.g. nginx -t).
    && mkdir -p /etc/nginx/snippets \
    && : > /etc/nginx/snippets/picsou-hsts.conf \
    # Fail the build on a broken nginx config rather than at container start.
    # Needs the docroot to exist, and validates the HSTS-disabled shape (the
    # enabled one is exercised by the entrypoint at boot).
    && mkdir -p /usr/share/nginx/html \
    && nginx -t

COPY docker/supervisord.conf /etc/supervisor/conf.d/picsou.conf
COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080

# Probe through nginx so a failing backend (crash-looping under supervisord
# while nginx keeps answering 502) flips the container to `unhealthy` instead
# of staying `Up` forever. /actuator/health is permitAll in SecurityConfig.
# The start period covers Spring's boot on a slow NAS; docker-compose.yml
# repeats the same probe so `depends_on: condition: service_healthy` works.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health > /dev/null || exit 1

ENTRYPOINT ["/app/entrypoint.sh"]
