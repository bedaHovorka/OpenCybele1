FROM eclipse-temurin:21-jdk-noble AS builder

RUN apt-get update \
    && apt-get install -y --no-install-recommends maven \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
COPY cybelle ./cybelle

RUN test -f cybelle/Cybele.jar && test -f cybelle/CybeleImpl.jar \
    || (echo "cybelle/Cybele.jar and/or cybelle/CybeleImpl.jar are missing from the build context." \
        "Run 'git checkout withoutGradle -- cybelle/Cybele.jar cybelle/CybeleImpl.jar' before building (see README.md)." >&2; exit 1)

RUN mvn install:install-file -Dfile=cybelle/Cybele.jar     -DgroupId=com.iai -DartifactId=cybele-api  -Dversion=1.0 -Dpackaging=jar -q \
    && mvn install:install-file -Dfile=cybelle/CybeleImpl.jar -DgroupId=com.iai -DartifactId=cybele-impl -Dversion=1.0 -Dpackaging=jar -q \
    && ./gradlew installDist --no-daemon

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
COPY --chown=app:app cybelle/cybele.prop cybelle/ICS.prop ./cybelle/

ENV DISPLAY=:0
USER app

CMD ["bin/opencybele"]
