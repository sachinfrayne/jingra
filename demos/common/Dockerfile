FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/jingra-*-jar-with-dependencies.jar /app/jingra.jar

ENTRYPOINT ["java", "-jar", "/app/jingra.jar"]

CMD ["--help"]
