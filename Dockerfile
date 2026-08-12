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
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Stockage des médias — chemin ABSOLU, aligné sur le point de montage du volume
# Railway, qui est /app/uploads.
#
# Le chemin est écrit en toutes lettres alors que le défaut relatif de
# application.properties ("uploads", résolu depuis WORKDIR) désignerait le même
# répertoire. C'est délibéré : un chemin relatif dépend du répertoire de travail,
# donc du jour où quelqu'un changera le WORKDIR ou lancera le jar autrement, et
# il rendrait la coïncidence avec le point de montage invisible. Écrit ici, le
# lien entre les deux est vérifiable.
#
# Ce mkdir ne rend rien persistant à lui seul : le volume monté par Railway
# recouvre ce répertoire au démarrage. Il garantit seulement que le chemin existe
# si le volume venait à manquer. La ligne "Storage contains no persistence
# marker" au démarrage, répétée à chaque redeploy, signalerait précisément ce
# cas — c'est-à-dire l'incident du 2026-08-11, où plus aucun média n'était
# lisible en production.
ENV STORAGE_PATH=/app/uploads
RUN mkdir -p /app/uploads

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]