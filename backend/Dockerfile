# VitalLink API image. The same image runs locally and on Render; only env vars differ.
# Build stage: compile and package with the Maven wrapper (dependencies cached in their own layer).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn ./.mvn
RUN chmod +x mvnw
RUN ./mvnw -q -B dependency:go-offline
COPY src ./src
RUN ./mvnw -q -B -DskipTests package

# Run stage: the JRE and the jar only.
FROM eclipse-temurin:25-jre
WORKDIR /srv
# Never run as root.
RUN useradd --create-home --uid 10001 api
COPY --from=build /src/target/vitallink-api.jar ./vitallink-api.jar
USER api

# Render sets PORT (default 10000) and gives the free tier 512 MB: keep the heap modest and the GC simple.
# Flyway applies migrations at startup, before the server listens. If they fail, the process exits and
# Render keeps serving the previous version (the free tier has no pre-deploy step).
ENV PORT=10000 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/srv/vitallink-api.jar"]
