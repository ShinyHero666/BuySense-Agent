FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY java-control-plane/pom.xml ./pom.xml
RUN mvn -q -DskipTests dependency:go-offline
COPY java-control-plane/src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --create-home --uid 10001 buysense
COPY --from=build /workspace/target/buysense-java-0.1.0-SNAPSHOT.jar ./app.jar
USER buysense
EXPOSE 19090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
