# ---------------------------------------------------------------------------
# SettleX: Algorithmic Multi-Party Debt Optimization Engine
# Multi-stage build. Stage 1 compiles, stage 2 is a slim JRE-only runtime.
# ---------------------------------------------------------------------------

# ----------------------------- Stage 1: build ------------------------------
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Dependency layer: resolved separately so source edits do not re-download the world.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY src ./src
RUN mvn -B -DskipTests clean package

# ---------------------------- Stage 2: runtime -----------------------------
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="SettleX" \
      org.opencontainers.image.description="Algorithmic Multi-Party Debt Optimization Engine" \
      org.opencontainers.image.version="1.0.0" \
      org.opencontainers.image.licenses="MIT"

WORKDIR /app

# Run as an unprivileged user.
RUN addgroup -S settlex && adduser -S settlex -G settlex \
 && chown -R settlex:settlex /app

COPY --from=builder --chown=settlex:settlex /build/target/settlex.jar app.jar

USER settlex

EXPOSE 8085

# JAVA_TOOL_OPTIONS is read by the JVM itself, so the entrypoint can stay an exec-form
# JSON array (no shell, no signal swallowing: SIGTERM reaches the JVM directly).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+UseSerialGC -Djava.security.egd=file:/dev/./urandom"

# The health probe must follow the port the app actually binds to.
ENV PORT=8085
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- "http://127.0.0.1:${PORT}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]