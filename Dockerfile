# Multi-stage build for jingra.
FROM rust:1.97.1-slim-bookworm AS builder

WORKDIR /build

# Cache dependency compilation in its own layer: build with stub sources first, then swap in the
# real ones. Avoids re-downloading/re-compiling every dependency on every source change.
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

# Runtime image: no Rust toolchain, just the binary and TLS roots.
FROM debian:bookworm-slim

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/target/release/jingra /app/jingra

ENTRYPOINT ["/app/jingra"]
# Override with e.g. `bench /config/jingra.yaml` -- first arg is the subcommand, second the config path.
CMD ["load", "/config/jingra.yaml"]
