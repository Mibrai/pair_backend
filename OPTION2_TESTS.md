# Option 2 - Execute Tests

**Date**: 2026-06-23  
**Status**: ⏳ PENDING (Option 1 first)

---

## Objectif

Exécuter tous les scripts de test automatisés pour valider les fonctionnalités Phase 1 & 2.

---

## Prerequisites

- [x] Option 1 complétée (Application deployed)
- [x] Application running on port 8090
- [x] Database populated avec seed data
- [x] No errors in startup logs

---

## Test Scripts Available

### Phase 1 Tests

#### 1. test-activities-complete.sh
**Durée**: ~5 min  
**Endpoints testés**:
- GET /api/categories
- GET /api/activities
- POST /api/user-activities
- GET /api/user-activities/my
- PUT /api/user-activities/{id}
- DELETE /api/user-activities/{id}

**Validation**:
- Categories retrieved
- Activities retrieved
- User activity created
- User activity updated
- User activity deleted
- Full CRUD cycle

#### 2. test-programs.sh
**Durée**: ~5 min  
**Endpoints testés**:
- POST /api/programs
- GET /api/programs
- GET /api/programs/{id}
- PUT /api/programs/{id}
- DELETE /api/programs/{id}

**Validation**:
- Program created with slots
- Program retrieved
- Program updated
- Program deleted
- Slot management works

#### 3. test-map.sh
**Durée**: ~3 min  
**Endpoints testés**:
- POST /api/map/nearby

**Validation**:
- Geographic search works
- Distance calculation accurate
- Filters applied correctly
- Results paginated

#### 4. test-chat.sh
**Durée**: ~5 min  
**Endpoints testés**:
- POST /api/conversations
- GET /api/conversations
- GET /api/conversations/{id}/messages
- POST /api/messages
- WS /ws (WebSocket)

**Validation**:
- Conversation created
- Messages sent/received
- WebSocket connection stable
- Read receipts work

---

### Phase 2 Tests

#### 5. test-search.sh
**Durée**: ~5 min  
**Endpoints testés**:
- POST /api/search

**Scenarios**:
1. Natural language query: "Je cherche un partenaire de tennis à Paris"
2. Location-based: "Activités sportives à 5km"
3. Activity-specific: "Cours de piano débutant"
4. Time-based: "Activités ce weekend"
5. Level filter: "Tennis niveau intermédiaire"

**Validation**:
- LLM intent extraction works
- Full-Text Search returns results
- Filters correctly applied
- Relevance scoring accurate
- Fallback to simple search if LLM fails

#### 6. test-progressions.sh
**Durée**: ~4 min  
**Endpoints testés**:
- POST /api/progressions
- GET /api/progressions/my
- GET /api/progressions/my/streak
- GET /api/progressions/my/stats

**Validation**:
- Progression logged
- Streak calculated correctly
- Stats aggregated
- Public/Private visibility respected
- Multiple progressions tracked

