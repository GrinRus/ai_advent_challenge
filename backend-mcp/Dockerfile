# syntax=docker/dockerfile:1.7

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential clang pkg-config \
    && rm -rf /var/lib/apt/lists/*

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY treesitter treesitter

RUN ./gradlew --no-daemon clean

COPY src src

RUN ./gradlew --no-daemon treeSitterBuild bootJar

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl git ca-certificates docker.io \
    && curl -fsSL https://deb.nodesource.com/setup_18.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g @anthropic-ai/claude-code \
    && npm config set update-notifier false \
    && rm -rf /var/lib/apt/lists/*

ENV SPRING_MAIN_BANNER_MODE=off \
    JAVA_OPTS="" \
    AGENT_OPS_BACKEND_BASE_URL=http://backend:8080 \
    FLOW_OPS_BACKEND_BASE_URL=http://backend:8080 \
    INSIGHT_BACKEND_BASE_URL=http://backend:8080

COPY --from=build /workspace/build/libs/backend-mcp-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build /workspace/build/treesitter /app/treesitter

EXPOSE 8080
ENV GITHUB_RAG_AST_LIBRARY_PATH=/app/treesitter

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
