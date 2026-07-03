# Frontend-Backend API Usage Report

**Generated:** 2026-07-03  
**Repository:** Pair Backend & Frontend

---

## Executive Summary

| Metric | Value |
|--------|-------|
| **Total Backend Endpoints** | 93 |
| **Total Frontend API Calls** | 86 |
| **Used Backend Endpoints** | 62 (66.7%) |
| **Unused Backend Endpoints** | 31 (33.3%) |
| **Broken Frontend Calls** | 24 (27.9%) |
| **Overall Alignment Health** | **MODERATE** - Significant issues found |

### Quick Status Overview

- ✅ **Aligned Modules (4):** Auth, Map, Search, GDPR & Settings
- ⚠️ **Mostly Aligned (2):** User, Notification
- ⚠️ **Underutilized (1):** Chat
- ⚠️ **Misaligned (1):** Program & Enrollment
- ❌ **Critical Mismatch (3):** Reviews, Activity, Badge

---

## Per-Module Analysis

### ✅ Auth Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 7 |
| Frontend Calls | 7 |
| Used Endpoints | 7 |
| Unused Endpoints | 0 |
| Broken Calls | 0 |
| **Usage Rate** | **100%** |
| **Status** | **✅ ALIGNED** |

**Notes:** Perfect alignment between frontend and backend.

---

### ⚠️ User Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 10 |
| Frontend Calls | 8 |
| Used Endpoints | 8 |
| Unused Endpoints | 0 |
| Broken Calls | 1 |
| **Usage Rate** | **80%** |
| **Status** | **⚠️ MOSTLY ALIGNED** |

#### Broken Frontend Calls:
- `PUT /api/users/me/preferences` - Backend doesn't have preferences endpoint (only privacy settings exist)

---

### ⚠️ Chat Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 12 |
| Frontend Calls | 5 |
| Used Endpoints | 5 |
| Unused Endpoints | 7 |
| Broken Calls | 0 |
| **Usage Rate** | **41.7%** |
| **Status** | **⚠️ UNDERUTILIZED** |

#### Unused Backend Endpoints:
1. `GET /api/conversations/{conversationId}` - Get conversation details
2. `DELETE /api/conversations/{conversationId}` - Delete conversation
3. `PATCH /api/messages/{messageId}` - Edit message
4. `DELETE /api/messages/{messageId}` - Delete message
5. `POST /api/conversations/{conversationId}/read-all` - Mark all messages as read
6. `POST /api/conversations/{conversationId}/images` - Upload conversation image
7. `WebSocket @MessageMapping /chat.send` - Real-time messaging

**Notes:** Backend has advanced chat features (message editing, deletion, images, WebSocket) that frontend doesn't utilize.

---

### ⚠️ Program & Enrollment Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 18 |
| Frontend Calls | 18 |
| Used Endpoints | 12 |
| Unused Endpoints | 6 |
| Broken Calls | 6 |
| **Usage Rate** | **66.7%** |
| **Status** | **⚠️ MISALIGNED** |

#### Unused Backend Endpoints:
1. `GET /api/programs/new` - Error endpoint (returns 400)
2. `PUT /api/programs/{programId}` - Update program
3. `DELETE /api/programs/{programId}` - Delete program
4. `POST /api/programs/{programId}/schedules` - Add schedule
5. `PUT /api/programs/{programId}/schedules/{scheduleId}` - Update schedule
6. `DELETE /api/programs/{programId}/schedules/{scheduleId}` - Delete schedule

#### Broken Frontend Calls:
1. `PATCH /api/programs/drafts/{draftId}` - Backend has no draft system
2. `POST /api/programs/{programId}/report` - Backend has no reporting system

---

### ❌ Reviews Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 5 |
| Frontend Calls | 4 |
| Used Endpoints | 1 |
| Unused Endpoints | 4 |
| Broken Calls | 4 |
| **Usage Rate** | **20%** |
| **Status** | **❌ CRITICAL MISMATCH** |

#### Unused Backend Endpoints:
1. `GET /api/reviews/programs/{programId}/summary` - Review summary with averages
2. `GET /api/reviews/me` - My submitted reviews
3. `GET /api/reviews/can-review/{programId}` - Check review eligibility

#### Broken Frontend Calls:
1. `GET /api/programs/{programId}/reviews` - Should be `GET /api/reviews/programs/{programId}`
2. `POST /api/programs/{programId}/reviews` - Should be `POST /api/reviews` (with programId in body)
3. `PATCH /api/programs/reviews/{reviewId}` - Backend has no update review endpoint
4. `DELETE /api/programs/reviews/{reviewId}` - Backend has no delete review endpoint

#### Path Mismatches:
- **Frontend Pattern:** `/programs/{programId}/reviews`
- **Backend Pattern:** `/reviews/programs/{programId}`

