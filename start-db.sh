#!/bin/bash

DOCKER="/usr/local/bin/docker"
CONTAINER="pair-postgres"

if $DOCKERps -q -f name="^${CONTAINER}$" | grep -q .; then
  echo "PostgreSQL déjà en cours d'exécution."
elif $DOCKERps -aq -f name="^${CONTAINER}$" | grep -q .; then
  echo "Démarrage du conteneur existant..."
  $DOCKERstart "$CONTAINER"
else
  echo "Création et démarrage du conteneur PostgreSQL..."
  $DOCKERrun -d --name "$CONTAINER" \
    -e POSTGRES_USER=pair_user \
    -e POSTGRES_PASSWORD=Pair2026! \
    -e POSTGRES_DB=pair_db \
    -p 5432:5432 \
    postgis/postgis:16-3.4

  echo "Attente que PostgreSQL soit prêt..."
  until $DOCKERexec "$CONTAINER" pg_isready -U pair_user -d pair_db -q 2>/dev/null; do
    sleep 1
  done

  echo "Installation de pgvector..."
  $DOCKERexec "$CONTAINER" bash -c "apt-get update -qq && apt-get install -y -qq postgresql-16-pgvector"
  $DOCKERrestart "$CONTAINER"
fi

echo "Attente que PostgreSQL soit prêt..."
until $DOCKERexec "$CONTAINER" pg_isready -U pair_user -d pair_db -q 2>/dev/null; do
  sleep 1
done

echo "PostgreSQL prêt sur localhost:5432"
