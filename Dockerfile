FROM eclipse-temurin:21-jdk-noble AS builder

WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
COPY scenarios ./scenarios

# JADE and everything else resolve from Maven Central — no vendor-jar bootstrap.
RUN ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libxext6 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        fontconfig \
    && rm -rf /var/lib/apt/lists/*

# Match the host UID/GID so the bind-mounted .Xauthority cookie (mode 0600)
# is readable without running the container as root. The base image ships a
# default "ubuntu" user/group at 1000:1000, which collides with the default
# RUNTIME_UID/RUNTIME_GID below, so drop it first.
RUN userdel -r ubuntu 2>/dev/null; groupdel ubuntu 2>/dev/null; true

ARG RUNTIME_UID=1000
ARG RUNTIME_GID=1000
RUN groupadd -g ${RUNTIME_GID} app \
    && useradd -m -u ${RUNTIME_UID} -g ${RUNTIME_GID} app

WORKDIR /app
COPY --chown=app:app --from=builder /app/build/install/opencybele/ ./
# Ready-made scenario files, so `-Dsim.config=scenarios/short.properties` works in
# the container exactly as it does on the host. See docs/scenario-config.md.
COPY --chown=app:app scenarios ./scenarios

ENV DISPLAY=:0
USER app

CMD ["bin/opencybele"]
