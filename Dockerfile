# syntax=docker/dockerfile:1

# ---- Build stage: compile the fat jar with the Maven wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the wrapper first and warm the dependency cache so code-only changes
# don't force a full re-download on every build.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline

# Now the sources, then package (tests run against a live DB, so skip them here).
COPY src/ src/
RUN ./mvnw -B -q clean package -DskipTests

# ---- Runtime stage: slim JRE with only the jar ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render injects $PORT; application.properties binds server.port=${PORT:8080}.
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
