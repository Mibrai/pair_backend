# Activity Architecture Decision

**Date**: 2026-07-03  
**Status**: Decision Required  
**Priority**: Critical  
**Impact**: 11 missing endpoints, architectural misalignment

---

## Problem Statement

There is a fundamental architectural mismatch between frontend and backend regarding "Activities":

- **Frontend** (`activity.api.ts`): Treats "Activities" as **real events** - user-generated activity logs with photos, likes, favorites, comments, and full CRUD operations
- **Backend** (`ActivityController.java`): Treats "Activities" as **user preferences** - profile settings indicating which sports/activities a user is interested in

This confusion results in **11 missing endpoints** and blocks core social features.

---

## Current State Analysis

### Frontend Expectations (activity.api.ts)

The frontend expects Activities to be real events that users log/create:

```typescript
interface Activity {
  activity_id: string;
  user_id: string;
  title: string;
  description?: string;
  category_id: string;
  start_time: string;
  end_time: string;
  stats: ActivityStats; // distance, duration, speed, etc.
  route_data?: GeoJSON;
  photos?: string[];
  tags?: string[];
  visibility: 'public' | 'friends' | 'private';
  is_favorite: boolean;
  likes_count: number;
  comments_count: number;
}
```

**Frontend API calls (11 endpoints)**:
1. `GET /activities` - List activity events with pagination/filtering
2. `GET /activities/{id}` - Get activity event details
3. `GET /users/{userId}/activities` - Get user's activity feed
4. `GET /users/me/activities` - Get my activity feed
5. `POST /activities` - Create new activity event
6. `PATCH /activities/{id}` - Update activity event
7. `DELETE /activities/{id}` - Delete activity event
8. `POST /activities/{id}/like` - Like an activity
9. `DELETE /activities/{id}/like` - Unlike an activity
10. `POST /activities/{id}/favorite` - Favorite an activity
11. `DELETE /activities/{id}/favorite` - Unfavorite an activity
12. `POST /activities/{id}/photos` - Upload activity photos

### Backend Reality (ActivityController.java)

The backend provides UserActivities as profile preferences:

```java
@Entity
@Table(name = "user_activities")
public class UserActivity {
    private UUID id;
    private User user;
    private Activity activity; // References activities table (sport types)
    private Boolean visibleOnMap;
    private String customDescription;
    private ActivityLevel level; // BEGINNER, INTERMEDIATE, ADVANCED, EXPERT
    private ActivityFormat format; // SOLO, GROUP, GUIDED, SELF_GUIDED
}
```

**Backend endpoints (6 endpoints)**:
1. `GET /categories` - List sport categories
2. `GET /activities?categoryId=&search=` - Search activity types (not events)
3. `GET /users/me/activities` - Get my activity preferences
4. `POST /users/me/activities` - Add activity preference
5. `PUT /users/me/activities/{userActivityId}` - Update preference
6. `DELETE /users/me/activities/{userActivityId}` - Remove preference
7. `PATCH /users/me/activities/{userActivityId}/visibility` - Toggle map visibility

**Key Difference**:
- Backend `Activity` entity = sport/activity type (e.g., "Tennis", "Running", "Yoga")
- Frontend expects Activity = logged event (e.g., "Morning Run - 5km on July 3")

---

## Missing Backend Entities

To support frontend requirements, we need:

```java
@Entity
@Table(name = "activity_events") // or "user_activity_logs"
public class ActivityEvent {
    private UUID id;
    private User user;
    private Activity activityType; // Reference to sport type
    private String title;
    private String description;
    private Instant startTime;
    private Instant endTime;
    private ActivityStats stats; // distance, duration, etc.
    private String routeData; // GeoJSON
    private List<String> photoUrls;
    private List<String> tags;
    private VisibilityLevel visibility;
    private Integer likesCount;
    private Integer commentsCount;
}

@Entity
@Table(name = "activity_likes")
public class ActivityLike {
    private UUID id;
    private ActivityEvent activityEvent;
    private User user;
}

@Entity
@Table(name = "activity_favorites")
public class ActivityFavorite {
    private UUID id;
    private ActivityEvent activityEvent;
    private User user;
}
```

---

## Options Analysis

### Option A: Quick Fix - Refactor Frontend to Match Backend

**Approach**: Treat Activities as profile preferences only, remove event logging features

**Changes Required**:
- Rename `activity.api.ts` → `userPreferences.api.ts` or merge into `category.api.ts`
- Remove unused endpoints (like, favorite, photos, create event)
- Update types to match backend DTOs
- Remove Activity feed/timeline from UI

**Pros**:
- Zero backend work required
- Immediate alignment
- No database changes
- No new controllers/services

**Cons**:
- Removes core social features (activity feed, likes, photos)
- No Strava-like activity logging
- No social engagement around activities
- Breaks frontend spec expectations (Section 6.1 Progression Tracking)
- Users cannot showcase their activities
- Missing progression tracking with activity logs

**Effort**: 0.5 days (frontend only)

**MVP Impact**: Removes activity feed, progression tracking with logs

---

### Option B: Complete Solution - Create EventController for Real Activities

**Approach**: Keep current UserActivity as preferences, create new ActivityEvent domain for real events