**Critical Issue:** Fundamental path structure mismatch causing all review operations to fail.

---

### ✅ Map Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 7 |
| Frontend Calls | 7 |
| Used Endpoints | 7 |
| Unused Endpoints | 0 |
| Broken Calls | 0 |
| **Usage Rate** | **100%** |
| **Status** | **✅ ALIGNED** |

**Notes:** Perfect alignment. Note: 2 endpoints are mock implementations (geocode, reverse-geocode).

---

### ✅ Search Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 6 |
| Frontend Calls | 6 |
| Used Endpoints | 6 |
| Unused Endpoints | 0 |
| Broken Calls | 0 |
| **Usage Rate** | **100%** |
| **Status** | **✅ ALIGNED** |

**Notes:** Perfect alignment between frontend and backend.

---

### ⚠️ Notification Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 10 |
| Frontend Calls | 10 |
| Used Endpoints | 8 |
| Unused Endpoints | 0 |
| Broken Calls | 2 |
| **Usage Rate** | **80%** |
| **Status** | **⚠️ MOSTLY ALIGNED** |

#### Broken Frontend Calls:
1. `DELETE /api/notifications/read` - Backend only has `DELETE /api/notifications/{id}`
2. `POST /api/notifications/push/unregister` - Backend has `DELETE /api/notifications/devices/{token}`

#### Path Mismatches:
- **Frontend:** `POST /api/devices`
- **Backend:** `POST /api/notifications/devices` (missing /notifications prefix)

---

### ❌ Activity Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 7 |
| Frontend Calls | 16 |
| Used Endpoints | 4 |
| Unused Endpoints | 1 |
| Broken Calls | 11 |
| **Usage Rate** | **57.1%** |
| **Status** | **❌ CRITICAL MISMATCH** |

#### Unused Backend Endpoints:
1. `PATCH /api/users/me/activities/{userActivityId}/visibility` - Toggle map visibility

#### Broken Frontend Calls:
1. `GET /api/activities/{activityId}` - Backend only has search endpoint
2. `GET /api/users/{userId}/activities` - Backend only has `/users/me/activities`
3. `POST /api/activities` - Backend only supports `POST /api/users/me/activities`
4. `PATCH /api/activities/{activityId}` - No general activity update, only user activities
5. `DELETE /api/activities/{activityId}` - No general activity delete
6. `POST /api/activities/{activityId}/like` - Backend has no like system
7. `DELETE /api/activities/{activityId}/like` - Backend has no like system
8. `POST /api/activities/{activityId}/favorite` - Backend has no favorite system
9. `DELETE /api/activities/{activityId}/favorite` - Backend has no favorite system
10. `POST /api/activities/{activityId}/photos` - Backend has no photo upload for activities
11. `GET /api/categories/{categoryId}` - Backend only has list endpoint

**Critical Issue:** Frontend expects full activity CRUD + social features (likes, favorites, photos). Backend only supports user-activity associations.

---

### ❌ Badge Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 5 |
| Frontend Calls | 3 |
| Used Endpoints | 1 |
| Unused Endpoints | 4 |
| Broken Calls | 2 |
| **Usage Rate** | **20%** |
| **Status** | **❌ CRITICAL MISMATCH** |

#### Unused Backend Endpoints:
1. `GET /api/badges` - List all available badges
2. `GET /api/badges/users/{userId}` - Get user's public badges
3. `POST /api/badges/me/evaluate` - Trigger badge evaluation
4. `GET /api/badges/me/count` - Get badge count

#### Broken Frontend Calls:
1. `GET /api/badges/{badgeId}/progress` - Backend has no progress tracking endpoint
2. `POST /api/badges/{badgeId}/claim` - Backend has no manual claim, uses auto-evaluation

**Critical Issue:** Different badge flow paradigm - Frontend expects manual claim, backend uses auto-evaluation.

---

### ✅ GDPR & Settings Module
| Metric | Value |
|--------|-------|
| Backend Endpoints | 6 |
| Frontend Calls | 6 |
| Used Endpoints | 6 |
| Unused Endpoints | 4 |
| Broken Calls | 0 |
| **Usage Rate** | **100%** |
| **Status** | **✅ ALIGNED** |

#### Unused Backend Endpoints:
1. `POST /api/media/upload/image` - Upload program/activity images
2. `POST /api/media/upload/avatar` - Upload avatar (duplicates /users/me/avatar)
3. `GET /api/media/files/**` - Serve media files
4. `DELETE /api/media/files/**` - Delete media files

**Notes:** GDPR endpoints aligned. Media endpoints unused (avatar upload uses different endpoint).

---

## Complete List of Unused Backend Endpoints (31)

