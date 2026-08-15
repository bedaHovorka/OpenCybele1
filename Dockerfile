# Debian wheezy is EOL, so its apt repos have moved to archive.debian.org.
# It's the last Debian release to ship a real Java 6 JDK (openjdk-6-jdk;
# dropped in jessie), matching the toolchain this 2007/08 project was built with.
FROM debian:7-slim

RUN printf 'deb http://archive.debian.org/debian wheezy main\ndeb http://archive.debian.org/debian-security wheezy/updates main\n' > /etc/apt/sources.list \
    && printf 'Acquire::Check-Valid-Until "false";\n' > /etc/apt/apt.conf.d/99no-check-valid-until \
    # debian:7-slim strips /usr/share/man, but openjdk-6's postinst needs
    # man1/ to exist to create its man-page symlinks.
    && mkdir -p /usr/share/man/man1 \
    && apt-get update \
    && apt-get install -y --no-install-recommends --allow-unauthenticated \
        openjdk-6-jdk \
        libxext6 \
        libxrender1 \
        libxtst6 \
        libxi6 \
        fontconfig \
    && rm -rf /var/lib/apt/lists/*

# Match the host UID/GID so the bind-mounted .Xauthority cookie (mode 0600)
# is readable without running the container as root.
ARG RUNTIME_UID=1000
ARG RUNTIME_GID=1000
RUN groupadd -g ${RUNTIME_GID} app \
    && useradd -m -u ${RUNTIME_UID} -g ${RUNTIME_GID} app

WORKDIR /app
COPY --chown=app:app src ./src
COPY --chown=app:app cybelle ./cybelle

RUN mkdir -p bin \
    && javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src/main -name "*.java")

ENV DISPLAY=:0
USER app

CMD ["java", "-classpath", "bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar", "cz.vutbr.fit.ags.xhovor07.Main"]
