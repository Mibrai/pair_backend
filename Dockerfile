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

# Stockage des médias — chemin ABSOLU, hors de /app, et destiné à recevoir un
# volume persistant.
#
# Le défaut de application.properties est "uploads", un chemin relatif : dans un
# conteneur il se résout en /app/uploads, c'est-à-dire dans la couche d'écriture
# éphémère. Les téléversements réussissent, la base garde l'URL, et le redeploy
# suivant efface les octets — c'est l'incident du 2026-08-11, où plus aucun média
# n'était lisible en production.
#
# Ce mkdir ne rend rien persistant à lui seul : il garantit seulement que le
# chemin existe. La persistance vient du volume monté sur /data côté Railway
# (Service > Settings > Volumes, mount path = /data). Sans ce volume, la ligne
# d'avertissement au démarrage — "Storage contains no persistence marker" — se
# répétera à chaque redeploy.
ENV STORAGE_PATH=/data/uploads
RUN mkdir -p /data/uploads

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]