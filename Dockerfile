# Build
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B package -DskipTests

# Run
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/target/porto-bus-api.jar app.jar
# The static store is a derived artifact, but re-downloading it on every
# restart is wasteful: mount a volume here.
ENV DB_PATH=/data/gtfs.db
VOLUME /data
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
