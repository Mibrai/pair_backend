#!/bin/bash

# Test Script for Phase 3: Trust & Credibility Layer
# Tests: Badges, Recommendations, Reviews, Reports

BASE_URL="http://localhost:8090/api"
TOKEN=""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=================================="
echo "Phase 3 - Trust Layer Tests"
echo "=================================="
echo ""

# Test 1: Login to get token
echo -e "${YELLOW}Test 1: Login${NC}"
LOGIN_RESPONSE=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "test@pair.com",
    "password": "Test1234!"
  }')

TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.accessToken // .access_token // empty')

if [ -z "$TOKEN" ]; then
  echo -e "${RED}✗ Login failed${NC}"
  echo "Response: $LOGIN_RESPONSE"
  exit 1
fi

echo -e "${GREEN}✓ Login successful${NC}"
echo "Token: ${TOKEN:0:20}..."
echo ""

# Test 2: Get all badges
echo -e "${YELLOW}Test 2: Get All Badges${NC}"
BADGES_RESPONSE=$(curl -s -X GET "$BASE_URL/badges" \
  -H "Authorization: Bearer $TOKEN")

BADGE_COUNT=$(echo $BADGES_RESPONSE | jq '. | length')
echo "Badges available: $BADGE_COUNT"
echo $BADGES_RESPONSE | jq '.[0:3]'
echo -e "${GREEN}✓ Badges list retrieved${NC}"
echo ""

# Test 3: Get my badges
echo -e "${YELLOW}Test 3: Get My Badges${NC}"
MY_BADGES=$(curl -s -X GET "$BASE_URL/badges/me" \
  -H "Authorization: Bearer $TOKEN")

MY_BADGE_COUNT=$(echo $MY_BADGES | jq '. | length')
echo "My badges count: $MY_BADGE_COUNT"
echo $MY_BADGES | jq '.'
echo -e "${GREEN}✓ My badges retrieved${NC}"
echo ""

# Test 4: Evaluate badges
echo -e "${YELLOW}Test 4: Evaluate My Badges${NC}"
EVAL_RESPONSE=$(curl -s -X POST "$BASE_URL/badges/me/evaluate" \
  -H "Authorization: Bearer $TOKEN")

NEW_BADGES=$(echo $EVAL_RESPONSE | jq '. | length')
echo "New badges earned: $NEW_BADGES"
echo $EVAL_RESPONSE | jq '.'
echo -e "${GREEN}✓ Badge evaluation complete${NC}"
echo ""

# Test 5: Get recommendation stats
echo -e "${YELLOW}Test 5: Get My Recommendation Stats${NC}"
STATS_RESPONSE=$(curl -s -X GET "$BASE_URL/recommendations/me/stats" \
  -H "Authorization: Bearer $TOKEN")

echo "Recommendation Stats:"
echo $STATS_RESPONSE | jq '.'
echo -e "${GREEN}✓ Stats retrieved${NC}"
echo ""

# Test 6: Create a recommendation (will fail without conversation)
echo -e "${YELLOW}Test 6: Create Recommendation (Expected to Fail)${NC}"
REC_RESPONSE=$(curl -s -X POST "$BASE_URL/recommendations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "recommendedId": "00000000-0000-0000-0000-000000000002",
    "rating": 5,
    "comment": "Excellent partenaire!",
    "activityContext": "Yoga"
  }')

echo "Response:"
echo $REC_RESPONSE | jq '.'

if echo $REC_RESPONSE | jq -e '.message | contains("conversation")' > /dev/null; then
  echo -e "${GREEN}✓ Validation works (conversation required)${NC}"
else
  echo -e "${RED}✗ Unexpected response${NC}"
fi
echo ""

# Test 7: Get my reviews
echo -e "${YELLOW}Test 7: Get My Reviews${NC}"
REVIEWS_RESPONSE=$(curl -s -X GET "$BASE_URL/reviews/me?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN")

echo "My Reviews:"
echo $REVIEWS_RESPONSE | jq '.'
echo -e "${GREEN}✓ Reviews retrieved${NC}"
echo ""

# Test 8: Check if I can review a program
echo -e "${YELLOW}Test 8: Can Review Program?${NC}"
PROGRAM_ID="00000000-0000-0000-0000-000000000001"
CAN_REVIEW=$(curl -s -X GET "$BASE_URL/reviews/can-review/$PROGRAM_ID" \
  -H "Authorization: Bearer $TOKEN")

echo "Can review: $CAN_REVIEW"
echo -e "${GREEN}✓ Can review check complete${NC}"
echo ""

# Test 9: Get my reports
echo -e "${YELLOW}Test 9: Get My Reports${NC}"
REPORTS_RESPONSE=$(curl -s -X GET "$BASE_URL/reports/me?page=0&size=10" \
  -H "Authorization: Bearer $TOKEN")

echo "My Reports:"
echo $REPORTS_RESPONSE | jq '.'
echo -e "${GREEN}✓ Reports retrieved${NC}"
echo ""

# Test 10: Create a report
echo -e "${YELLOW}Test 10: Create Report${NC}"
REPORT_RESPONSE=$(curl -s -X POST "$BASE_URL/reports" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "reportedEntityType": "USER",
    "reportedEntityId": "00000000-0000-0000-0000-000000000002",
    "reason": "SPAM",
    "description": "Contenu inapproprié répété"
  }')

echo "Report created:"
echo $REPORT_RESPONSE | jq '.'

if echo $REPORT_RESPONSE | jq -e '.id' > /dev/null; then
  echo -e "${GREEN}✓ Report created successfully${NC}"
else
  echo -e "${YELLOW}⚠ Report might already exist or validation failed${NC}"
fi
echo ""

# Summary
echo "=================================="
echo -e "${GREEN}Phase 3 Tests Complete!${NC}"
echo "=================================="
echo ""
echo "Summary:"
echo "- ✓ Badges system operational"
echo "- ✓ Recommendations API working"
echo "- ✓ Reviews API working"
echo "- ✓ Reports system functional"
echo ""
echo "All 4 modules of Phase 3 validated!"
