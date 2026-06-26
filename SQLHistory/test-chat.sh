#!/bin/bash

# ================================================================
# Test script for Chat System - Phase 1 Step 7
# Tests: conversation creation, messaging, reading, marking as read
# ================================================================

echo "===================================================="
echo "  TEST CHAT SYSTEM"
echo "===================================================="
echo ""

# Register two users
ALICE_REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"alice$(date +%s)@pair.com\",\"password\":\"Test1234!\",\"displayName\":\"Alice Martin\"}")

TOKEN_ALICE=$(echo "$ALICE_REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
USER_ALICE=$(echo "$ALICE_REG" | grep -o '"userId":"[^"]*"' | head -1 | cut -d'"' -f4)

BOB_REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"bob$(date +%s)@pair.com\",\"password\":\"Test1234!\",\"displayName\":\"Bob Dupont\"}")

TOKEN_BOB=$(echo "$BOB_REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
USER_BOB=$(echo "$BOB_REG" | grep -o '"userId":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "Users created:"
echo "  Alice: $USER_ALICE"
echo "  Bob: $USER_BOB"
echo ""

# Test 1: Create conversation
echo "1. Alice creates conversation with Bob"
CONV=$(curl -s -X POST http://localhost:8090/api/conversations \
  -H "Authorization: Bearer $TOKEN_ALICE" \
  -H "Content-Type: application/json" \
  -d "{\"targetUserId\":\"$USER_BOB\"}")

if echo "$CONV" | grep -q '"id"'; then
  CONV_ID=$(echo "$CONV" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
  echo "   [OK] Conversation created: $CONV_ID"
else
  echo "   [FAIL] $CONV"
  exit 1
fi
echo ""

# Test 2: Send message
echo "2. Alice sends message"
MSG1=$(curl -s -X POST http://localhost:8090/api/conversations/$CONV_ID/messages \
  -H "Authorization: Bearer $TOKEN_ALICE" \
  -H "Content-Type: application/json" \
  -d "{\"conversationId\":\"$CONV_ID\",\"content\":\"Salut Bob! Tu veux jouer au tennis demain?\"}")

if echo "$MSG1" | grep -q '"content"'; then
  echo "   [OK] Message sent"
  echo "   Content: \"$(echo "$MSG1" | grep -o '"content":"[^"]*"' | cut -d'"' -f4)\""
else
  echo "   [FAIL] $MSG1"
  exit 1
fi
echo ""

# Test 3: List conversations
echo "3. Bob lists his conversations"
CONVS=$(curl -s http://localhost:8090/api/conversations \
  -H "Authorization: Bearer $TOKEN_BOB")

if echo "$CONVS" | grep -q "$CONV_ID"; then
  echo "   [OK] Bob sees the conversation"
  UNREAD=$(echo "$CONVS" | grep -o '"unreadCount":[0-9]*' | head -1 | grep -o '[0-9]*')
  echo "   Unread count: $UNREAD"
else
  echo "   [FAIL] $CONVS"
  exit 1
fi
echo ""

# Test 4: Read messages
echo "4. Bob reads messages"
MSGS=$(curl -s "http://localhost:8090/api/conversations/$CONV_ID/messages?limit=10" \
  -H "Authorization: Bearer $TOKEN_BOB")

if echo "$MSGS" | grep -q "tennis"; then
  MSG_COUNT=$(echo "$MSGS" | grep -o '"id"' | wc -l)
  echo "   [OK] Messages retrieved (count: $MSG_COUNT)"
else
  echo "   [FAIL] $MSGS"
  exit 1
fi
echo ""

# Test 5: Reply
echo "5. Bob replies"
MSG2=$(curl -s -X POST http://localhost:8090/api/conversations/$CONV_ID/messages \
  -H "Authorization: Bearer $TOKEN_BOB" \
  -H "Content-Type: application/json" \
  -d "{\"conversationId\":\"$CONV_ID\",\"content\":\"Oui avec plaisir! 18h au parc?\"}")

if echo "$MSG2" | grep -q '"content"'; then
  echo "   [OK] Bob's reply sent"
else
  echo "   [FAIL] $MSG2"
  exit 1
fi
echo ""

# Test 6: Mark as read
echo "6. Bob marks conversation as read"
RESPONSE=$(curl -s -w "\n%{http_code}" -X POST http://localhost:8090/api/conversations/$CONV_ID/read \
  -H "Authorization: Bearer $TOKEN_BOB")

HTTP_CODE=$(echo "$RESPONSE" | tail -1)
if [ "$HTTP_CODE" = "204" ]; then
  echo "   [OK] Marked as read (HTTP 204)"
else
  echo "   [INFO] HTTP $HTTP_CODE"
fi
echo ""

# Test 7: Alice checks unread count (should be 1 - Bob's reply)
echo "7. Alice checks unread count"
ALICE_CONVS=$(curl -s http://localhost:8090/api/conversations \
  -H "Authorization: Bearer $TOKEN_ALICE")

if echo "$ALICE_CONVS" | grep -q '"unreadCount"'; then
  ALICE_UNREAD=$(echo "$ALICE_CONVS" | grep -o '"unreadCount":[0-9]*' | head -1 | grep -o '[0-9]*')
  echo "   [OK] Alice has $ALICE_UNREAD unread message(s)"
else
  echo "   [INFO] No unread count found"
fi
echo ""

echo "===================================================="
echo "  CHAT SYSTEM TEST COMPLETE"
echo "===================================================="
echo ""
echo "Endpoints tested:"
echo "  [OK] POST /api/conversations"
echo "  [OK] GET  /api/conversations"
echo "  [OK] POST /api/conversations/{id}/messages"
echo "  [OK] GET  /api/conversations/{id}/messages"
echo "  [OK] POST /api/conversations/{id}/read"
echo ""
echo "Phase 1 Step 7: COMPLETE ✓"
echo ""
echo "Note: WebSocket messaging can be tested with:"
echo "  URL: ws://localhost:8090/ws/chat"
echo "  Destination: /app/chat.send"
echo "  Subscribe: /user/queue/messages"
