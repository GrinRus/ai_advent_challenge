# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon clean

COPY src src
RUN ./gradlew --no-daemon bootJar

# --- Runtime image ---
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Install Node.js + npm and make sure MCP server is available without network on startup
RUN apt-get update && apt-get install -y curl ca-certificates \
 && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
 && apt-get install -y nodejs \
 && node -v && npm -v

ENV PATH="/usr/local/bin:${PATH}" \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS=""

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]