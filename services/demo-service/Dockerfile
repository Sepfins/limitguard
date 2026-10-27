# Packages the jar built by `./gradlew bootJar`; the build needs the host's generated jOOQ bindings.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/limitguard-demo-service-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
