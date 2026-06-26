#!/bin/bash

# Test du endpoint conversations avec authentification
# Usage: bash test-conversations.sh

BASE_URL="http://localhost:8090/api"

echo "=========================================="
echo "Test Chat Conversations API"
echo "=========================================="
echo ""

# Fonction pour afficher les résultats
print_result() {
    if [ $? -eq 0 ]; then
        echo "✅ $1"
    else
        echo "❌ $1"
    fi
}

# 1. Créer un utilisateur de test
echo "1️⃣  Création utilisateur de test..."
REGISTER_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "chat_test@example.com",
    "password": "password123",
    "displayName": "Chat Test User"
  }')

echo "$REGISTER_RESPONSE" | jq '.' 2>/dev/null || echo "$REGISTER_RESPONSE"
print_result "Inscription"

# Extraire le token
ACCESS_TOKEN=$(echo "$REGISTER_RESPONSE" | jq -r '.accessToken' 2>/dev/null)

if [ "$ACCESS_TOKEN" = "null" ] || [ -z "$ACCESS_TOKEN" ]; then
    echo "⚠️  Tentative de connexion avec compte existant..."

    LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
      -H "Content-Type: application/json" \
      -d '{
        "email": "chat_test@example.com",
        "password": "password123"
      }')

    ACCESS_TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.accessToken' 2>/dev/null)
    USER_ID=$(echo "$LOGIN_RESPONSE" | jq -r '.userId' 2>/dev/null)

    echo "$LOGIN_RESPONSE" | jq '.' 2>/dev/null
    print_result "Connexion"
else
    USER_ID=$(echo "$REGISTER_RESPONSE" | jq -r '.userId' 2>/dev/null)
fi

echo ""
echo "🔑 Access Token: ${ACCESS_TOKEN:0:30}..."
echo "👤 User ID: $USER_ID"
echo ""

# 2. Créer un second utilisateur (pour créer une conversation)
echo "2️⃣  Création second utilisateur..."
REGISTER2_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "chat_test2@example.com",
    "password": "password123",
    "displayName": "Chat Test User 2"
  }')

USER2_ID=$(echo "$REGISTER2_RESPONSE" | jq -r '.userId' 2>/dev/null)

if [ "$USER2_ID" = "null" ] || [ -z "$USER2_ID" ]; then
    # Essayer de se connecter
    LOGIN2_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
      -H "Content-Type: application/json" \
      -d '{
        "email": "chat_test2@example.com",
        "password": "password123"
      }')
    USER2_ID=$(echo "$LOGIN2_RESPONSE" | jq -r '.userId' 2>/dev/null)
fi

echo "👤 User 2 ID: $USER2_ID"
print_result "Second utilisateur créé"
echo ""

# 3. Lister les conversations (devrait être vide initialement)
echo "3️⃣  Liste des conversations (avant création)..."
CONVERSATIONS_RESPONSE=$(curl -s -X GET "$BASE_URL/conversations" \
  -H "Authorization: Bearer $ACCESS_TOKEN")

echo "$CONVERSATIONS_RESPONSE" | jq '.' 2>/dev/null || echo "$CONVERSATIONS_RESPONSE"
print_result "GET /conversations"
echo ""

# 4. Créer une conversation avec le second utilisateur
echo "4️⃣  Création d'une conversation..."
CREATE_CONV_RESPONSE=$(curl -s -X POST "$BASE_URL/conversations" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"targetUserId\": \"$USER2_ID\"
  }")

echo "$CREATE_CONV_RESPONSE" | jq '.' 2>/dev/null || echo "$CREATE_CONV_RESPONSE"
CONVERSATION_ID=$(echo "$CREATE_CONV_RESPONSE" | jq -r '.id' 2>/dev/null)
print_result "POST /conversations"
echo "💬 Conversation ID: $CONVERSATION_ID"
echo ""

# 5. Lister les conversations (devrait contenir la nouvelle)
echo "5️⃣  Liste des conversations (après création)..."
CONVERSATIONS_RESPONSE=$(curl -s -X GET "$BASE_URL/conversations" \
  -H "Authorization: Bearer $ACCESS_TOKEN")

echo "$CONVERSATIONS_RESPONSE" | jq '.' 2>/dev/null || echo "$CONVERSATIONS_RESPONSE"
print_result "GET /conversations"
echo ""

# 6. Envoyer un message dans la conversation
echo "6️⃣  Envoi d'un message..."
SEND_MESSAGE_RESPONSE=$(curl -s -X POST "$BASE_URL/conversations/$CONVERSATION_ID/messages" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "'"$CONVERSATION_ID"'",
    "content": "Hello! Ceci est un message de test."
  }')

echo "$SEND_MESSAGE_RESPONSE" | jq '.' 2>/dev/null || echo "$SEND_MESSAGE_RESPONSE"
print_result "POST /conversations/{id}/messages"
echo ""

# 7. Récupérer les messages de la conversation
echo "7️⃣  Récupération des messages..."
MESSAGES_RESPONSE=$(curl -s -X GET "$BASE_URL/conversations/$CONVERSATION_ID/messages?limit=50" \
  -H "Authorization: Bearer $ACCESS_TOKEN")

echo "$MESSAGES_RESPONSE" | jq '.' 2>/dev/null || echo "$MESSAGES_RESPONSE"
print_result "GET /conversations/{id}/messages"
echo ""

# 8. Marquer la conversation comme lue
echo "8️⃣  Marquer comme lu..."
curl -s -X POST "$BASE_URL/conversations/$CONVERSATION_ID/read" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -w "\nHTTP Status: %{http_code}\n"
print_result "POST /conversations/{id}/read"
echo ""

# 9. Test avec les paramètres de pagination originaux
echo "9️⃣  Test avec pagination (page=1&page_size=50)..."
PAGINATED_RESPONSE=$(curl -s -X GET "$BASE_URL/conversations?page=1&page_size=50" \
  -H "Authorization: Bearer $ACCESS_TOKEN")

echo "$PAGINATED_RESPONSE" | jq '.' 2>/dev/null || echo "$PAGINATED_RESPONSE"
print_result "GET /conversations?page=1&page_size=50"
echo ""

echo "=========================================="
echo "✅ Tests terminés!"
echo "=========================================="
echo ""
echo "📝 Notes:"
echo "  - L'endpoint est /api/conversations (pas /api/chat/conversations)"
echo "  - Un token JWT est requis (Authorization: Bearer <token>)"
echo "  - Les paramètres page/page_size sont ignorés (pas de pagination implémentée)"
echo "  - Pour tester depuis le navigateur, utilisez le token ci-dessus"
echo ""
echo "🔑 Token à utiliser: $ACCESS_TOKEN"
