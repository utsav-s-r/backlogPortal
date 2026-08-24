# Single production image: the React SPA is built and bundled INTO the Spring Boot jar, so one
# container serves both the app and the API on one origin.
#
# Why one container rather than nginx + backend:
#   * One origin means api.js's hardcoded baseURL "/api" and the SameSite=Lax session cookie both
#     keep working with zero code changes. A split origin silently drops the cookie on every XHR.
#   * Free container tiers give ONE instance. Two services do not fit; this does.
# The SPA fallback that nginx's `try_files` used to provide is now SpaStaticResourceConfig.
#
# Build context is the REPO ROOT (both frontend/ and backend/ are needed):
#   docker build -t backlog .

# ---------- stage 1: build the SPA ----------
# Node 26 matches frontend/.node-version (26.7.0).
FROM node:26-alpine AS frontend
WORKDIR /web

# Cypress's postinstall pulls a ~200MB Electron binary that an asset build never uses. Note
# `npm ci --omit=dev` is NOT an option instead: vite and tailwind are devDependencies and the
# build needs them.
ENV CYPRESS_INSTALL_BINARY=0

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
# NOT `npm run lint` — 9 intentional, documented react-hooks errors mean lint exits non-zero by
# design. `vite build` does not run eslint.
RUN npm run build

# ---------- stage 2: build the jar ----------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /build

# Wrapper + pom on their own layer so dependency resolution is cached across source edits.
COPY backend/backlog/.mvn/ .mvn/
COPY backend/backlog/mvnw backend/backlog/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

COPY backend/backlog/src/ src/

# The built SPA becomes classpath:/static/ inside the jar, which is where
# SpaStaticResourceConfig looks for it.
COPY --from=frontend /web/dist/ src/main/resources/static/

# application.properties is GITIGNORED, so it is absent from a clean checkout and the app would
# fail at startup (app.jwt.secret has no default). Generate it from the tracked template, which is
# pure ${ENV:default} placeholders — every real value still arrives from the environment at
# runtime. This is also what makes a laptop build identical to a CI/platform build.
RUN cp src/main/resources/application.properties.example src/main/resources/application.properties \
 && ./mvnw -B clean package -DskipTests

# ---------- stage 3: runtime ----------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN useradd --system --no-create-home --shell /usr/sbin/nologin spring
COPY --from=backend /build/target/*.jar /app/app.jar
RUN chown spring:spring /app/app.jar
USER spring

EXPOSE 8080

# Tuned for a ~512MB free-tier container:
#   MaxRAMPercentage 75 / InitialRAMPercentage 40 — percentages, not -Xmx, so the JVM reads the
#     container's cgroup limit and adapts to whatever the platform actually allots.
#   UseSerialGC — at a fraction of a vCPU there is no parallelism for a concurrent collector to
#     exploit, so the serial collector's lower overhead is a straight win.
# Override JAVA_OPTS wholesale on a larger instance.
ENV JAVA_OPTS="-XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC"

# TCP, not HTTP: there is no actuator dependency, and the public /api/registration-status endpoint
# would make health depend on the DATABASE being awake — which on a serverless Neon that suspends
# when idle would report a healthy app as unhealthy.
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
