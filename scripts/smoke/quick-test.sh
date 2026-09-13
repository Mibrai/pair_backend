#!/bin/bash

# Test rapide authentification + conversations

echo "=== 1. Inscription ==="
RESULT=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "quicktest@example.com",
    "password": "password123",
    "displayName": "Quick Test"
  }')

echo "$RESULT" | python3 -m json.tool 2>/dev/null || echo "$RESULT"

TOKEN=$(echo "$RESULT" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo ""
echo "Token obtenu: ${TOKEN:0:50}..."
echo ""

echo "=== 2. Liste conversations (avec auth) ==="
curl -s -X GET "http://localhost:8090/api/conversations" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool 2>/dev/null

echo ""
echo "=== 3. Test pagination ==="
curl -s -X GET "http://localhost:8090/api/conversations?page=1&page_size=50" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool 2>/dev/null

echo ""
echo "✅ Tests terminés!"
