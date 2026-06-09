# syntax=docker/dockerfile:1

# ---- build stage ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -q -e dependency:go-offline || true
COPY src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/frontier-service-1.0.0.jar ./app.jar
EXPOSE 3000
# ALLOW_ENCODED_SLASH lets Tomcat accept %2F in path variables (e.g. /seen/:url with full URL value).
CMD ["java", "-Dorg.apache.tomcat.util.buf.UDecoder.ALLOW_ENCODED_SLASH=true", "-jar", "app.jar"]
