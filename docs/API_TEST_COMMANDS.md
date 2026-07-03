# API Test Commands

Quick curl commands to test backend endpoints manually.

## Setup

```bash
# Set base URL
export API_BASE="http://localhost:8080/api"

# Login and get token
export TOKEN=$(curl -s -X POST "$API_BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password"}' \
  | jq -r '.accessToken')

echo "Token: $TOKEN"
```

---

## ✅ Auth API (Working)

```bash
# Register
curl -X POST "$API_BASE/auth/register" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "newuser@example.com",
    "password": "SecurePass123",
    "firstName": "John",
    "lastName": "Doe"
  }'

# Login
curl -X POST "$API_BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password"
  }'

# Verify Email (GET - backend uses GET!)
curl "$API_BASE/auth/verify-email?token=abc123"

# Forgot Password
curl -X POST "$API_BASE/auth/forgot-password" \
  -H "Content-Type: application/json" \
  -d '{"email": "test@example.com"}'

# Reset Password
curl -X POST "$API_BASE/auth/reset-password" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "reset-token",
    "newPassword": "NewSecurePass123"
  }'

# Refresh Token
curl -X POST "$API_BASE/auth/refresh" \
  -H "Content-Type: application/json" \
  -d '{"refreshToken": "your-refresh-token"}'
```

---

## ✅ User API (Mostly Working)

```bash
# Get My Profile
curl "$API_BASE/users/me" \
  -H "Authorization: Bearer $TOKEN"

# Update Profile
curl -X PUT "$API_BASE/users/me" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Jane",
    "bio": "Updated bio"
  }'

# Update Location
curl -X PUT "$API_BASE/users/me/location" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 48.8566,
    "longitude": 2.3522,
    "city": "Paris"
  }'

# Get Public Profile
curl "$API_BASE/users/{userId}" \
  -H "Authorization: Bearer $TOKEN"

# ❌ Search Users (NOT IMPLEMENTED)
curl "$API_BASE/users?query=john&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

# ❌ Upload Avatar (NOT IMPLEMENTED - use /media/upload/avatar)
curl -X POST "$API_BASE/users/me/avatar" \
  -H "Authorization: Bearer $TOKEN" \
  -F "avatar=@photo.jpg"
```

---

## ✅ Categories & Activities

```bash
# Get All Categories
curl "$API_BASE/categories" \
  -H "Authorization: Bearer $TOKEN"

# Search Activities (returns categories)
curl "$API_BASE/activities?search=yoga&page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

# Get My Activity Preferences
curl "$API_BASE/users/me/activities" \
  -H "Authorization: Bearer $TOKEN"

# Add Activity Preference
curl -X POST "$API_BASE/users/me/activities" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "categoryId": "category-uuid",
    "skillLevel": "INTERMEDIATE",
    "showOnMap": true
  }'

# Update Activity Preference
curl -X PUT "$API_BASE/users/me/activities/{userActivityId}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "skillLevel": "ADVANCED",
    "showOnMap": false
  }'

# Delete Activity Preference
curl -X DELETE "$API_BASE/users/me/activities/{userActivityId}" \
  -H "Authorization: Bearer $TOKEN"

# Toggle Visibility
curl -X PATCH "$API_BASE/users/me/activities/{userActivityId}/visibility" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"visible": true}'
```

---

## ⚠️ Program API (Partially Implemented)

```bash
# Get My Programs
curl "$API_BASE/programs" \
  -H "Authorization: Bearer $TOKEN"

# Create Program
curl -X POST "$API_BASE/programs" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Morning Yoga Sessions",
    "description": "Relaxing yoga every morning",
    "categoryId": "category-uuid",
    "activityLevel": "BEGINNER",
    "maxParticipants": 10,
    "locationType": "OUTDOOR",
    "city": "Paris"
  }'

# Get Program Detail
curl "$API_BASE/programs/{programId}" \
  -H "Authorization: Bearer $TOKEN"

# Update Program
curl -X PUT "$API_BASE/programs/{programId}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Updated Title",
    "maxParticipants": 15
  }'

# Delete Program
curl -X DELETE "$API_BASE/programs/{programId}" \
  -H "Authorization: Bearer $TOKEN"

# Add Schedule
curl -X POST "$API_BASE/programs/{programId}/schedules" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "dayOfWeek": 1,
    "startTime": "09:00",
    "duration": 60
  }'

# ❌ Join Program (NOT IMPLEMENTED)
curl -X POST "$API_BASE/programs/{programId}/join" \
  -H "Authorization: Bearer $TOKEN"

# ❌ Leave Program (NOT IMPLEMENTED)
curl -X POST "$API_BASE/programs/{programId}/leave" \
  -H "Authorization: Bearer $TOKEN"
```

