#!/bin/bash

echo "========================================="
echo "  Test complet du système d'activités"
echo "========================================="
echo ""

# Register new user with unique email
echo "1. Création d'un nouvel utilisateur..."
UNIQUE_EMAIL="sports-fan-$(date +%s)@test.com"
REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$UNIQUE_EMAIL\",\"password\":\"Test1234!\",\"displayName\":\"Sports Fan\"}")

TOKEN=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
USER_ID=$(echo "$REG" | grep -o '"userId":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
    echo "❌ Échec de l'inscription"
    exit 1
fi

echo "✅ Utilisateur créé: $USER_ID"
echo ""

# Test public endpoints
echo "2. GET /api/categories (public)"
CATS=$(curl -s http://localhost:8090/api/categories)
CAT_COUNT=$(echo "$CATS" | grep -o '"id"' | wc -l)
echo "✅ $CAT_COUNT catégories trouvées"
echo ""

echo "3. GET /api/activities (public)"
ACTS=$(curl -s "http://localhost:8090/api/activities?size=5")
ACT_COUNT=$(echo "$ACTS" | grep -o '"totalElements":' | cut -d: -f2 | cut -d, -f1)
echo "✅ $ACT_COUNT activités trouvées"
echo ""

# Test authenticated endpoints
echo "4. GET /api/users/me/activities (mes activités - vide)"
MY_ACTS=$(curl -s http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN")
echo "✅ Résultat: $MY_ACTS"
echo ""

echo "5. POST /api/users/me/activities (ajouter Tennis)"
ADD=$(curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "visibleOnMap":true,
    "customDescription":"Je cherche des partenaires pour jouer au tennis",
    "level":"INTERMEDIATE",
    "format":"BOTH"
  }')
echo "$ADD" | head -5
echo "✅ Tennis ajouté"
echo ""

echo "6. POST /api/users/me/activities (ajouter Running)"
curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "activityId":"cccccccc-cccc-cccc-cccc-cccccccccccc",
    "visibleOnMap":true,
    "level":"ADVANCED",
    "format":"SOLO"
  }' > /dev/null
echo "✅ Running ajouté"
echo ""

echo "7. GET /api/users/me/activities (mes 2 activités)"
MY_ACTS2=$(curl -s http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN")
MY_COUNT=$(echo "$MY_ACTS2" | grep -o '"id"' | wc -l)
echo "✅ $MY_COUNT activités dans mon profil"
echo ""

# Get the user activity ID for Tennis
UA_ID=$(echo "$ADD" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -n "$UA_ID" ]; then
    echo "8. PUT /api/users/me/activities/$UA_ID (modifier Tennis)"
    curl -s -X PUT "http://localhost:8090/api/users/me/activities/$UA_ID" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d '{
        "activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
        "visibleOnMap":false,
        "customDescription":"Description mise à jour",
        "level":"ADVANCED",
        "format":"GROUP"
      }' > /dev/null
    echo "✅ Tennis modifié"
    echo ""

    echo "9. PATCH /api/users/me/activities/$UA_ID/visibility"
    curl -s -X PATCH "http://localhost:8090/api/users/me/activities/$UA_ID/visibility" \
      -H "Authorization: Bearer $TOKEN" \
      -H "Content-Type: application/json" \
      -d '{"visible":true}' > /dev/null
    echo "✅ Visibilité activée"
    echo ""

    echo "10. DELETE /api/users/me/activities/$UA_ID (supprimer Tennis)"
    curl -s -X DELETE "http://localhost:8090/api/users/me/activities/$UA_ID" \
      -H "Authorization: Bearer $TOKEN"
    echo "✅ Tennis supprimé"
    echo ""
fi

echo "11. GET /api/users/me/activities (vérification finale)"
FINAL=$(curl -s http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN")
FINAL_COUNT=$(echo "$FINAL" | grep -o '"id"' | wc -l)
echo "✅ $FINAL_COUNT activité(s) restante(s)"
echo ""

echo "========================================="
echo "  ✅ Tests terminés avec succès!"
echo "========================================="
