#!/usr/bin/env bash
#
# Mesure le coût nu d'un aller-retour vers la base, depuis le conteneur
# applicatif. À exécuter DANS le conteneur, pas sur un poste de travail :
#
#     railway ssh -p "$RW_PROJECT" -e "$RW_ENV" -s pair_backend_service \
#       bash -s -- avant < scripts/mesure-rtt-db.sh
#
# Après la migration, rejouer à l'identique avec l'étiquette « apres ». Le
# couple des deux sorties est ce que le client attend (RELANCE_BACKEND_
# PLANCHER_2026-08-24.md §1, REPONSE_APP_FENETRE_MIGRATION_2026-08-24.md §4).
#
# Pourquoi un connect() TCP plutôt qu'un vrai « SELECT 1 » : l'image applicative
# est un eclipse-temurin:21-jre-jammy, sans client Postgres. Or l'établissement
# d'une connexion TCP coûte exactement un aller-retour (SYN → SYN/ACK), qui est
# la grandeur cherchée. Si curl est présent, le script double la mesure par un
# vrai SELECT 1 : l'indicateur de santé DataSource exécute une requête de
# validation sur une connexion déjà ouverte du pool Hikari.

set -uo pipefail

ETIQUETTE="${1:-sans-etiquette}"
N=20
WARMUP=3

echo "=============================================================="
echo " Aller-retour applicatif → base — relevé « ${ETIQUETTE} »"
echo " $(date -u +%Y-%m-%dT%H:%M:%SZ)  hôte $(hostname)"
echo "=============================================================="

if [ -z "${PGHOST:-}" ] || [ -z "${PGPORT:-}" ]; then
  echo "ERREUR : PGHOST/PGPORT absents de l'environnement."
  echo "         Ce script doit tourner DANS le conteneur applicatif."
  exit 1
fi
echo "Cible : ${PGHOST}:${PGPORT}"

# Garde-fou : `date +%s%N` n'existe que sur GNU coreutils. Sur macOS/BSD il rend
# la chaîne littérale « ...N » et tout le calcul devient du bruit silencieux.
# Ce script est fait pour tourner DANS le conteneur (Ubuntu jammy), jamais sur
# un poste de travail.
if ! date +%s%N | grep -Eq '^[0-9]+$'; then
  echo "ERREUR : « date +%s%N » ne rend pas de nanosecondes sur ce système."
  echo "         Vous êtes probablement sur macOS. Ce script s'exécute dans le"
  echo "         conteneur applicatif :"
  echo "         railway ssh -p ... -s pair_backend_service bash -s -- avant \\"
  echo "           < scripts/mesure-rtt-db.sh"
  exit 1
fi

# Résoudre une fois pour toutes : sans cela, chaque /dev/tcp refait un
# getaddrinfo et l'on mesurerait le DNS autant que le réseau.
CIBLE="$PGHOST"
if command -v getent >/dev/null 2>&1; then
  ADRESSE=$(getent hosts "$PGHOST" 2>/dev/null | awk '{print $1; exit}')
  if [ -n "${ADRESSE:-}" ]; then
    CIBLE="$ADRESSE"
    echo "Résolu : $ADRESSE  (mesures faites sur l'adresse, DNS exclu)"
  else
    echo "Résolution impossible — mesures faites sur le nom, DNS inclus."
  fi
fi
echo

connect_ms() {
  local debut fin
  debut=$(date +%s%N)
  { exec 3<>"/dev/tcp/${CIBLE}/${PGPORT}"; } 2>/dev/null || return 1
  fin=$(date +%s%N)
  exec 3<&- 3>&-
  echo $(( (fin - debut) / 1000000 ))
}

# Les premiers connects portent le coût du cache ARP/route ; on les jette.
for _ in $(seq 1 "$WARMUP"); do connect_ms >/dev/null 2>&1; done

MESURES=()
ECHECS=0
for _ in $(seq 1 "$N"); do
  if valeur=$(connect_ms); then
    MESURES+=("$valeur")
  else
    ECHECS=$((ECHECS + 1))
  fi
done

if [ "${#MESURES[@]}" -eq 0 ]; then
  echo "ERREUR : aucune connexion n'a abouti (${ECHECS} échecs)."
  echo "         La base est-elle joignable depuis ce conteneur ?"
  exit 1
fi

printf '%s\n' "${MESURES[@]}" | sort -n | awk -v ech="$ECHECS" -v etq="$ETIQUETTE" '
  { a[NR] = $1; somme += $1 }
  END {
    med = (NR % 2) ? a[int(NR/2) + 1] : (a[NR/2] + a[NR/2 + 1]) / 2
    rang = NR * 0.9; p90i = int(rang); if (p90i < rang) p90i++
    if (p90i < 1) p90i = 1; if (p90i > NR) p90i = NR
    p90 = a[p90i]
    printf "  n         : %d réussis, %d échoués\n", NR, ech
    printf "  min       : %d ms\n", a[1]
    printf "  MEDIANE   : %.1f ms   <-- la valeur à retenir\n", med
    printf "  p90       : %d ms\n", p90
    printf "  max       : %d ms\n", a[NR]
    printf "  moyenne   : %.1f ms\n", somme / NR
    printf "\nRTT_TCP\t%s\t%.1f\n", etq, med
  }'

echo
echo "--- Lecture ---"
echo "  150 à 200 ms : le service et la base ne sont pas dans la même région."
echo "  1 à 5 ms     : même région. C'est l'état attendu APRÈS migration."
echo "  20 à 100 ms  : ni l'un ni l'autre — ne pas conclure, venir en parler."
echo

# Doublure applicative : un vrai SELECT 1, sur une connexion du pool.
if command -v curl >/dev/null 2>&1; then
  echo "--- SELECT 1 applicatif (/actuator/health/db, 10 appels en loopback) ---"
  # %{http_code} d'abord : en cas d'échec de connexion curl rend « 000 » ET un
  # temps plausible. Sans ce filtre on publierait le temps d'un échec.
  for _ in $(seq 1 10); do
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' \
      "http://127.0.0.1:${PORT:-8080}/actuator/health/db" 2>/dev/null
  done | awk '$1 != "000" && $1 != "" { print $2 }' | sort -n | awk -v etq="$ETIQUETTE" '
    { a[NR] = $1 * 1000 }
    END {
      if (NR == 0) { print "  (aucune réponse exploitable — endpoint injoignable)"; exit }
      med = (NR % 2) ? a[int(NR/2) + 1] : (a[NR/2] + a[NR/2 + 1]) / 2
      printf "  n=%d  min=%.1f ms  MEDIANE=%.1f ms  max=%.1f ms\n", NR, a[1], med, a[NR]
      printf "  (inclut le HTTP en loopback, ~1 à 2 ms, et le pool Hikari)\n"
      printf "\nSELECT1_HTTP\t%s\t%.1f\n", etq, med
    }'
else
  echo "--- SELECT 1 applicatif : curl absent du conteneur, mesure TCP seule ---"
fi

echo
echo "=== Fin du relevé « ${ETIQUETTE} » — conserver cette sortie ==="
