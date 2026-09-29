FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/AttendanceTracker-0.0.1-SNAPSHOT.jar app.jar
ENV SPRING_PROFILES_ACTIVE=render
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65.0", "-jar", "/app/app.jar"]