**Changes Required**:

#### Backend (2 days)
1. Create new domain `activityevent` (or `activitylog`)
   - `ActivityEvent` entity
   - `ActivityLike` entity
   - `ActivityFavorite` entity
   - `ActivityPhoto` entity
   - `ActivityEventRepository`
   - `ActivityEventService`
   - `ActivityEventController`
   - DTOs: `ActivityEventDto`, `CreateActivityEventRequest`, `UpdateActivityEventRequest`

2. Implement 11 endpoints:
   - `GET /activity-events` (or `/users/me/activity-events`)
   - `GET /activity-events/{id}`
   - `GET /users/{userId}/activity-events`
   - `POST /activity-events`
   - `PATCH /activity-events/{id}`
   - `DELETE /activity-events/{id}`
   - `POST /activity-events/{id}/like`
   - `DELETE /activity-events/{id}/like`
   - `POST /activity-events/{id}/favorite`
   - `DELETE /activity-events/{id}/favorite`
   - `POST /activity-events/{id}/photos`

3. Database migrations:
   - `activity_events` table
   - `activity_likes` table
   - `activity_favorites` table
   - Indexes for performance

#### Frontend (1 day)
1. Update `activity.api.ts` to call `/activity-events` endpoints
2. Rename types for clarity:
   - `Activity` → `ActivityEvent`
   - Keep `UserActivityDto` for preferences
3. Update components to use correct endpoints

**Pros**:
- Complete feature set as per spec
- Strava-like activity logging
- Social engagement (likes, favorites, comments)
- Progression tracking with real data
- Photo sharing capabilities
- Clear separation: UserActivity = preferences, ActivityEvent = logged events
- Aligns with frontend spec (Section 6.1)

**Cons**:
- 3 days total development effort
- New database tables
- More complex data model
- Requires testing and validation

**Effort**: 3 days (2 backend + 1 frontend)

**MVP Impact**: Enables full activity tracking and social features

---

## Recommendation: Option B (Complete Solution)

### Reasoning

1. **Frontend Spec Requirement**: Section 6.1 "Progression Tracking" explicitly requires:
   - Activity log with edit/delete
   - Session stats
   - Streak counter
   - Heatmap calendar
   
   These features are impossible without real activity events.

2. **Core Value Proposition**: Pair is a social fitness platform. Activity logging and social engagement around activities are core features, not nice-to-haves.

3. **Competitive Parity**: Strava, Komoot, and similar apps all have activity feeds. This is table stakes.

4. **Gamification Foundation**: Badges, streaks, and progression tracking require activity events as source data.

5. **3-Day Investment is Justified**: For an MVP feature that enables:
   - Activity feed (social)
   - Progression tracking (gamification)
   - Photo sharing (engagement)
   - Likes/favorites (social validation)

6. **Clear Data Model**: Separating UserActivity (preferences) from ActivityEvent (logged events) is architecturally sound and prevents future confusion.

### Implementation Priority

**Phase 1 (Day 1-2): Backend Core**
1. Create domain: entities, repositories, services
2. Implement CRUD endpoints (create, read, update, delete)
3. Basic pagination and filtering
4. Database migrations

**Phase 2 (Day 2-3): Backend Social**
1. Implement like/unlike endpoints
2. Implement favorite/unfavorite endpoints
3. Photo upload endpoint
4. Feed aggregation (my activities, user activities)

**Phase 3 (Day 3): Frontend Integration**
1. Update API client
2. Update types
3. Test all endpoints
4. Update components

### Risk Mitigation

- Use existing patterns from ProgramController (CRUD structure)
- Use existing MediaController for photo uploads
- Use existing pagination patterns
- Can be feature-flagged initially

---

## Alternative Naming Convention

If "Activity" is too confusing, consider renaming:

**Backend**:
- `Activity` → `ActivityType` or `SportType` (existing table)
- `UserActivity` → `UserSport` or `UserPreference` (existing table)
- `ActivityEvent` → `ActivityLog` or `WorkoutLog` (new table)

**Frontend**:
- Keep `Activity` for logged events (matches spec)
- Use `ActivityType` or `Category` for sport types
- Use `UserActivityPreference` for profile preferences

---

## Decision Criteria

Choose **Option A** if:
- MVP must ship in < 1 week
- Activity feed is not core to MVP
- Progression tracking can be delayed to Sprint 2

Choose **Option B** if:
- Activity tracking is core to value proposition
- Frontend spec requirements are fixed
- 3-day investment is acceptable for MVP
- Long-term architecture matters

---

## Next Steps

1. **Decision**: Product owner decides A or B
2. **If Option A**: Update frontend immediately, mark activity features as Sprint 2
3. **If Option B**: 
   - Create ticket: "Implement ActivityEvent domain"
   - Assign backend dev (2 days)
   - Assign frontend dev (1 day)
   - Schedule for current/next sprint

---

## References

- Frontend spec: `docs/FRONTEND_SPEC.md` (Section 6.1)
- Frontend API: `pair_frontend/src/api/activity.api.ts`
- Backend controller: `ActivityController.java`
- Related: `ProgressionController.java`, `BadgeController.java`
