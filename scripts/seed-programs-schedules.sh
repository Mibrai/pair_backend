#!/usr/bin/env bash
# Seed programs + schedules via REST API (Railway backend)
# Usage: ./scripts/seed-programs-schedules.sh [BASE_URL]
# Default BASE_URL: https://pairbackend-production-35fe.up.railway.app

set -euo pipefail

BASE_URL="${1:-https://pairbackend-production-35fe.up.railway.app}"
EMAIL="seyd.njoya@yahoo.fr"
PASSWORD="Cameroun1@"

# ── couleurs ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; exit 1; }

# ── helper: POST JSON ─────────────────────────────────────────────────────────
api_post() {
  local path="$1"; local body="$2"; local token="${3:-}"
  local auth_header=""
  [[ -n "$token" ]] && auth_header="-H \"Authorization: Bearer $token\""

  eval curl -s -X POST \
    -H "'Content-Type: application/json'" \
    ${auth_header} \
    -d "'$body'" \
    "'${BASE_URL}${path}'"
}

api_get() {
  local path="$1"; local token="$2"
  curl -s -X GET \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $token" \
    "${BASE_URL}${path}"
}

# ─────────────────────────────────────────────────────────────────────────────
# 1. LOGIN
# ─────────────────────────────────────────────────────────────────────────────
info "Connexion avec $EMAIL..."

LOGIN_RESP=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" \
  "${BASE_URL}/api/auth/login")

