# API Alignment Changelog
**Date**: July 3, 2026  
**Sprint**: GDPR Compliance (Sprint 1)  
**Status**: Backend Implementation Complete

---

## Executive Summary

This changelog documents all backend API changes, frontend integration updates, and infrastructure improvements made during the GDPR compliance implementation sprint. The primary focus was implementing EU GDPR requirements (Articles 15, 17, 20) including data export, account deletion, and audit logging capabilities.

---

## 1. Backend Endpoints Added

### 1.1 GDPR Compliance Endpoints (NEW)

#### `GET /api/gdpr/export`
**Purpose**: Export all user personal data (GDPR Article 15: Right of access)  
**Authentication**: Required (JWT Bearer)  
**Controller**: `GdprController.java`  
**Service**: `GdprService.java`

**Response**: `GdprExportDto` containing:
- User profile data (email, name, bio, location, avatar)
- Activities (all user activities with levels, formats)
- Programs created (title, description, schedules, status)
- Messages sent (conversation history)
- Reviews written (ratings, comments, criteria scores)
- Peer recommendations given
- Progression records
- Notifications received
- Audit logs (last 1000 entries)
- Statistics summary

**Implementation Details**:
- Exports data in machine-readable JSON format
- Includes location data with blur radius
- Logs export action in audit trail
- Read-only transaction for consistency

#### `DELETE /api/gdpr/delete-account`
**Purpose**: Request permanent account deletion (GDPR Article 17: Right to erasure)  
**Authentication**: Required (JWT Bearer)  
**Controller**: `GdprController.java`  
**Service**: `GdprService.java`

**Behavior**:
- Account deactivated immediately
- Data purged after 30-day grace period via scheduled job
- Returns HTTP 204 (No Content) on success

**Related Job**: `GdprPurgeJob.java` (scheduled daily at 2 AM)

---

### 1.2 Audit Logging System (NEW)

**Service**: `AuditLogService.java`  
**Repository**: `AuditLogRepository.java`  
**Entity**: `AuditLog.java`

**Capabilities**:
- Asynchronous audit event logging
- Tracks user actions: CREATE, UPDATE, DELETE, LOGIN, GDPR_EXPORT, GDPR_ANONYMIZE
- Captures IP address, user agent, old/new values
- Automatic log purging after 2 years (GDPR Article 5.1.e: storage limitation)

**Action Types** (`AuditActionType.java`):
- `CREATE` - Entity creation
- `UPDATE` - Entity modification
- `DELETE` - Entity deletion
- `LOGIN` - User authentication
- `LOGOUT` - User logout
- `GDPR_EXPORT` - Data export request
- `GDPR_ANONYMIZE` - Account anonymization

**Database Schema**: `V14__create_audit_logs.sql`
- Table: `audit_logs`
- Indexes: `idx_audit_user_id`, `idx_audit_entity`, `idx_audit_created_at`
- Retention: 2 years automated cleanup

---

### 1.3 Repository Enhancements

#### UserRepository
**New Methods**:
- `findInactiveAccountsBefore(Instant cutoff)` - Find accounts for purging
- Enhanced GDPR anonymization support

#### MessageRepository
**New Methods**:
- `findBySenderId(UUID userId)` - Get all messages sent by user
- `anonymizeBySenderId(UUID userId)` - Anonymize messages for GDPR deletion

#### ReviewRepository
**New Methods**:
- `findByReviewerId(UUID userId)` - Get all reviews written by user
- `anonymizeByReviewerId(UUID userId)` - Anonymize reviews for GDPR deletion

#### PeerRecommendationRepository
**New Methods**:
- `findByRecommenderId(UUID userId)` - Get all recommendations given
- `anonymizeByRecommenderId(UUID userId)` - Anonymize recommendations

#### ProgressionRepository
**New Methods**:
- `findByProgramOrganisateurId(UUID userId)` - Get progressions for user's programs

#### NotificationRepository
**New Methods**:
- `findByUserId(UUID userId)` - Get all user notifications
- `countByUserId(UUID userId)` - Count user notifications

#### SearchLogRepository
**New Methods**:
- `deleteByUserId(UUID userId)` - Delete user search history

#### ConversationMemberRepository
**New Methods**:
- `findConversationsByUserId(UUID userId)` - Get user's conversations

#### AuditLogRepository (NEW)
**Methods**:
- `findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable)` - Get user audit logs
- `findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, UUID entityId)` - Get entity audit trail
- `countUserActionsBetween(UUID userId, Instant start, Instant end)` - Count user actions
- `anonymizeByUserId(UUID userId)` - Anonymize audit logs for deleted user
- `deleteByCreatedAtBefore(Instant cutoff)` - Purge old logs

---

## 2. Frontend API Client Changes

