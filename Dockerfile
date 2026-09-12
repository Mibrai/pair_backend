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

# Port de gestion privé (fiche d'audit P-BA-21, décision D9 option B).
#
# Déclaré pour la documentation, pas pour l'exposer : Railway ne publie qu'un
# port sur Internet, celui de PORT. C'est par le réseau privé du projet que
# l'agent de collecte lit pair-backend.railway.internal:9090/actuator/prometheus.
# Voir management.server.port dans application-railway.properties.
EXPOSE 9090

# Les trois options ajoutées le 12/09, et ce que chacune répare.
#
# -XX:MaxRAMPercentage=75 — la JVM sans option prend 25 % de la mémoire visible
#   comme tas maximum. Sur un conteneur de 512 Mo cela fait 128 Mo de tas alors
#   que 512 sont facturés : le modèle d'embeddings et le pool de 20 connexions
#   se disputent le quart d'une enveloppe déjà payée. 75 % laisse la place au
#   hors-tas (métaspace, piles, tampons directs d'ONNX Runtime), qui n'est pas
#   négligeable ici.
#
# -XX:+ExitOnOutOfMemoryError — sans cette option, un OutOfMemoryError tue le
#   fil qui l'a levé et laisse le processus debout : le conteneur reste « sain »
#   pour la plateforme, le contrôle de santé répond, et l'application ne fait
#   plus rien d'utile. Avec elle, le processus meurt, et
#   restartPolicyType=ON_FAILURE de railway.json le relance. Un redémarrage
#   visible vaut mieux qu'une instance zombie.
#
# -Duser.timezone=UTC (fiche P-BA-19) — l'image jammy n'a pas de fuseau
#   configuré et la JVM retombe sur celui de l'hôte, qui n'est pas garanti. Or
#   les jobs @Scheduled de ce projet sont écrits en cron (« 0 0 3 * * * » pour la
#   purge RGPD) : leur heure de déclenchement dépend du fuseau par défaut. Posé
#   ici, l'heure des jobs ne change pas quand la plateforme change d'hôte.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]