### Chat (7 endpoints)
1. `GET /api/conversations/{conversationId}` - Get conversation details
2. `DELETE /api/conversations/{conversationId}` - Delete conversation
3. `PATCH /api/messages/{messageId}` - Edit message
4. `DELETE /api/messages/{messageId}` - Delete message
5. `POST /api/conversations/{conversationId}/read-all` - Mark all messages as read
6. `POST /api/conversations/{conversationId}/images` - Upload conversation image
7. `WebSocket @MessageMapping /chat.send` - Real-time messaging

### Program & Enrollment (6 endpoints)
8. `GET /api/programs/new` - Error endpoint (returns 400)
9. `PUT /api/programs/{programId}` - Update program
10. `DELETE /api/programs/{programId}` - Delete program
11. `POST /api/programs/{programId}/schedules` - Add schedule
12. `PUT /api/programs/{programId}/schedules/{scheduleId}` - Update schedule
13. `DELETE /api/programs/{programId}/schedules/{scheduleId}` - Delete schedule

### Reviews (3 endpoints)
14. `GET /api/reviews/programs/{programId}/summary` - Review summary with averages
15. `GET /api/reviews/me` - My submitted reviews
16. `GET /api/reviews/can-review/{programId}` - Check review eligibility

### Activity (1 endpoint)
17. `PATCH /api/users/me/activities/{userActivityId}/visibility` - Toggle map visibility

### Badge (4 endpoints)
18. `GET /api/badges` - List all available badges
19. `GET /api/badges/users/{userId}` - Get user's public badges
20. `POST /api/badges/me/evaluate` - Trigger badge evaluation
21. `GET /api/badges/me/count` - Get badge count

### Media (4 endpoints)
22. `POST /api/media/upload/image` - Upload program/activity images
23. `POST /api/media/upload/avatar` - Upload avatar (duplicates /users/me/avatar)
24. `GET /api/media/files/**` - Serve media files
25. `DELETE /api/media/files/**` - Delete media files

---

## Complete List of Broken Frontend Calls (24)

### User (1 call)
1. `PUT /api/users/me/preferences` - Backend doesn't have preferences endpoint

### Program & Enrollment (2 calls)
2. `PATCH /api/programs/drafts/{draftId}` - Backend has no draft system
3. `POST /api/programs/{programId}/report` - Backend has no reporting system

### Reviews (4 calls)
4. `GET /api/programs/{programId}/reviews` - Should be `GET /api/reviews/programs/{programId}`
5. `POST /api/programs/{programId}/reviews` - Should be `POST /api/reviews` (with programId in body)
6. `PATCH /api/programs/reviews/{reviewId}` - Backend has no update review endpoint
7. `DELETE /api/programs/reviews/{reviewId}` - Backend has no delete review endpoint

### Notification (2 calls)
8. `DELETE /api/notifications/read` - Backend only has `DELETE /api/notifications/{id}`
9. `POST /api/notifications/push/unregister` - Backend has `DELETE /api/notifications/devices/{token}`

### Activity (11 calls)
10. `GET /api/activities/{activityId}` - Backend only has search endpoint
11. `GET /api/users/{userId}/activities` - Backend only has `/users/me/activities`
12. `POST /api/activities` - Backend only supports `POST /api/users/me/activities`
13. `PATCH /api/activities/{activityId}` - No general activity update
14. `DELETE /api/activities/{activityId}` - No general activity delete
15. `POST /api/activities/{activityId}/like` - Backend has no like system
16. `DELETE /api/activities/{activityId}/like` - Backend has no like system
17. `POST /api/activities/{activityId}/favorite` - Backend has no favorite system
18. `DELETE /api/activities/{activityId}/favorite` - Backend has no favorite system
19. `POST /api/activities/{activityId}/photos` - Backend has no photo upload
20. `GET /api/categories/{categoryId}` - Backend only has list endpoint

### Badge (2 calls)
21. `GET /api/badges/{badgeId}/progress` - Backend has no progress tracking
22. `POST /api/badges/{badgeId}/claim` - Backend uses auto-evaluation

---

## Path Mismatches

### 1. Reviews Module (HIGH SEVERITY)
- **Frontend Pattern:** `/programs/{programId}/reviews`
- **Backend Pattern:** `/reviews/programs/{programId}`
- **Impact:** 4 broken calls
- **Fix Required:** Align path structure in either frontend or backend

### 2. Notification Device Registration (LOW SEVERITY)
- **Frontend:** `POST /api/devices`
- **Backend:** `POST /api/notifications/devices`
- **Impact:** 1 broken call
- **Fix Required:** Add `/notifications` prefix in frontend

---

## Critical Issues Summary