### 2.1 User API Enhancements

**File**: `F:/Projekt/Pair Frontend/pair_frontend/src/api/user.api.ts`

#### New Interface: `UserSearchParams`
```typescript
interface UserSearchParams {
  page?: number;
  page_size?: number;
  query?: string;
  latitude?: number;
  longitude?: number;
  max_distance_km?: number;
}
```

#### New Method: `searchUsers()`
```typescript
searchUsers: async (params?: UserSearchParams): Promise<PaginatedResponse<UserPublic>>
```
**Purpose**: Search for users with optional location-based filtering  
**Backend Endpoint**: `GET /api/users`

#### Updated Method: `deleteAccount()`
**Purpose**: Request account deletion (triggers GDPR deletion flow)  
**Backend Endpoint**: `DELETE /api/users/me`  
**Note**: This now initiates the 30-day GDPR compliant deletion process

---

### 2.2 Missing GDPR API Client Implementation

**Status**: NOT YET IMPLEMENTED in frontend

The following GDPR endpoints are available on backend but not yet integrated in frontend:

**Recommended Addition** to `user.api.ts` or new `gdpr.api.ts`:
```typescript
// GDPR Data Export
exportMyData: async (): Promise<GdprExportDto> => {
  const response = await apiClient.get('/gdpr/export');
  return response.data;
}

// GDPR Account Deletion
requestAccountDeletion: async (): Promise<void> => {
  await apiClient.delete('/gdpr/delete-account');
}
```

**Action Required**: Create frontend UI for:
1. "Download My Data" button in user settings
2. "Delete Account" flow with confirmation modal
3. Display exported data in human-readable format

---

### 2.3 Type Definitions Required

**Missing Types** to be added to `src/types/user.types.ts` or new `gdpr.types.ts`:

```typescript
interface GdprExportDto {
  exportDate: string;
  exportedBy: string;
  user: UserDataDto;
  activities: ActivityDataDto[];
  programs: ProgramDataDto[];
  messages: MessageDataDto[];
  reviews: ReviewDataDto[];
  recommendations: RecommendationDataDto[];
  progressions: ProgressionDataDto[];
  notifications: NotificationDataDto[];
  auditLogs: AuditLogDataDto[];
  statistics: Record<string, number>;
}

// Additional nested DTOs needed...
```

---

## 3. Breaking Changes

### 3.1 Database Schema Changes

**Migration**: `V14__create_audit_logs.sql`

**Impact**: NONE - Additive only
- New table `audit_logs` created
- No existing tables modified
- Backward compatible

### 3.2 API Changes

**No Breaking Changes** - All changes are additive:
- New endpoints added (`/api/gdpr/*`)
- Existing endpoints unchanged
- User deletion behavior enhanced but API contract preserved

### 3.3 Repository Query Fixes (Non-Breaking)

**Commits**:
- `6220ff8` - Fix: correct Program navigation path in Progression query
- `e352367` - Fix: use correct JPA entity names in GDPR anonymization queries
- `ed51a90` - Fix: correct User entity field references in GDPR queries

**Impact**: Internal implementation fixes only, no API contract changes

---

## 4. Infrastructure & Configuration Changes

### 4.1 Email Service Migration

**Commits**:
- `c96c79a` - Refactor: replace SendGrid with Resend for email delivery
- `c5b56ee` - Feat: integrate SendGrid for email delivery
- `e417bf1` - Fix: Mail sender
- `d1d78d7` - Feat: configure Hostinger email integration
- `5f61143` - Fix: disable mail health check for Railway

**Impact**: Email notifications now use Resend provider  
**Action Required**: Update environment variables for email configuration

### 4.2 CORS Configuration

**Commit**: `df3b2bf` - CORS Config

**Documentation**: `F:/Projekt/Pair/pair_backend/docs/deployment/CORS_CONFIGURATION.md`

**Impact**: Enhanced cross-origin request handling  
**Action Required**: Review allowed origins in production deployment

### 4.3 WebSocket Configuration

**Commits**:
- `b3b8c02` - Config: websocket for Vercel
- `907f5c4` - Fix: enable WebSocket configuration

**Impact**: WebSocket support for real-time features  
**Action Required**: Verify WebSocket endpoints in deployment

### 4.4 Docker & Deployment Fixes

**Commits**:
- `8da8119` - Fix: create uploads directory with correct permissions in Dockerfile
- `2541916` - Add: run chmod in Dockerfile

**Impact**: Improved file upload handling in containerized environments

---

## 5. Testing Recommendations

### 5.1 GDPR Endpoint Testing

#### Test: Data Export
```bash
# Get auth token first
TOKEN="your-jwt-token"

# Export user data
curl -X GET "http://localhost:8080/api/gdpr/export" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json"
```

