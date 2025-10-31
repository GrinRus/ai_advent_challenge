FROM eclipse-temurin:21-jdk

ARG GRADLE_VERSION=8.10.2

ENV GRADLE_HOME=/opt/gradle/gradle-${GRADLE_VERSION} \
    PATH=${GRADLE_HOME}/bin:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git bash coreutils \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/gradle \
    && curl -fsSL https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip -o /tmp/gradle.zip \
    && unzip -d /opt/gradle /tmp/gradle.zip \
    && rm -f /tmp/gradle.zip

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["gradle"]
