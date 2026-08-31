#!/bin/bash

# Chemin explicite d'abord (Docker Desktop sur macOS), sinon celui du PATH : les
# deux existent selon les postes, et un chemin en dur qui manque donnait un
# « command not found » sans rapport visible avec la vraie cause.
DOCKER="/usr/local/bin/docker"
[ -x "$DOCKER" ] || DOCKER="$(command -v docker)"
CONTAINER="pair-postgres"

# ATTENTION : « $DOCKER ps » et non « $DOCKERps ». La seconde forme se lit comme
# la variable DOCKERps — indéfinie, donc vide — suivie de « -q » : le shell
# lançait alors « ps -q -f name=… », qui n'a rien à voir, et le script semblait
# ne jamais trouver le conteneur. C'était le défaut de cette version.
if $DOCKER ps -q -f name="^${CONTAINER}$" | grep -q .; then
  echo "PostgreSQL déjà en cours d'exécution."
elif $DOCKER ps -aq -f name="^${CONTAINER}$" | grep -q .; then
  echo "Démarrage du conteneur existant..."
  $DOCKER start "$CONTAINER"
else
  echo "Création et démarrage du conteneur PostgreSQL..."
  $DOCKER run -d --name "$CONTAINER" \
    -e POSTGRES_USER=pair_user \
    -e POSTGRES_PASSWORD=Pair2026! \
    -e POSTGRES_DB=pair_db \
    -p 5432:5432 \
    postgis/postgis:16-3.4

  echo "Attente que PostgreSQL soit prêt..."
  until $DOCKER exec "$CONTAINER" pg_isready -U pair_user -d pair_db -q 2>/dev/null; do
    sleep 1
  done

  echo "Installation de pgvector..."
  $DOCKER exec "$CONTAINER" bash -c "apt-get update -qq && apt-get install -y -qq postgresql-16-pgvector"
  $DOCKER restart "$CONTAINER"
fi

echo "Attente que PostgreSQL soit prêt..."
until $DOCKER exec "$CONTAINER" pg_isready -U pair_user -d pair_db -q 2>/dev/null; do
  sleep 1
done

echo "PostgreSQL prêt sur localhost:5432"