**Expected**:
- HTTP 200 response
- Complete JSON export with all user data categories
- Audit log entry created for export action

#### Test: Account Deletion Request
```bash
# Request account deletion
curl -X DELETE "http://localhost:8080/api/gdpr/delete-account" \
  -H "Authorization: Bearer $TOKEN"
```

**Expected**:
- HTTP 204 response
- User account marked as inactive immediately
- Audit log entry created for deletion request

#### Test: Scheduled Purge Job
```bash
# Trigger manually (if endpoint exposed for testing)
# Or wait for scheduled execution at 2 AM daily
```

**Expected**:
- Accounts inactive >30 days are anonymized
- All related data anonymized (messages, reviews, recommendations)
- Search logs deleted
- Audit logs anonymized

### 5.2 Audit Logging Verification

**Test Scenarios**:
1. User login → Verify audit log entry
2. Profile update → Verify old/new values captured
3. Data export → Verify GDPR_EXPORT action logged
4. Account deletion → Verify GDPR_ANONYMIZE action logged

**Verification Query**:
```sql
SELECT * FROM audit_logs 
WHERE user_id = 'user-uuid-here' 
ORDER BY created_at DESC 
LIMIT 50;
```

### 5.3 Repository Query Testing

**Test**: Progression query with correct Program navigation
```java
// Verify fix in commit 6220ff8
List<Progression> progressions = progressionRepository
    .findByProgramOrganisateurId(userId);
```

**Expected**: No SQL errors, correct results returned

### 5.4 Integration Testing

**Scenarios**:
1. **Complete GDPR Flow**:
   - Create user account
   - Perform various actions (create programs, send messages, write reviews)
   - Export data (verify all activities captured)
   - Request deletion
   - Wait 30 days (or simulate)
   - Verify complete anonymization

2. **Audit Trail Verification**:
   - Perform 10 different user actions
   - Export audit logs
   - Verify all actions captured with correct metadata

3. **Data Integrity**:
   - Export user data
   - Verify referential integrity (all foreign keys valid)
   - Check for orphaned records

### 5.5 Performance Testing

**Load Tests**:
- GDPR export for user with 1000+ activities
- Bulk audit log queries (1000+ entries)
- Concurrent data exports (10+ users simultaneously)

**Expected**:
- Export completes in <5 seconds for typical user
- No database timeout errors
- Audit logging doesn't block main operations (async)

---

## 6. Deployment Notes

### 6.1 Database Migrations

**Required Migration**: `V14__create_audit_logs.sql`

**Pre-Deployment Checklist**:
1. Backup production database
2. Review migration script
3. Test on staging environment first
4. Execute migration: `./gradlew flywayMigrate` (or auto-migrate on startup)
5. Verify audit_logs table created with correct indexes

**Rollback Plan**: Not recommended (data loss risk)  
If needed: `DROP TABLE audit_logs;`

### 6.2 Environment Variables

**New Configuration Required**:

```properties
# Email Service (Resend)
RESEND_API_KEY=your-resend-api-key-here

# GDPR Scheduled Jobs
GDPR_PURGE_ENABLED=true
GDPR_PURGE_CRON=0 0 2 * * ?  # Daily at 2 AM
GDPR_RETENTION_DAYS=30

# Audit Log Settings
AUDIT_LOG_ENABLED=true
AUDIT_LOG_RETENTION_YEARS=2
```

**Optional Configuration**:
```properties
# CORS (already configured in code)
CORS_ALLOWED_ORIGINS=https://your-frontend-domain.com

# WebSocket
WEBSOCKET_ENABLED=true
WEBSOCKET_ALLOWED_ORIGINS=https://your-frontend-domain.com
```

### 6.3 Monitoring & Alerts

**Key Metrics to Monitor**:
1. GDPR export request volume
2. Account deletion requests
3. Audit log growth rate
4. Scheduled job execution (purge job)
5. Email delivery success rate (Resend)

**Recommended Alerts**:
- Failed GDPR exports
- Failed account purges
- Audit log table size > threshold
- Email delivery failures > 5%

### 6.4 Deployment Sequence

**Recommended Order**:
1. Deploy backend with database migration
2. Verify GDPR endpoints functional
3. Test audit logging
4. Deploy frontend (when GDPR UI implemented)
5. Enable scheduled jobs in production
6. Monitor for 24 hours

**Zero-Downtime Strategy**:
- Database migration is additive (no downtime)
- New endpoints can be deployed without affecting existing functionality
- Feature flag for GDPR UI in frontend (if applicable)

### 6.5 Documentation Updates Required

**Backend**:
- [x] API documentation (Swagger/OpenAPI)
- [x] GDPR service implementation
- [x] Database schema documentation
- [ ] Deployment runbook