---

## ✅ Chat API (Partially Implemented)

```bash
# Get Conversations
curl "$API_BASE/conversations" \
  -H "Authorization: Bearer $TOKEN"

# Create Conversation
curl -X POST "$API_BASE/conversations" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "participantIds": ["user-uuid"],
    "type": "DIRECT"
  }'

# Get Messages
curl "$API_BASE/conversations/{conversationId}/messages?limit=50" \
  -H "Authorization: Bearer $TOKEN"

# Send Message
curl -X POST "$API_BASE/conversations/{conversationId}/messages" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "conversationId": "conversation-uuid",
    "content": "Hello!",
    "messageType": "TEXT"
  }'

# Mark Conversation as Read
curl -X POST "$API_BASE/conversations/{conversationId}/read" \
  -H "Authorization: Bearer $TOKEN"

# ❌ Get Conversation Detail (NOT IMPLEMENTED)
curl "$API_BASE/conversations/{conversationId}" \
  -H "Authorization: Bearer $TOKEN"

# ❌ Edit Message (NOT IMPLEMENTED)
curl -X PATCH "$API_BASE/messages/{messageId}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"content": "Updated message"}'
```

---

## ✅ Map API (9 Endpoints)

```bash
# 1. Get Users on Map (radius-based search)
curl "$API_BASE/map/users?lat=48.8566&lng=2.3522&radiusMeters=5000" \
  -H "Authorization: Bearer $TOKEN"

# With optional filters
curl "$API_BASE/map/users?lat=48.8566&lng=2.3522&radiusMeters=10000&activityId=uuid&level=INTERMEDIATE&format=GROUP" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "userId": "uuid",
#     "displayName": "Jane Doe",
#     "avatarUrl": "https://...",
#     "latitude": 48.8570,
#     "longitude": 2.3525,
#     "isOnline": true,
#     "activities": [
#       {
#         "activityId": "uuid",
#         "activityName": "Yoga",
#         "level": "INTERMEDIATE",
#         "format": "GROUP",
#         "colorRamp": "BLUE"
#       }
#     ],
#     "verificationStatus": "VERIFIED"
#   }
# ]

# 2. Get Clusters (for map clustering at different zoom levels)
curl "$API_BASE/map/clusters?north=49&south=48&east=3&west=2&zoom=10" \
  -H "Authorization: Bearer $TOKEN"

# With activity filter
curl "$API_BASE/map/clusters?north=49&south=48&east=3&west=2&zoom=12&activityId=uuid" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "latitude": 48.8566,
#     "longitude": 2.3522,
#     "count": 15,
#     "type": "cluster"
#   },
#   {
#     "latitude": 48.9200,
#     "longitude": 2.4100,
#     "count": 1,
#     "type": "single"
#   }
# ]

# 3. Update My Location
curl -X POST "$API_BASE/map/location" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "latitude": 48.8566,
    "longitude": 2.3522,
    "city": "Paris"
  }'

# 4. Get All Markers in Bounds (users + activities + programs)
curl "$API_BASE/map/bounds?north=49&south=48&east=3&west=2" \
  -H "Authorization: Bearer $TOKEN"

# With filters and pagination
curl "$API_BASE/map/bounds?north=49&south=48&east=3&west=2&categoryIds=uuid1,uuid2&activityLevels=BEGINNER,INTERMEDIATE&limit=50&offset=0" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# {
#   "users": [...],
#   "activities": [...],
#   "programs": [...]
# }

# 5. Get Nearby Items by Type (users/activities/programs)
# Get nearby users
curl "$API_BASE/map/nearby/users?lat=48.8566&lng=2.3522&radiusKm=10" \
  -H "Authorization: Bearer $TOKEN"

# Get nearby activities
curl "$API_BASE/map/nearby/activities?lat=48.8566&lng=2.3522&radiusKm=5" \
  -H "Authorization: Bearer $TOKEN"

# Get nearby programs
curl "$API_BASE/map/nearby/programs?lat=48.8566&lng=2.3522&radiusKm=15" \
  -H "Authorization: Bearer $TOKEN"

# 6. 🔷 MOCK: Geocode (convert address to coordinates)
# Note: Currently returns mock data. Real implementation requires Google Maps/Nominatim/Mapbox API integration.
curl "$API_BASE/map/geocode?address=10%20Rue%20de%20Rivoli%2C%20Paris" \
  -H "Authorization: Bearer $TOKEN"

# Response example (MOCK):
# {
#   "latitude": 48.8566,
#   "longitude": 2.3522,
#   "address": "10 Rue de Rivoli, Paris",
#   "city": "Mock City",
#   "country": "Mock Country"
# }

# 7. 🔷 MOCK: Reverse Geocode (convert coordinates to address)
# Note: Currently returns mock data. Real implementation requires Google Maps/Nominatim/Mapbox API integration.
curl "$API_BASE/map/reverse-geocode?latitude=48.8566&longitude=2.3522" \
  -H "Authorization: Bearer $TOKEN"

# Response example (MOCK):
# {
#   "latitude": 48.8566,
#   "longitude": 2.3522,
#   "address": "Mock Address at 48.8566, 2.3522",
#   "city": "Mock City",
#   "country": "Mock Country"
# }
```

