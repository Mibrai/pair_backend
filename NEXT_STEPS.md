# Next Steps — Phase 1 Implementation

## 📋 Session 2: Auth & Core API (pair-phase1-spec.md)

### Prerequisites
1. **Start PostgreSQL** with PostGIS and pgvector extensions:
   ```bash
   docker run -d --name pair-postgres \
     -e POSTGRES_USER=pair_user \
     -e POSTGRES_PASSWORD=changeme \
     -e POSTGRES_DB=pair_db \
     -p 5432:5432 \
     postgis/postgis:16-3.4
   ```

2. **Install pgvector** in the container:
   ```bash
   docker exec -it pair-postgres psql -U pair_user -d pair_db -c "CREATE EXTENSION IF NOT EXISTS vector;"
   ```

3. **Run Flyway migrations**:
   ```bash
   mvn flyway:migrate
   ```

### Implementation Order (Phase 1)

#### 1. Security Configuration
- [ ] `SecurityConfig.java` — JWT + stateless sessions
- [ ] `JwtTokenProvider.java` — Token generation/validation
- [ ] `JwtAuthFilter.java` — Request filter
- [ ] `UserDetailsServiceImpl.java` — Load user by ID
- [ ] `RateLimiter.java` — In-memory rate limiting

#### 2. Authentication Endpoints
- [ ] `AuthService.java` — Register, login, refresh logic
- [ ] `AuthController.java` — REST endpoints
- [ ] DTOs: `RegisterRequest`, `LoginRequest`, `AuthResponse`, `ErrorResponse`
- [ ] `EmailVerificationService.java` — Token generation/validation
- [ ] `GlobalExceptionHandler.java` — Centralized error handling

#### 3. User Profile Management
- [ ] `UserService.java` — CRUD operations
- [ ] `UserController.java` — REST endpoints
- [ ] DTOs: `UserPublicDto`, `UserPrivateDto`, `UpdateProfileRequest`
- [ ] `HtmlSanitizer.java` — OWASP sanitizer for user input

#### 4. Activities Management
- [ ] `ActivityService.java` — Search & user activity management
- [ ] `ActivityController.java` — REST endpoints
- [ ] DTOs: `CategoryDto`, `ActivityDto`, `UserActivityDto`

#### 5. Programs & Schedules
- [ ] `ProgramService.java` — CRUD + visibility rules
- [ ] `ProgramController.java` — REST endpoints
- [ ] DTOs: `CreateProgramRequest`, `ProgramDto`, `ScheduleDto`

#### 6. Map Feature
- [ ] `MapService.java` — PostGIS radius search + location blurring
- [ ] `MapController.java` — REST endpoint
- [ ] DTOs: `MapUserDto`, `MapSearchRequest`

#### 7. Real-Time Chat
- [ ] `WebSocketConfig.java` — STOMP configuration
- [ ] `ChatService.java` — Conversation & message logic
- [ ] `ChatController.java` — REST endpoints (list, history)
- [ ] `ChatWebSocketHandler.java` — WebSocket message handling
- [ ] DTOs: `CreateConversationRequest`, `MessageDto`, `ConversationSummaryDto`

#### 8. Email Service
- [ ] `EmailService.java` — Transactional emails (Postmark/SendGrid)
- [ ] Templates: `email/verify.html`, `email/reset-password.html`

### Environment Variables Needed

Create `src/main/resources/application-dev.properties`:

```properties
# JWT (generate with: openssl rand -base64 32)
jwt.secret=YOUR_BASE64_SECRET_HERE
jwt.access-token-expiry-ms=900000
jwt.refresh-token-expiry-ms=2592000000

# Email (Postmark or SendGrid)
spring.mail.host=smtp.postmarkapp.com
spring.mail.port=587
spring.mail.username=YOUR_POSTMARK_TOKEN
spring.mail.password=YOUR_POSTMARK_TOKEN
email.from=noreply@pair.app
email.base-url=http://localhost:8090
```

### Testing Checklist

After implementation:
- [ ] `POST /api/auth/register` — Create user
- [ ] `POST /api/auth/login` — Get JWT token
- [ ] `GET /api/users/me` — Fetch own profile (authenticated)
- [ ] `PUT /api/users/me/location` — Update location
- [ ] `GET /api/map/users?lat=48.8566&lng=2.3522&radiusMeters=5000` — Search users
- [ ] WebSocket connection to `/ws/chat`
- [ ] Send message via `/app/chat.send`

### Expected Endpoints (Phase 1)

#### Auth
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `GET /api/auth/verify-email?token=xxx`
- `POST /api/auth/forgot-password`
- `POST /api/auth/reset-password`

#### Users
- `GET /api/users/me`
- `PUT /api/users/me`
- `PUT /api/users/me/location`
- `POST /api/users/me/avatar`
- `GET /api/users/{id}`
- `DELETE /api/users/me`

#### Activities
- `GET /api/categories`
- `GET /api/activities?categoryId=&search=`
- `GET /api/users/me/activities`
- `POST /api/users/me/activities`
- `PUT /api/users/me/activities/{id}`
- `DELETE /api/users/me/activities/{id}`

#### Programs
- `GET /api/programs`
- `GET /api/programs/{id}`
- `POST /api/programs`
- `PUT /api/programs/{id}`
- `DELETE /api/programs/{id}`
- `POST /api/programs/{id}/schedules`

#### Map
- `GET /api/map/users?lat=&lng=&radiusMeters=&activityId=`

#### Chat (REST)
- `GET /api/conversations`
- `POST /api/conversations`
- `GET /api/conversations/{id}/messages`
- `POST /api/conversations/{id}/read`

#### Chat (WebSocket)
- Connect: `/ws/chat`
- Send: `/app/chat.send`
- Subscribe: `/user/queue/messages`

### Additional Dependencies for Phase 1

Add to `pom.xml`:

```xml
<!-- JWT -->
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.5</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.5</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.5</version>
  <scope>runtime</scope>
</dependency>

<!-- Email -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>

<!-- OWASP HTML Sanitizer -->
<dependency>
  <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
  <artifactId>owasp-java-html-sanitizer</artifactId>
  <version>20220608.1</version>
</dependency>
```

---

## 🎯 Session 1 Completed

✅ **Data Model**: 18 entities, 15 repositories, 9 migrations  
✅ **Compilation**: All 55 source files compile successfully  
✅ **Git**: Initial commit created on `master` branch  

**Ready for Phase 1 authentication and API implementation!**
