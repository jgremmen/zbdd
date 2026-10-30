# Images running the Windows tests under Wine, built by Gradle for linux/amd64. Target "wine" runs the C tests, target
# "java" adds a Windows JDK for the Java tests and has its archive verified before it is extracted.
ARG DEBIAN_RELEASE=bookworm-slim

FROM debian:${DEBIAN_RELEASE} AS wine

ENV WINEDEBUG=-all
ENV WINEPREFIX=/opt/wine-prefix

RUN set -e; \
    apt-get update; \
    apt-get install -y --no-install-recommends wine wine64; \
    rm -rf /var/lib/apt/lists/*; \
    wineboot -i; \
    wineserver -w

WORKDIR /work


FROM wine AS java

ARG WINDOWS_JDK_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip
ARG WINDOWS_JDK_SHA256=a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9

RUN set -e; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates curl unzip; \
    rm -rf /var/lib/apt/lists/*; \
    curl -fsSL -o /tmp/windows-jdk.zip "${WINDOWS_JDK_URL}"; \
    echo "${WINDOWS_JDK_SHA256}  /tmp/windows-jdk.zip" | sha256sum -c -; \
    unzip -q /tmp/windows-jdk.zip -d /opt; \
    mv /opt/jdk-* /opt/windows-jdk; \
    rm /tmp/windows-jdk.zip
