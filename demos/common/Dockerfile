# Builds from source inside the container rather than copying a host-built binary: unlike a
# Java .jar, a natively-compiled Rust binary is tied to the builder's OS/architecture, so
# "compile on the host, COPY into a Linux image" breaks for anyone not already building on
# Linux/amd64 or Linux/arm64 (e.g. developing on macOS).
FROM rust:1.97.1-slim-bookworm AS builder

WORKDIR /build

COPY Cargo.toml Cargo.lock rust-toolchain.toml ./
RUN mkdir -p src && \
    echo "fn main() {}" > src/main.rs && \
    echo "" > src/lib.rs && \
    cargo build --release && \
    rm -rf src

COPY src ./src
COPY tests ./tests
COPY engine-versions ./engine-versions
RUN touch src/main.rs src/lib.rs && cargo build --release

FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/release/jingra /app/jingra

ENTRYPOINT ["/app/jingra"]
CMD ["--help"]