#### 7. test-media.sh
**Durée**: ~3 min  
**Endpoints testés**:
- POST /api/media/upload/image
- POST /api/media/upload/avatar
- GET /api/media/files/**

**Scenarios**:
1. Upload valid image (JPEG)
2. Upload avatar (PNG)
3. Serve file (download)
4. Invalid file size (>10MB) - should FAIL
5. Invalid MIME type (PDF) - should FAIL
6. Storage directory check

**Validation**:
- Images uploaded successfully
- MIME validation works (Tika magic bytes)
- Size limit enforced
- Files optimized (Thumbnailator)
- Storage directory created
- Files accessible via GET

---

## Execution Plan

### Step 1: Verify Application Ready

```bash
# Health check
curl http://localhost:8090/actuator/health
# Expected: {"status":"UP"}

# Check user count
psql pair_db -c "SELECT COUNT(*) FROM users;"
# Expected: > 0 (seed data)
```

### Step 2: Run Phase 1 Tests

```bash
cd SQLHistory

# Test 1
echo "=== Test Activities ==="
bash test-activities-complete.sh
# Save output to OPTION2_TEST_RESULTS.md

# Test 2
echo "=== Test Programs ==="
bash test-programs.sh

# Test 3
echo "=== Test Map ==="
bash test-map.sh

# Test 4
echo "=== Test Chat ==="
bash test-chat.sh
```

### Step 3: Run Phase 2 Tests

```bash
# Test 5
echo "=== Test Search ==="
bash test-search.sh
# Note: Requires ANTHROPIC_API_KEY for LLM

# Test 6
echo "=== Test Progressions ==="
bash test-progressions.sh

# Test 7
echo "=== Test Media ==="
bash test-media.sh
```

### Step 4: Validation

```bash
# Check for errors in logs
tail -100 /tmp/pair-final.log | grep "ERROR"

# Verify no memory leaks
ps aux | grep java | grep Pair
```

---

## Success Criteria

Option 2 est complète quand:

- [ ] All Phase 1 tests pass (4/4)
- [ ] All Phase 2 tests pass (3/3)
- [ ] No ERROR in application logs
- [ ] No database connection issues
- [ ] Performance acceptable (<500ms avg)
- [ ] Memory usage stable (<1GB)
- [ ] WebSocket connections stable

---

## Expected Results

### Phase 1
- ✅ 20+ endpoints validated
- ✅ Full CRUD operations work
- ✅ WebSocket chat functional
- ✅ Geographic search accurate

### Phase 2
- ✅ LLM integration works (or fallback)
- ✅ Full-Text Search returns results
- ✅ Progression tracking accurate
- ✅ Media upload secure

---

## Known Issues to Watch

### Issue: LLM API Key
**If**: `ANTHROPIC_API_KEY` not set  
**Then**: Search will fallback to simple Full-Text Search  
**Action**: Expected behavior, not an error

### Issue: Port 8090 busy
**If**: Tests fail to connect  
**Then**: Check application still running  
**Action**: Restart if needed

### Issue: Database lock
**If**: Concurrent test failures  
**Then**: Tests might need sequential execution  
**Action**: Add delays between tests

---

## Test Results Document

Create `OPTION2_TEST_RESULTS.md` with:

```markdown
# Test Results - Option 2

**Date**: 2026-06-23
**Duration**: XX minutes

## Phase 1 Tests
- [x] test-activities-complete.sh: ✅ PASS
- [x] test-programs.sh: ✅ PASS
- [x] test-map.sh: ✅ PASS
- [x] test-chat.sh: ✅ PASS

## Phase 2 Tests
- [x] test-search.sh: ✅ PASS
- [x] test-progressions.sh: ✅ PASS
- [x] test-media.sh: ✅ PASS

## Summary
- Total: 7/7 tests passed
- Errors: 0
- Warnings: 0
- Performance: <500ms avg
```

---

## Timeline Estimate

- **Phase 1 tests**: 18 minutes (4 scripts @ 4-5 min each)
- **Phase 2 tests**: 12 minutes (3 scripts @ 3-5 min each)
- **Validation**: 5 minutes
- **Total**: ~35 minutes

---

## After Option 2

**Next**: Option 3 - Implement Phase 3

See: `PHASE3_IMPLEMENTATION_PLAN.md`

**Modules**:
1. Badges (2-3h)
2. Peer Recommendations (3-4h)
3. Program Reviews (3-4h)
4. Reports (1-2h)

**Total**: 10-12 hours

---

## Notes

- Tests scripts already exist in `SQLHistory/`
- All tests use `curl` for HTTP requests
- JWT tokens managed automatically in scripts
- Tests create/cleanup their own data
- Safe to run multiple times

**Key Validations**:
- Authentication works
- Database operations succeed
- Business logic correct
- Error handling robust
- Security enforced

---

**Status**: ⏳ Waiting for Option 1 completion...
