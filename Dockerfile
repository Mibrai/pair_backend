# Build stage
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B
COPY src ./src
# Cache bust: 2026-07-04-v2
RUN ./mvnw clean package -DskipTests -B

# Run stage
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache libstdc++ libgcc
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
RUN mkdir -p /app/uploads && chown -R spring:spring /app/uploads
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]