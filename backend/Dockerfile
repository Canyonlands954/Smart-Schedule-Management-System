FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S schedule && adduser -S schedule -G schedule \
    && mkdir -p /app/secrets && chown schedule:schedule /app/secrets && chmod 700 /app/secrets
COPY --from=build /build/target/schedule-api-2.0.0.jar /app/api.jar
USER schedule
ENV API_HOST=0.0.0.0
ENV AI_CONFIG_KEY_FILE=/app/secrets/model-config.key
EXPOSE 18865
ENTRYPOINT ["java","-jar","/app/api.jar"]
