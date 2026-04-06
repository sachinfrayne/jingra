# Multi-stage build for jingra
# Java version is passed as build arg (read from .java-version via Makefile)
ARG JAVA_VERSION=21

FROM maven:3.9-eclipse-temurin-${JAVA_VERSION} AS builder

WORKDIR /build

# Copy pom.xml and download dependencies (cached layer)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source and build JAR (tests run via Makefile before Docker build)
COPY src ./src
RUN mvn package -DskipTests

# Runtime image (same Java version as builder)
FROM eclipse-temurin:${JAVA_VERSION}-jre-jammy

WORKDIR /app

# Copy JAR from builder
COPY --from=builder /build/target/jingra-*-jar-with-dependencies.jar /app/jingra.jar

# Set up entrypoint with JAVA_OPTS support
ENTRYPOINT ["/bin/sh", "-c", "exec java $JAVA_OPTS -jar /app/jingra.jar \"$@\"", "--"]
# Override with e.g. load /config/jingra.yaml — first arg is subcommand, second is config path
CMD ["load", "/config/jingra.yaml"]
