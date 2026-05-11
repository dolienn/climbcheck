# ---- Build stage: compilation and packaging (tests run in CI) ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Dependencies first — the layer is cached until pom.xml changes
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q -B dependency:go-offline || true

COPY src src
RUN ./mvnw -q -B package -DskipTests

# ---- Run stage: JRE only, no Maven or sources ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app

EXPOSE 8080

# Monitoring: /actuator/health (Spring Boot Actuator)
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -q -O /dev/null http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
