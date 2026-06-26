#!/bin/bash

echo "╔══════════════════════════════════════════════════════╗"
echo "║  TEST SYSTÈME DE PROGRESSION - Phase 2 Module 2     ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

# Register test user
echo "📝 Registering test user..."
TIMESTAMP=$(date +%s)
USER_REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"progtest${TIMESTAMP}\",\"email\":\"progtest${TIMESTAMP}@test.com\",\"password\":\"Test1234!\",\"firstName\":\"Progress\",\"lastName\":\"Tester\",\"displayName\":\"Progress Tester\"}")

TOKEN=$(echo "$USER_REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "[FAIL] Registration failed: $USER_REG"
  exit 1
fi

echo "[OK] User registered"
echo ""

# Get a program ID to work with
echo "🔍 Finding a program..."
PROGRAMS=$(curl -s -X GET "http://localhost:8090/api/programs?page=0&size=1" \
  -H "Authorization: Bearer $TOKEN")

PROGRAM_ID=$(echo "$PROGRAMS" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)

if [ -z "$PROGRAM_ID" ]; then
  echo "[WARN] No programs found, test will be limited"
else
  echo "[OK] Using program: $PROGRAM_ID"
fi

echo ""
echo "════════════════════════════════════════════════════════"
echo "Test 1: Create Progression"
echo "════════════════════════════════════════════════════════"
PROG1=$(curl -s -X POST http://localhost:8090/api/progressions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"programId\": \"$PROGRAM_ID\",
    \"title\": \"First workout session\",
    \"content\": \"Completed 30 minutes of running. Felt great!\",
    \"metrics\": [5.2, 30],
    \"metricLabels\": [\"Distance (km)\", \"Duration (min)\"],
    \"isPublic\": true
  }")

echo "$PROG1" | python -m json.tool 2>/dev/null || echo "$PROG1"
PROG1_ID=$(echo "$PROG1" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
echo ""

sleep 1

echo "════════════════════════════════════════════════════════"
echo "Test 2: Create Second Progression (for streak)"
echo "════════════════════════════════════════════════════════"
PROG2=$(curl -s -X POST http://localhost:8090/api/progressions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"programId\": \"$PROGRAM_ID\",
    \"title\": \"Second session - progress!\",
    \"content\": \"Ran 6km today. Improving!\",
    \"metrics\": [6.0, 32],
    \"metricLabels\": [\"Distance (km)\", \"Duration (min)\"],
    \"isPublic\": true
  }")

echo "$PROG2" | python -m json.tool 2>/dev/null | head -20
echo ""

echo "════════════════════════════════════════════════════════"
echo "Test 3: Get My Progressions"
echo "════════════════════════════════════════════════════════"
curl -s -X GET "http://localhost:8090/api/progressions/my?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool 2>/dev/null | head -40
echo ""

echo "════════════════════════════════════════════════════════"
echo "Test 4: Calculate My Streak"
echo "════════════════════════════════════════════════════════"
curl -s -X GET http://localhost:8090/api/progressions/my/streak \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool 2>/dev/null
echo ""

echo "════════════════════════════════════════════════════════"
echo "Test 5: Get My Stats (with metrics aggregates)"
echo "════════════════════════════════════════════════════════"
curl -s -X GET http://localhost:8090/api/progressions/my/stats \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool 2>/dev/null
echo ""

if [ ! -z "$PROG1_ID" ]; then
  echo "════════════════════════════════════════════════════════"
  echo "Test 6: Update Progression"
  echo "════════════════════════════════════════════════════════"
  curl -s -X PUT "http://localhost:8090/api/progressions/$PROG1_ID" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d '{
      "title": "First workout session - UPDATED",
      "content": "Actually ran 5.5km, not 5.2!",
      "metrics": [5.5, 30]
    }' | python -m json.tool 2>/dev/null | head -20
  echo ""

  echo "════════════════════════════════════════════════════════"
  echo "Test 7: Get Single Progression"
  echo "════════════════════════════════════════════════════════"
  curl -s -X GET "http://localhost:8090/api/progressions/$PROG1_ID" \
    -H "Authorization: Bearer $TOKEN" | python -m json.tool 2>/dev/null | head -20
  echo ""
fi

if [ ! -z "$PROGRAM_ID" ]; then
  echo "════════════════════════════════════════════════════════"
  echo "Test 8: Get Progressions by Program"
  echo "════════════════════════════════════════════════════════"
  curl -s -X GET "http://localhost:8090/api/progressions/program/$PROGRAM_ID?page=0&size=5" \
    -H "Authorization: Bearer $TOKEN" | python -m json.tool 2>/dev/null | head -30
  echo ""
fi

echo "╔══════════════════════════════════════════════════════╗"
echo "║                  Tests Completed!                    ║"
echo "╚══════════════════════════════════════════════════════╝"
echo ""
echo "✅ Fonctionnalités testées:"
echo "  - Create progression"
echo "  - Get my progressions"
echo "  - Calculate streak"
echo "  - Get statistics with metrics aggregates"
echo "  - Update progression"
echo "  - Get single progression"
echo "  - Get progressions by program"
echo ""
echo "Module 2 Progression: READY FOR TESTING ✅"
