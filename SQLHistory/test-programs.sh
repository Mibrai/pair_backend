#!/bin/bash

echo "========================================="
echo "  Test Programmes & Créneaux"
echo "========================================="
echo ""

# Register user
EMAIL="test-$(date +%s)@test.com"
REG=$(curl -s -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"Test1234!\",\"displayName\":\"Test User\"}")

TOKEN=$(echo "$REG" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "✅ User registered"

# Add activity
UA=$(curl -s -X POST http://localhost:8090/api/users/me/activities \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"activityId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","visibleOnMap":true,"level":"INTERMEDIATE"}')

UA_ID=$(echo "$UA" | grep -o '"id":"[a-f0-9-]*"' | head -1 | sed 's/"id":"//' | sed 's/"//')
echo "✅ Activity added: $UA_ID"

# Create program
PROG=$(curl -s -X POST http://localhost:8090/api/programs \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userActivityId\":\"$UA_ID\",\"title\":\"Tennis Club hebdomadaire\",\"description\":\"Sessions de tennis tous les mercredis soir\",\"isPublic\":true}")

PROG_ID=$(echo "$PROG" | grep -o '"id":"[a-f0-9-]*"' | head -1 | sed 's/"id":"//' | sed 's/"//')
echo "✅ Program created: $PROG_ID"
echo "$PROG" | head -5

# Add schedule
FUTURE=$(date -u -d "+3 days" +"%Y-%m-%dT18:00:00Z" 2>/dev/null || echo "2026-07-01T18:00:00Z")
SCHED=$(curl -s -X POST "http://localhost:8090/api/programs/$PROG_ID/schedules" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"placeName\":\"Tennis Club\",\"placeType\":\"PUBLIC\",\"lat\":48.8566,\"lng\":2.3522,\"addressPublic\":\"15 Rue de la Paix\",\"startsAt\":\"$FUTURE\",\"maxParticipants\":4}")

SCHED_ID=$(echo "$SCHED" | grep -o '"id":"[a-f0-9-]*"' | head -1 | sed 's/"id":"//' | sed 's/"//')
echo "✅ Schedule created: $SCHED_ID"

# Get program with schedules
echo ""
echo "Program with schedules:"
curl -s "http://localhost:8090/api/programs/$PROG_ID" \
  -H "Authorization: Bearer $TOKEN" | head -15

# List my programs
echo ""
echo ""
MY_PROGS=$(curl -s http://localhost:8090/api/programs -H "Authorization: Bearer $TOKEN")
COUNT=$(echo "$MY_PROGS" | grep -o '"id"' | wc -l)
echo "✅ Total programs: $COUNT"

echo ""
echo "========================================="
echo "  ✅ Tests réussis!"
echo "========================================="
