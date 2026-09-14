FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/low-altitude-server-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
