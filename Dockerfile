FROM eclipse-temurin:21-jdk-noble

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
COPY --chown=app:app src ./src
COPY --chown=app:app cybelle ./cybelle

RUN mkdir -p bin \
    && javac -d bin -cp "cybelle/Cybele.jar:cybelle/CybeleImpl.jar" $(find src/main -name "*.java")

ENV DISPLAY=:0
USER app

CMD ["java", "--patch-module", "java.base=cybelle", "-classpath", "bin:cybelle:cybelle/Cybele.jar:cybelle/CybeleImpl.jar", "cz.vutbr.fit.ags.xhovor07.Main"]
