# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle

RUN ./gradlew --no-daemon clean

COPY src src

RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates docker.io \
    && rm -rf /var/lib/apt/lists/*

ENV SPRING_MAIN_BANNER_MODE=off \
    JAVA_OPTS="" \
    AGENT_OPS_BACKEND_BASE_URL=http://backend:8080 \
    FLOW_OPS_BACKEND_BASE_URL=http://backend:8080 \
    INSIGHT_BACKEND_BASE_URL=http://backend:8080

COPY --from=build /workspace/build/libs/backend-mcp-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