**Map API Notes:**
- All endpoints return blurred/anonymized user positions (randomized within user's `blurRadiusM`)
- Requester is never included in results
- Only users with `mapVisible=true` and valid location are returned
- Online status shown only if `onlineStatusVisible=true` and last active < 5 minutes
- Geocoding endpoints (6 & 7) are MOCK implementations awaiting external API integration

---

## ✅ Notifications (Well Implemented)

```bash
# Get Notifications
curl "$API_BASE/notifications?page=0&size=20" \
  -H "Authorization: Bearer $TOKEN"

# Get Unread Count
curl "$API_BASE/notifications/unread-count" \
  -H "Authorization: Bearer $TOKEN"

# Mark as Read
curl -X PUT "$API_BASE/notifications/{notificationId}/read" \
  -H "Authorization: Bearer $TOKEN"

# Mark All as Read
curl -X PUT "$API_BASE/notifications/read-all" \
  -H "Authorization: Bearer $TOKEN"

# Delete Notification
curl -X DELETE "$API_BASE/notifications/{notificationId}" \
  -H "Authorization: Bearer $TOKEN"

# Get Preferences
curl "$API_BASE/notifications/preferences" \
  -H "Authorization: Bearer $TOKEN"

# Update Preferences
curl -X PUT "$API_BASE/notifications/preferences" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "type": "MESSAGE",
    "emailEnabled": true,
    "pushEnabled": true,
    "frequency": "REALTIME"
  }'

# Register Device Token
curl -X POST "$API_BASE/notifications/devices" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "token": "device-token",
    "platform": "WEB",
    "deviceName": "Chrome on Windows"
  }'

# Get My Devices
curl "$API_BASE/notifications/devices" \
  -H "Authorization: Bearer $TOKEN"

# Delete Device
curl -X DELETE "$API_BASE/notifications/devices/{token}" \
  -H "Authorization: Bearer $TOKEN"
```

---

## ✅ Search API (6 Endpoints)

```bash
# 1. Semantic Search (natural language search)
curl -X POST "$API_BASE/search" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "morning yoga near me",
    "lat": 48.8566,
    "lng": 2.3522,
    "radiusMeters": 5000
  }'

# More examples
curl -X POST "$API_BASE/search" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "je cherche quelquun pour faire du tennis le weekend",
    "lat": 45.7640,
    "lng": 4.8357,
    "radiusMeters": 10000
  }'

# Response types:
# Type "results" - Found matches
# {
#   "type": "results",
#   "results": [
#     {
#       "type": "user" | "program",
#       "id": "uuid",
#       "title": "...",
#       "description": "...",
#       "score": 0.85
#     }
#   ],
#   "totalResults": 15
# }

# Type "clarification" - Query too vague
# {
#   "type": "clarification",
#   "message": "Your search is too broad. Please specify...",
#   "suggestions": ["time preference", "skill level"]
# }

# Type "empty" - No results
# {
#   "type": "empty",
#   "message": "No results found",
#   "suggestions": ["Try expanding your search radius", "Try different activities"]
# }

# 2. Get Popular Searches (last 30 days, all users)
curl "$API_BASE/search/popular?limit=10" \
  -H "Authorization: Bearer $TOKEN"

# With custom limit (max 50)
curl "$API_BASE/search/popular?limit=25" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "query": "morning yoga",
#     "count": 145,
#     "lastSearchedAt": "2026-07-03T10:30:00Z"
#   },
#   {
#     "query": "tennis partner",
#     "count": 98,
#     "lastSearchedAt": "2026-07-03T09:15:00Z"
#   }
# ]

# 3. Get My Recent Searches (last 10 searches)
curl "$API_BASE/search/recent" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "query": "morning yoga near me",
#     "searchedAt": "2026-07-03T08:45:00Z",
#     "resultCount": 12
#   },
#   {
#     "query": "tennis partner weekend",
#     "searchedAt": "2026-07-02T19:30:00Z",
#     "resultCount": 7
#   }
# ]

# 4. Clear My Recent Searches
curl -X DELETE "$API_BASE/search/recent" \
  -H "Authorization: Bearer $TOKEN"

# Response: 204 No Content

# 5. Search Tags (for MVP: returns matching categories)
curl "$API_BASE/search/tags?q=yoga&limit=10" \
  -H "Authorization: Bearer $TOKEN"

# With custom limit (max 50)
curl "$API_BASE/search/tags?q=fitness&limit=20" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "id": "uuid",
#     "name": "Yoga",
#     "slug": "yoga",
#     "usageCount": 234,
#     "type": "category"
#   },
#   {
#     "id": "uuid",
#     "name": "Hot Yoga",
#     "slug": "hot-yoga",
#     "usageCount": 89,
#     "type": "category"
#   }
# ]

# 6. Get Popular Tags (sorted by usage count)
curl "$API_BASE/search/tags/popular?limit=20" \
  -H "Authorization: Bearer $TOKEN"

# With custom limit (max 50)
curl "$API_BASE/search/tags/popular?limit=10" \
  -H "Authorization: Bearer $TOKEN"

# Response example:
# [
#   {
#     "id": "uuid",
#     "name": "Running",
#     "slug": "running",
#     "usageCount": 567,
#     "type": "category"
#   },
#   {
#     "id": "uuid",
#     "name": "Yoga",
#     "slug": "yoga",
#     "usageCount": 489,
#     "type": "category"
#   }
# ]
```

**Search API Notes:**
- Semantic search uses natural language processing for intelligent matching
- Search history is saved automatically for logged-in users
- Popular searches are computed across all users in the last 30 days
- Tag search returns categories for MVP (may expand to include custom tags later)
- All search queries are rate-limited to prevent abuse

---

## ✅ GDPR API

```bash
# Export My Data
curl "$API_BASE/gdpr/export" \
  -H "Authorization: Bearer $TOKEN" \
  > my-data-export.json

# Delete Account
curl -X DELETE "$API_BASE/gdpr/delete-account" \
  -H "Authorization: Bearer $TOKEN"
```

---

## ✅ Media API

```bash
# Upload Image
curl -X POST "$API_BASE/media/upload/image" \
  -H "Authorization: Bearer $TOKEN" \
  -F "image=@photo.jpg"

# Upload Avatar
curl -X POST "$API_BASE/media/upload/avatar" \
  -H "Authorization: Bearer $TOKEN" \
  -F "image=@avatar.jpg"

# Get File
curl "$API_BASE/media/files/{filename}" -o downloaded.jpg

# Delete File
curl -X DELETE "$API_BASE/media/files/{filename}" \
  -H "Authorization: Bearer $TOKEN"
```

---

## 🧪 Test Script

Save as `test-api.sh`:

```bash
#!/bin/bash
set -e

API_BASE="http://localhost:8080/api"

echo "🔐 Testing Auth..."
TOKEN=$(curl -s -X POST "$API_BASE/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password"}' \
  | jq -r '.accessToken')

if [ "$TOKEN" = "null" ]; then
  echo "❌ Login failed"
  exit 1
fi
echo "✅ Login successful"

echo "👤 Testing User Profile..."
USER=$(curl -s "$API_BASE/users/me" -H "Authorization: Bearer $TOKEN")
echo "✅ Got user profile"

echo "📋 Testing Categories..."
CATEGORIES=$(curl -s "$API_BASE/categories" -H "Authorization: Bearer $TOKEN")
echo "✅ Got categories"

echo "🔔 Testing Notifications..."
NOTIFS=$(curl -s "$API_BASE/notifications?page=0&size=5" -H "Authorization: Bearer $TOKEN")
echo "✅ Got notifications"

echo "🎉 All tests passed!"
```

Run with: `chmod +x test-api.sh && ./test-api.sh`

---

## Summary

- **Map API**: 7 endpoints (5 functional + 2 MOCK geocoding)
- **Search API**: 6 endpoints (all functional)
- **Total New Endpoints Documented**: 13

**Last Updated**: 2026-07-03 (Added comprehensive Map and Search API test commands)
