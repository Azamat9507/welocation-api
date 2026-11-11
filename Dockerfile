FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
# the wrapper and build files first, so dependencies stay cached while only the sources change
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN useradd --system --uid 10001 --no-create-home welocation
WORKDIR /app
COPY --from=build /src/build/libs/welocation-*.jar app.jar
USER welocation
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
