FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy Jingra JAR (build context is jingra root: ../..)
COPY target/jingra-*-jar-with-dependencies.jar /app/jingra.jar

# Config files and datasets are mounted as volumes in docker-compose.yml
# No need to COPY them here

# Set entrypoint to java -jar jingra.jar
ENTRYPOINT ["java", "-jar", "/app/jingra.jar"]

# Default command shows help
CMD ["--help"]
