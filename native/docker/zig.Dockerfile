# Compiler image for the native libraries. The zig build is selected by the architecture this image is built on, which
# is the one of the Docker daemon, and its archive is verified before it is extracted.
FROM alpine:3.20

ARG ZIG_VERSION=0.17.0
ARG ZIG_SHA256_X86_64=1cbe9df9f27e6b78d14ccbca43b6703a404ef79ef1c463de901d7f088d4e2026
ARG ZIG_SHA256_AARCH64=9e8d11661d4ae3bd57702a3832781e23ad151dde5798e16a5ccd503f65234ff8

RUN set -e; \
    arch="$(uname -m)"; \
    case "$arch" in \
      x86_64) sha256="$ZIG_SHA256_X86_64" ;; \
      aarch64) sha256="$ZIG_SHA256_AARCH64" ;; \
      *) echo "no zig ${ZIG_VERSION} checksum known for $arch" >&2; exit 1 ;; \
    esac; \
    wget -q -O /tmp/zig.tar.xz "https://ziglang.org/download/${ZIG_VERSION}/zig-${arch}-linux-${ZIG_VERSION}.tar.xz"; \
    echo "${sha256}  /tmp/zig.tar.xz" | sha256sum -c -; \
    mkdir /opt/zig; \
    tar xf /tmp/zig.tar.xz -C /opt/zig --strip-components=1; \
    rm /tmp/zig.tar.xz; \
    ln -s /opt/zig/zig /usr/local/bin/zig

ENV ZIG_GLOBAL_CACHE_DIR=/zig-cache
ENV ZIG_LOCAL_CACHE_DIR=/zig-cache/local

WORKDIR /work