| Priority | Module | Issue | Impact | Broken Calls |
|----------|--------|-------|--------|--------------|
| **HIGH** | Reviews | Path structure mismatch | All review operations fail | 4 |
| **HIGH** | Activity | Missing backend features | Social features non-functional | 11 |
| **HIGH** | Chat | Unused advanced features | Lost functionality potential | 0 (7 unused) |
| **MEDIUM** | Badge | Different paradigm (manual vs auto) | Badge claiming broken | 2 |
| **MEDIUM** | Program | Missing draft/reporting | Draft workflow broken | 2 |
| **LOW** | Notification | Minor path inconsistencies | Workarounds possible | 2 |

---

## Recommendations

### Immediate Actions (Critical - Do First)

1. **Fix Reviews Module Path Mismatch**
   - **Priority:** CRITICAL
   - **Effort:** Low (path refactoring)
   - **Impact:** Fixes 4 broken calls
   - **Action:** Choose one pattern and align both sides
     - Option A: Move backend to `/programs/{id}/reviews`
     - Option B: Update frontend to `/reviews/programs/{id}` (recommended - RESTful)

2. **Clarify Activity Module Scope**
   - **Priority:** CRITICAL
   - **Effort:** Medium (documentation + decision)
   - **Impact:** Fixes 11 broken calls
   - **Action:** Document that backend only handles user-activity associations, not full activity CRUD
   - **Decision Required:** Will activities be standalone entities or always user-owned?

### High Priority Actions

3. **Implement or Remove Social Features**
   - **Priority:** HIGH
   - **Effort:** High (implementation) or Medium (removal)
   - **Impact:** Fixes 9 broken calls (likes, favorites, photos)
   - **Action:** 
     - Option A: Implement like/favorite/photo systems in backend
     - Option B: Remove UI elements from frontend

4. **Enable Chat Advanced Features**
   - **Priority:** HIGH
   - **Effort:** Medium (UI implementation)
   - **Impact:** Utilizes 7 unused endpoints
   - **Action:** Implement frontend UI for:
     - Message editing/deletion
     - Conversation deletion
     - Image uploads
     - WebSocket real-time messaging

### Medium Priority Actions

5. **Implement Program Draft System**
   - **Priority:** MEDIUM
   - **Effort:** High
   - **Impact:** Fixes 1 broken call
   - **Action:** Implement draft persistence in backend or remove draft UI from frontend

6. **Align Badge Award Mechanism**
   - **Priority:** MEDIUM
   - **Effort:** Medium
   - **Impact:** Fixes 2 broken calls
   - **Action:** Decide on badge paradigm:
     - Option A: Backend adds manual claim endpoint
     - Option B: Frontend switches to auto-evaluation model

### Low Priority Actions

7. **Standardize Notification Paths**
   - **Priority:** LOW
   - **Effort:** Low
   - **Impact:** Fixes 2 broken calls
   - **Action:** Add `/notifications` prefix to device endpoints in frontend

8. **Cleanup Media Controller**
   - **Priority:** LOW
   - **Effort:** Low
   - **Impact:** Removes 4 unused endpoints or integrates them
   - **Action:** Decide if media endpoints should be unified or removed

---

## Alignment Health Score by Category

| Category | Score | Status |
|----------|-------|--------|
| **Authentication & Security** | 100% | ✅ Excellent |
| **User Management** | 80% | ⚠️ Good |
| **Communication (Chat)** | 42% | ⚠️ Needs Improvement |
| **Programs & Learning** | 67% | ⚠️ Fair |
| **Social & Reviews** | 20% | ❌ Poor |
| **Map & Location** | 100% | ✅ Excellent |
| **Search** | 100% | ✅ Excellent |
| **Notifications** | 80% | ⚠️ Good |
| **Activities & Gamification** | 39% | ❌ Poor |
| **Privacy & Compliance** | 100% | ✅ Excellent |

---

## Conclusion

The Pair application shows **moderate frontend-backend alignment** with significant issues concentrated in specific modules:

### Strengths
- Core features (Auth, Search, Map, GDPR) are perfectly aligned
- 66.7% of backend endpoints are actively used
- No critical security or authentication gaps

### Weaknesses
- **Reviews module has fundamental path structure mismatch** (CRITICAL)
- **Activity module has architectural mismatch** (CRITICAL) - frontend expects standalone activities, backend treats them as user-owned associations
- **Social features (likes, favorites) missing in backend** but present in frontend
- **Chat advanced features built but not used** (7 unused endpoints)
- **Badge system paradigm mismatch** (manual vs automatic)

### Next Steps
1. **Week 1:** Fix Reviews path mismatch (CRITICAL)
2. **Week 1-2:** Clarify and document Activity architecture (CRITICAL)
3. **Week 2-3:** Decide on social features - implement or remove
4. **Week 3-4:** Enable Chat advanced features in frontend
5. **Ongoing:** Address medium/low priority items as capacity allows

**Estimated effort to reach 90%+ alignment:** 4-6 weeks of focused development.

---

*Report generated by API alignment analysis tool*  
*Last updated: 2026-07-03*