**Frontend**:
- [ ] GDPR API client implementation
- [ ] User settings UI mockups
- [ ] Data export display component
- [ ] Account deletion flow

### 6.6 Security Considerations

**GDPR Compliance**:
- Data export requires authentication (JWT)
- Account deletion has 30-day grace period
- Audit logs retained for 2 years max
- User data anonymized (not deleted) for referential integrity

**Data Protection**:
- Exported data contains PII (handle securely)
- Audit logs capture IP addresses (GDPR compliant)
- Anonymization preserves statistical integrity

**Access Control**:
- Users can only export their own data
- Users can only delete their own account
- Admin endpoints for audit log review (TODO: implement if needed)

---

## 7. Known Issues & Future Work

### 7.1 Frontend Implementation Pending

**Status**: Backend complete, frontend integration incomplete

**Tasks**:
1. Create GDPR API client (`gdpr.api.ts`)
2. Add "Download My Data" button in user settings
3. Create account deletion confirmation modal
4. Display exported data in user-friendly format
5. Add loading states and error handling

**Priority**: HIGH (legal requirement for GDPR compliance)

### 7.2 Admin Dashboard for Audit Logs

**Status**: NOT IMPLEMENTED

**Recommended Feature**:
- Admin panel to view system-wide audit logs
- Filter by user, action type, date range
- Export audit reports
- Monitor GDPR compliance metrics

**Priority**: MEDIUM (nice-to-have for internal compliance)

### 7.3 Enhanced Data Portability

**Current**: Export as JSON only  
**Future**: Support additional formats
- CSV export for spreadsheet compatibility
- PDF summary report for human readability
- XML for system interoperability

**Priority**: LOW (JSON fulfills GDPR requirement)

### 7.4 Automated Compliance Reporting

**Feature Request**: Generate monthly GDPR compliance reports
- Number of data export requests
- Number of deletion requests
- Average response time
- Audit log statistics

**Priority**: MEDIUM (useful for compliance audits)

---

## 8. Related Documentation

**Backend**:
- `F:/Projekt/Pair/pair_backend/docs/FRONTEND_SPEC.md` - Complete frontend specification
- `F:/Projekt/Pair/pair_backend/docs/deployment/CORS_CONFIGURATION.md` - CORS setup guide
- `F:/Projekt/Pair/pair_backend/docs/status/FEATURE_COMPLETENESS_AUDIT.md` - Feature status
- `F:/Projekt/Pair/pair_backend/docs/API_TEST_COMMANDS.md` - API testing guide

**Frontend**:
- `F:/Projekt/Pair Frontend/pair_frontend/src/api/` - API client implementations

**Database**:
- `src/main/resources/db/migration/V14__create_audit_logs.sql` - Audit log schema

---

## 9. Commit History Reference

**GDPR Implementation** (Sprint 1):
- `a925f7a` - feat: implement GDPR compliance (Sprint 1)
- `ed51a90` - fix: correct User entity field references in GDPR queries
- `e352367` - fix: use correct JPA entity names in GDPR anonymization queries
- `6220ff8` - fix: correct Program navigation path in Progression query

**Infrastructure**:
- `df3b2bf` - CORS Config
- `b3b8c02` - config websocket for vercel
- `c96c79a` - refactor: replace SendGrid with Resend for email delivery
- `907f5c4` - fix: enable WebSocket configuration
- `8da8119` - fix: create uploads directory with correct permissions in Dockerfile

**Database Fixes**:
- `bc50cec` - fix: use native query for findByEmbeddingIsNull in ActivityRepository
- `8cc5a60` - fix: remove v_user_id column from temp tables to eliminate ambiguity
- `f705e11` - fix: resolve ambiguous column references in V13 migration

---

## 10. Summary

### Completed
- [x] GDPR data export endpoint
- [x] GDPR account deletion endpoint
- [x] Audit logging system
- [x] Database migration (V14)
- [x] Repository query enhancements
- [x] Scheduled purge job
- [x] Email service migration to Resend
- [x] CORS configuration
- [x] WebSocket setup
- [x] Docker configuration fixes

### Pending
- [ ] Frontend GDPR API client integration
- [ ] Frontend GDPR UI components
- [ ] Admin audit log dashboard
- [ ] Enhanced data export formats
- [ ] Automated compliance reporting

### Critical Path for Production
1. **IMMEDIATE**: Implement frontend GDPR UI (legal requirement)
2. **BEFORE GO-LIVE**: Complete end-to-end GDPR flow testing
3. **POST-DEPLOYMENT**: Monitor audit logs and scheduled jobs
4. **ONGOING**: Review GDPR compliance metrics monthly

---

**Changelog Maintained By**: Claude Sonnet 4.5  
**Last Updated**: 2026-07-03  
**Version**: 1.0
