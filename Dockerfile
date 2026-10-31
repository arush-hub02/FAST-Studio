# FAST Studio: one image with the Spring Boot backend, the built React frontend and the mailer.
# The Cloudflare Worker (worker/) is deployed separately with wrangler; point FASTSTUDIO_EDGE_URL / _PUBLIC_URL at it.
#
#   docker build -t fast-studio .
#   docker run -p 8080:8080 -v fs-data:/data --env-file .env fast-studio
#
# Starts in the real-data "pilot" profile: no demo companies or accounts. For production set DB_URL (+ DB_USER,
# DB_PASSWORD) to a managed PostgreSQL; without it the built-in database runs inside the container in /data/pg.
# Every setting and secret is an environment variable (see .env.example); none are baked into the image.

# ---- 1. frontend: type-check and build into backend/src/main/resources/static
FROM node:24-slim AS web
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. mailer: production dependencies only (nodemailer)
FROM node:24-slim AS mailer
WORKDIR /mailer
COPY mailer/package.json mailer/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY mailer/send.mjs ./

# ---- 3. backend jar with the built frontend inside (tests run in CI, not here)
FROM eclipse-temurin:21-jdk AS jar
WORKDIR /build/backend
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN sh ./mvnw -B -ntp -q dependency:go-offline
COPY backend/src src
COPY --from=web /build/backend/src/main/resources/static src/main/resources/static
RUN sh ./mvnw -B -ntp -q -DskipTests package && cp target/backend-*.jar /app.jar

# ---- 4. runtime: JRE + ffmpeg (video processing) + node (mailer), running as a non-root user
FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home --uid 10001 faststudio \
 && mkdir -p /data && chown faststudio /data
COPY --from=web /usr/local/bin/node /usr/local/bin/node
WORKDIR /app
COPY --from=jar /app.jar app.jar
COPY --from=mailer /mailer mailer
USER faststudio
ENV SPRING_PROFILES_ACTIVE=pilot \
    FASTSTUDIO_DATA_DIR=/data \
    FASTSTUDIO_FFMPEG=/usr/bin/ffmpeg \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