TOKEN=$(echo "$LOGIN_RESP" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

[[ -z "$TOKEN" ]] && {
  echo "Réponse login: $LOGIN_RESP"
  error "Authentification échouée. Vérifiez email/mot de passe."
}
success "Token JWT obtenu."

# ─────────────────────────────────────────────────────────────────────────────
# 2. RÉCUPÉRER LES USER_ACTIVITIES DE L'UTILISATEUR
# ─────────────────────────────────────────────────────────────────────────────
info "Récupération des activités de l'utilisateur..."

UA_RESP=$(api_get "/api/users/me/activities" "$TOKEN")

# Extraire les IDs (format: "id":"<uuid>") — liste des user_activity IDs
UA_IDS=($(echo "$UA_RESP" | grep -o '"id":"[^"]*"' | cut -d'"' -f4))

if [[ ${#UA_IDS[@]} -eq 0 ]]; then
  warn "Aucune user_activity trouvée pour cet utilisateur."
  warn "Les programmes nécessitent une user_activity. Réponse: $UA_RESP"
  UA_IDS=()
else
  success "Trouvé ${#UA_IDS[@]} user_activity(ies): ${UA_IDS[*]}"
fi

# ─────────────────────────────────────────────────────────────────────────────
# 3. CRÉER DES PROGRAMMES (si user_activities disponibles)
# ─────────────────────────────────────────────────────────────────────────────

# Définition des programmes à créer (title, description, isPublic, ua_index)
# ua_index = index dans UA_IDS (on tourne en round-robin si moins de 5 activités)

declare -a PROGRAM_TITLES=(
  "Programme Natation Débutants"
  "Circuit Running Matinal"
  "Yoga & Méditation Hebdomadaire"
  "Football Loisir Samedi"
  "Cyclisme Urbain"
)

declare -a PROGRAM_DESCS=(
  "Programme de 8 semaines pour apprendre les bases de la natation : crawl, dos crawlé, brasse. Ouvert à tous niveaux débutants."
  "Sorties running en groupe, 5 à 10 km selon le niveau. Départ tous les matins à 6h30 du parc."
  "Séances de yoga et méditation guidées, focus sur la flexibilité et la gestion du stress. Matériel fourni."
  "Matches de football amicaux chaque samedi matin. Format 7v7, terrains en herbe synthétique."
  "Balades et entraînements à vélo en ville : pistes cyclables, parcs et voies vertes. Tous niveaux."
)

PROGRAM_IDS=()

if [[ ${#UA_IDS[@]} -gt 0 ]]; then
  info "Création de ${#PROGRAM_TITLES[@]} programmes..."

  for i in "${!PROGRAM_TITLES[@]}"; do
    # Round-robin sur les user_activities disponibles
    UA_IDX=$(( i % ${#UA_IDS[@]} ))
    UA_ID="${UA_IDS[$UA_IDX]}"
    TITLE="${PROGRAM_TITLES[$i]}"
    DESC="${PROGRAM_DESCS[$i]}"

    BODY=$(cat <<EOF
{
  "userActivityId": "$UA_ID",
  "title": "$TITLE",
  "description": "$DESC",
  "isPublic": true
}
EOF
)

    RESP=$(curl -s -X POST \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d "$BODY" \
      "${BASE_URL}/api/programs")

    PID=$(echo "$RESP" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -n "$PID" ]]; then
      PROGRAM_IDS+=("$PID")
      success "Programme créé : '$TITLE' (id=$PID)"
    else
      warn "Échec création programme '$TITLE'. Réponse: $RESP"
    fi
  done
else
  warn "Pas de user_activities → aucun programme créé par ce script."
fi

# ─────────────────────────────────────────────────────────────────────────────
# 4. RÉCUPÉRER TOUS LES PROGRAMMES EXISTANTS (+ ceux qu'on vient de créer)
# ─────────────────────────────────────────────────────────────────────────────
info "Récupération de tous les programmes existants..."

ALL_PROGRAMS_RESP=$(api_get "/api/programs" "$TOKEN")

# Extraire tous les IDs de programmes (premier "id" de chaque objet programme)
ALL_PROGRAM_IDS=($(echo "$ALL_PROGRAMS_RESP" | grep -o '"id":"[^"]*"' | cut -d'"' -f4 | sort -u))

[[ ${#ALL_PROGRAM_IDS[@]} -eq 0 ]] && error "Aucun programme trouvé. Impossible d'ajouter des schedules."

success "Total programmes : ${#ALL_PROGRAM_IDS[@]}"

# ─────────────────────────────────────────────────────────────────────────────
# 5. AJOUTER DES SCHEDULES À TOUS LES PROGRAMMES
# ─────────────────────────────────────────────────────────────────────────────
info "Ajout des schedules (lat/lng) pour chaque programme..."

# Lieux en Allemagne avec coordonnées réelles
declare -a PLACE_NAMES=(
  "Olympiastadion Berlin"
  "Englischer Garten München"
  "Stadtpark Hamburg"
  "Rheinpark Köln"
  "Palmengarten Frankfurt"
  "Eilenriede Hannover"
  "Bürgerpark Bremen"
  "Westfalenpark Dortmund"
)

declare -a LATS=( 52.5147  48.1642  53.5924  50.9658  50.1236  52.3805  53.0924  51.4987 )
declare -a LNGS=(  13.2394  11.6050   10.0024   6.9808   8.6568   9.7785   8.8203   7.4889 )
declare -a ADDRESSES=(
  "Olympischer Platz 3, 14053 Berlin"
  "Englischer Garten 1, 80538 München"
  "Am Stadtpark 1, 22299 Hamburg"
  "Rheinparkweg 1, 51063 Köln"
  "Siesmayerstraße 61, 60323 Frankfurt am Main"
  "Eilenriede, 30161 Hannover"
  "Marcusallee 1, 28359 Bremen"
  "Westfalenpark 1, 44139 Dortmund"
)

# Dates de départ futures (ISO 8601 UTC) — une par semaine à partir de J+7
# Calculées statiquement pour éviter la dépendance à `date`
declare -a STARTS_AT=(
  "2026-07-14T07:00:00Z"
  "2026-07-14T08:00:00Z"
  "2026-07-15T06:30:00Z"
  "2026-07-16T09:00:00Z"
  "2026-07-17T07:00:00Z"
  "2026-07-18T08:30:00Z"
  "2026-07-19T07:00:00Z"
  "2026-07-20T09:00:00Z"
)

declare -a ENDS_AT=(
  "2026-07-14T09:00:00Z"
  "2026-07-14T10:00:00Z"
  "2026-07-15T08:00:00Z"
  "2026-07-16T11:00:00Z"
  "2026-07-17T09:00:00Z"
  "2026-07-18T10:30:00Z"
  "2026-07-19T09:00:00Z"
  "2026-07-20T11:00:00Z"
)

declare -a MAX_PARTICIPANTS=( 20 15 25 30 20 22 30 25 )

SCHEDULE_COUNT=0
SCHEDULE_FAIL=0
TOTAL_PLACES=${#PLACE_NAMES[@]}

for PID in "${ALL_PROGRAM_IDS[@]}"; do
  # 2 schedules par programme, tirés en round-robin dans la liste des lieux
  for offset in 0 1; do
    IDX=$(( (SCHEDULE_COUNT + offset) % TOTAL_PLACES ))

    BODY=$(cat <<EOF
{
  "placeName":        "${PLACE_NAMES[$IDX]}",
  "placeType":        "PUBLIC",
  "lat":              ${LATS[$IDX]},
  "lng":              ${LNGS[$IDX]},
  "addressPublic":    "${ADDRESSES[$IDX]}",
  "showExactAddress": true,
  "startsAt":         "${STARTS_AT[$IDX]}",
  "endsAt":           "${ENDS_AT[$IDX]}",
  "maxParticipants":  ${MAX_PARTICIPANTS[$IDX]}
}
EOF
)

    RESP=$(curl -s -X POST \
      -H "Content-Type: application/json" \
      -H "Authorization: Bearer $TOKEN" \
      -d "$BODY" \
      "${BASE_URL}/api/programs/${PID}/schedules")

    SID=$(echo "$RESP" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

    if [[ -n "$SID" ]]; then
      success "  Programme $PID → schedule '${PLACE_NAMES[$IDX]}' (id=$SID)"
      SCHEDULE_COUNT=$(( SCHEDULE_COUNT + 1 ))
    else
      warn "  Échec schedule pour programme $PID. Réponse: $RESP"
      SCHEDULE_FAIL=$(( SCHEDULE_FAIL + 1 ))
    fi
  done
done

# ─────────────────────────────────────────────────────────────────────────────
# RÉSUMÉ
# ─────────────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo -e "${GREEN}  RÉSUMÉ DU SEEDING${NC}"
echo -e "${GREEN}════════════════════════════════════════${NC}"
echo -e "  Programmes créés   : ${#PROGRAM_IDS[@]}"
echo -e "  Programmes total   : ${#ALL_PROGRAM_IDS[@]}"
echo -e "  Schedules ajoutés  : $SCHEDULE_COUNT"
[[ $SCHEDULE_FAIL -gt 0 ]] && echo -e "  ${RED}Schedules échoués  : $SCHEDULE_FAIL${NC}"
echo -e "${GREEN}════════════════════════════════════════${NC}"
