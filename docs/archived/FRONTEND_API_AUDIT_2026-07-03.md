# Audits d'alignement API frontend / backend — 2026-07-03

> **Archivé le 20 août 2026.** Ces deux audits vivaient à la suite de
> `docs/FRONTEND_SPEC.md`. Ils décrivent l'état de l'API au 3 juillet 2026 et sont
> **périmés** : ils annoncent 47 % d'alignement et des routes manquantes — `logout`,
> `join`/`leave`, la messagerie — qui existent toutes depuis. Les lire aujourd'hui donne
> une image fausse du backend.
>
> Conservés parce qu'ils datent une étape réelle du projet, et parce que la liste des
> divergences qu'ils recensent explique plusieurs choix encore en vigueur.
>
> Pour l'état courant : `docs/ARCHITECTURE_BACKEND.md` (§4, routes par domaine) et
> `docs/specs/VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md` (ce qui reste à vérifier).

---

# ✅ VÉRIFICATION COMPLÈTE - Frontend API vs Backend (2026-07-03)

**Status**: Vérification complète terminée
**Résultat**: 89 endpoints frontend analysés, 34 problèmes identifiés

## 📊 Vue d'ensemble

| Catégorie | Total Frontend | Aligné | Problèmes | Taux |
|-----------|----------------|--------|-----------|------|
| Auth | 8 | 5 | 3 | 62% |
| User | 8 | 5 | 3 | 62% |
| Activity/Category | 22 | 9 | 13 | 41% |
| Badge | 3 | 2 | 1 | 67% |
| Chat | 11 | 5 | 6 | 45% |
| Program | 18 | 8 | 10 | 44% |
| Map | 10 | 1 | 9 | 10% |
| Search | 6 | 1 | 5 | 17% |
| Notification | 10 | 9 | 1 | 90% |
| Settings | 10 | 5 | 5 | 50% |
| **TOTAL** | **106** | **50** | **56** | **47%** |

## 🚨 Problèmes Critiques Identifiés

### 1. Auth - Méthodes HTTP non alignées
- `verify-email`: Frontend POST ≠ Backend GET ❌
- `confirm-reset-password`: Endpoint manquant backend ❌
- `logout`: Endpoint manquant backend ❌

### 2. Activity - Confusion architecturale majeure
- Frontend attend des "Activities" (événements) ❌
- Backend fournit des "UserActivities" (préférences) ❌
- 11 endpoints manquants (like, favorite, photos, CRUD complet) ❌

### 3. Program - Fonctionnalités d'enrollment manquantes
- `join/leave`: Manquants ❌
- `/users/me/programs`: Manquant ❌
- Progress tracking: Manquant ❌
- Reviews system: Manquant (4 endpoints) ❌
- Draft system: Manquant ❌

### 4. Map - Seulement 10% implémenté
- Bounds queries: Manquants (3 endpoints) ❌
- Clustering: Manquant ❌
- Geocoding: Manquant (2 endpoints) ❌
- Location update: Manquant ❌

### 5. Chat - Fonctionnalités d'édition manquantes
- Message edit/delete: Manquants ❌
- Conversation detail/delete: Manquants ❌
- Image upload: Manquant ❌

## ✅ APIs Bien Alignées

### Notification API - 90% ✅
Meilleure implémentation, presque tous les endpoints alignés sauf:
- `DELETE /notifications/read` (minor)

### User API - 62% ✅
Base solide, manque:
- Avatar upload
- User search
- Privacy settings

### Badge API - 67% ✅
Fonctionnel mais manque:
- Badge progress endpoint

---

# ANALYSE DES APPELS D'API FRONTEND vs BACKEND

## ✅ APIs correctement implémentées

### Auth API
- ✅ `/api/auth/login` (POST)
- ✅ `/api/auth/register` (POST)
- ✅ `/api/auth/refresh` (POST)
- ⚠️ **PROBLÈME CRITIQUE**: Frontend `verifyEmail` utilise POST mais backend utilise GET
  - Frontend: `POST /auth/verify-email` avec body `{token}` (auth.api.ts:74-78)
  - Backend: `GET /auth/verify-email?token={token}` (AuthController.java:47-51)
  - **FIX REQUIS**: Modifier frontend pour utiliser GET avec query param
- ⚠️ **PROBLÈME**: Endpoints password reset non alignés
  - Frontend `resetPassword`: `POST /auth/reset-password` avec `{email}` (auth.api.ts:81-85)
  - Frontend `confirmResetPassword`: `POST /auth/confirm-reset-password` avec `{token, newPassword}` (auth.api.ts:88-96)
  - Backend `forgotPassword`: `POST /auth/forgot-password` avec `{email}` (AuthController.java:53-61)
  - Backend `resetPassword`: `POST /auth/reset-password` avec `{token, newPassword}` (AuthController.java:63-67)
  - **FIX REQUIS**: Renommer les fonctions frontend ou créer des alias

### User API
- ✅ `/api/users/me` (GET)
- ✅ `/api/users/me` (PUT)
- ✅ `/api/users/me/location` (PUT)
- ✅ `/api/users/{id}` (GET)
- ✅ `/api/users/me` (DELETE)
- ❌ `/api/users/me/avatar` (POST) - Frontend l'appelle mais backend utilise `/api/media/upload/avatar`
- ❌ `/api/users` (GET pour search) - Frontend l'appelle avec params mais backend UserController n'a pas de search
- ❌ `/api/users/me/preferences` (PUT) - Frontend settings.api.ts l'appelle mais backend ne l'a pas
- ❌ `/api/users/me/privacy` (GET/PUT) - Frontend settings.api.ts l'appelle mais backend ne l'a pas
- ❌ `/api/users/me/change-password` (POST) - Frontend settings.api.ts l'appelle mais backend ne l'a pas

### Activity/Category API
- ✅ `/api/categories` (GET)
- ⚠️ **STRUCTURE PROBLEMATIQUE**: Frontend confond "Activities" (événements) et "UserActivities" (préférences)
  
**Frontend activity.api.ts appelle**:
- ❌ `GET /activities` - Liste d'événements/activités réelles (activity.api.ts:29)
- ❌ `GET /activities/{activityId}` - Détail d'une activité (activity.api.ts:36)
- ❌ `GET /users/{userId}/activities` - Activités d'un user (activity.api.ts:44)
- ❌ `POST /activities` - Créer une activité (activity.api.ts:62)
- ❌ `PATCH /activities/{activityId}` - Modifier une activité (activity.api.ts:67)
- ❌ `DELETE /activities/{activityId}` - Supprimer une activité (activity.api.ts:72)
- ❌ `POST /activities/{activityId}/like` - Liker (activity.api.ts:77)
- ❌ `DELETE /activities/{activityId}/like` - Unlike (activity.api.ts:86)
- ❌ `POST /activities/{activityId}/favorite` - Favoriser (activity.api.ts:93)
- ❌ `DELETE /activities/{activityId}/favorite` - Défavoriser (activity.api.ts:100)
- ❌ `POST /activities/{activityId}/photos` - Upload photos (activity.api.ts:107)

**Backend ActivityController a**:
- ✅ `GET /api/activities` - Recherche de categories (ActivityController.java:30-38)
- ✅ `GET /api/users/me/activities` - Mes préférences d'activités (ActivityController.java:40-44)
- ✅ `POST /api/users/me/activities` - Ajouter préférence (ActivityController.java:46-52)
- ✅ `PUT /api/users/me/activities/{userActivityId}` - Modifier préférence (ActivityController.java:54-60)
- ✅ `DELETE /api/users/me/activities/{userActivityId}` - Supprimer préférence (ActivityController.java:62-68)
- ✅ `PATCH /api/users/me/activities/{userActivityId}/visibility` - Toggle visibilité (ActivityController.java:70-77)

**Frontend category.api.ts appelle correctement**:
- ✅ `GET /categories` (category.api.ts:27)
- ❌ `GET /categories/{categoryId}` (category.api.ts:32) - Backend ne l'a pas
- ❌ `GET /categories?search=query` (category.api.ts:37) - Backend ne l'a pas
- ✅ `GET /users/me/activities` (category.api.ts:44)
- ✅ `POST /users/me/activities` (category.api.ts:49)
- ✅ `PUT /users/me/activities/{userActivityId}` (category.api.ts:57)
- ✅ `DELETE /users/me/activities/{userActivityId}` (category.api.ts:65)

**DÉCISION ARCHITECTURE REQUISE**: Clarifier si "Activity" = événement réel ou préférence profil

### Badge API
- ✅ `/api/badges` (GET) - Liste tous les badges (BadgeController.java:28-33)
- ✅ `/api/badges/me` (GET) - Mes badges (BadgeController.java:35-43)
- ✅ `/api/badges/users/{userId}` (GET) - Badges d'un user (BadgeController.java:45-52)
- ❌ `/api/badges/{badgeId}/progress` (GET) - Frontend badge.api.ts:223 l'appelle mais backend ne l'a pas
- ❌ `/api/badges/{badgeId}/claim` (POST) - Frontend badge.api.ts:244 l'appelle mais backend a `/badges/me/evaluate`

### Chat API
- ✅ `/api/conversations` (POST) - Créer conversation (ChatController.java:28-35)
- ✅ `/api/conversations` (GET) - Liste conversations (ChatController.java:37-42)
- ✅ `/api/conversations/{conversationId}/messages` (POST) - Envoyer message (ChatController.java:44-52)
- ✅ `/api/conversations/{conversationId}/messages` (GET) - Récupérer messages (ChatController.java:54-61)
- ✅ `/api/conversations/{conversationId}/read` (POST) - Marquer comme lu (ChatController.java:63-70)
- ❌ `/api/conversations/{conversationId}` (GET) - Frontend chat.api.ts:30 l'appelle mais backend ne l'a pas
- ❌ `/api/conversations/{conversationId}` (DELETE) - Frontend chat.api.ts:40 l'appelle mais backend ne l'a pas
- ❌ `/api/messages/{messageId}` (PATCH) - Frontend chat.api.ts:70 pour éditer mais backend ne l'a pas
- ❌ `/api/messages/{messageId}` (DELETE) - Frontend chat.api.ts:75 l'appelle mais backend ne l'a pas
- ❌ `/api/conversations/{conversationId}/read-all` (POST) - Frontend chat.api.ts:89 l'appelle mais backend ne l'a pas
- ❌ `/api/conversations/{conversationId}/images` (POST) - Frontend chat.api.ts:95 l'appelle mais backend ne l'a pas

### Program API
- ✅ `/api/programs` (GET) - Backend retourne myPrograms (ProgramController.java:32-36)
- ✅ `/api/programs` (POST) - Créer (ProgramController.java:24-30)
- ✅ `/api/programs/{programId}` (GET) - Détail (ProgramController.java:44-49)
- ✅ `/api/programs/{programId}` (PUT) - Modifier (ProgramController.java:51-57)
- ✅ `/api/programs/{programId}` (DELETE) - Supprimer (ProgramController.java:59-65)
- ✅ `/api/programs/{programId}/schedules` (POST) - Ajouter schedule (ProgramController.java:67-74)
- ✅ `/api/programs/{programId}/schedules/{scheduleId}` (PUT) - Modifier schedule (ProgramController.java:76-83)
- ✅ `/api/programs/{programId}/schedules/{scheduleId}` (DELETE) - Supprimer schedule (ProgramController.java:85-92)

**Frontend program.api.ts appelle des endpoints manquants**:
- ⚠️ `GET /programs` avec filtres - Backend retourne seulement myPrograms, pas une liste filtrée publique
- ❌ `PATCH /programs/drafts/{draftId}` (program.api.ts:60) - Backend ne gère pas les drafts
- ❌ `POST /programs/{programId}/join` (program.api.ts:75)
- ❌ `POST /programs/{programId}/leave` (program.api.ts:85)
- ❌ `POST /programs/{programId}/report` (program.api.ts:96)
- ❌ `GET /users/me/programs` (program.api.ts:112) - Conflit avec GET /programs
- ❌ `POST /users/me/programs` (program.api.ts:119) - Enrollment
- ❌ `PATCH /users/me/programs/{userProgramId}` (program.api.ts:127) - Progress update
- ❌ `DELETE /users/me/programs/{userProgramId}` (program.api.ts:135) - Unenroll
- ❌ `POST /users/me/programs/{userProgramId}/activities/{activityId}/complete` (program.api.ts:144)
- ❌ `POST /users/me/programs/{userProgramId}/activities/{activityId}/skip` (program.api.ts:152)
- ❌ `GET /programs/{programId}/reviews` (program.api.ts:162)
- ❌ `POST /programs/{programId}/reviews` (program.api.ts:173)
- ❌ `PATCH /programs/reviews/{reviewId}` (program.api.ts:184)
- ❌ `DELETE /programs/reviews/{reviewId}` (program.api.ts:189)

### Map API
- ✅ `/api/map/users` (GET) - MapController.java:22-27
- ❌ Tous les autres endpoints map du frontend map.api.ts ne sont pas implémentés:
  - `GET /map/users/bounds` (map.api.ts:34)
  - `GET /map/activities/bounds` (map.api.ts:54)
  - `GET /map/programs/bounds` (map.api.ts:74)
  - `GET /map/markers` (map.api.ts:99)
  - `GET /map/clusters` (map.api.ts:123)
  - `GET /map/nearby/{type}` (map.api.ts:147)
  - `GET /map/geocode` (map.api.ts:159)
  - `GET /map/reverse-geocode` (map.api.ts:167)
  - `POST /map/location` (map.api.ts:175) - Utiliser `/users/me/location` à la place

### Search API
- ✅ `/api/search` (POST) - SearchController.java:40-44
- ❌ Frontend search.api.ts appelle des endpoints manquants:
  - `GET /search/tags` (search.api.ts:117)
  - `GET /search/tags/popular` (search.api.ts:127)
  - `GET /search/popular` (search.api.ts:137)
  - `GET /search/recent` (search.api.ts:144)
  - `DELETE /search/recent` (search.api.ts:149)

### Notification API
- ✅ `/api/notifications` (GET) - NotificationController.java:34-46
- ✅ `/api/notifications/unread-count` (GET) - NotificationController.java:48-55
- ✅ `/api/notifications/{id}/read` (PUT) - NotificationController.java:57-65
- ✅ `/api/notifications/read-all` (PUT) - NotificationController.java:67-74
- ✅ `/api/notifications/{id}` (DELETE) - NotificationController.java:76-84
- ✅ `/api/notifications/preferences` (GET) - NotificationController.java:86-93
- ✅ `/api/notifications/preferences` (PUT) - NotificationController.java:95-110
- ✅ `/api/notifications/devices` (POST) - NotificationController.java:112-126
- ✅ `/api/notifications/devices/{token}` (DELETE) - NotificationController.java:128-133
- ✅ `/api/notifications/devices` (GET) - NotificationController.java:135-142
- ❌ `/api/notifications/read` (DELETE) - Frontend notification.api.ts:59 pour deleteAllRead
- ⚠️ Frontend notification.api.ts utilise `/notifications/push/register` et `/push/unregister` (lignes 78-90) mais backend utilise `/notifications/devices`

### Settings API
- ✅ `/api/notifications/preferences` (GET/PUT)
- ✅ `/api/notifications/devices` (GET)
- ✅ `/api/notifications/devices/{deviceId}` (DELETE)
- ✅ `/api/gdpr/export` (GET) - GdprController.java:31-40
- ⚠️ `/api/gdpr/delete-account` (DELETE) - GdprController.java:46-58
  - Frontend settings.api.ts:62 envoie `{data}` dans body mais backend ne l'attend pas
- ❌ `/api/users/me/privacy` (GET/PUT) - Frontend settings.api.ts:31-40 mais backend ne l'a pas
- ❌ `/api/users/me/change-password` (POST) - Frontend settings.api.ts:70-77 mais backend ne l'a pas

### Media API
- ✅ `/api/media/upload/image` (POST) - MediaController.java:30-63
- ✅ `/api/media/upload/avatar` (POST) - MediaController.java:65-85
- ✅ `/api/media/files/**` (GET) - MediaController.java:87-105
- ✅ `/api/media/files/**` (DELETE) - MediaController.java:107-118

## 📊 Résumé par Priorité

### 🔴 PRIORITÉ CRITIQUE - Bugs bloquants

1. **Auth verify-email**: Frontend POST vs Backend GET
2. **Auth password reset**: Noms d'endpoints non alignés
3. **Activity structure**: Confusion Activities vs UserActivities
4. **Program join/leave/enroll**: Fonctionnalités core manquantes
5. **Notification push endpoints**: `/push/register` vs `/devices`

### 🟡 PRIORITÉ HAUTE - Fonctionnalités importantes

6. Chat message edit/delete
7. Map bounds et clustering
8. Program reviews (CRUD complet)
9. User search endpoint
10. Privacy settings endpoints
11. Change password endpoint

### 🟢 PRIORITÉ MOYENNE - Améliorations UX

12. Badge progress et claim
13. Search tags et popular
14. Categories search et detail
15. Notification deleteAllRead
16. Chat conversation detail et delete
17. Chat images upload

### 🔵 PRIORITÉ BASSE - Nice to have

18. Activity likes/favorites
19. Activity photos upload
20. Map geocoding
21. Program drafts

## 🔧 Actions Recommandées

### Corrections Immédiates (Sprint actuel)

1. **AuthController & Frontend auth.api.ts**
   - Modifier frontend `verifyEmail` pour utiliser GET avec query param
   - Renommer `resetPassword` → `forgotPassword` et `confirmResetPassword` → `resetPassword`

2. **NotificationController & Frontend notification.api.ts**
   - Unifier: soit tout en `/devices`, soit tout en `/push/*`
   - Recommandation: garder `/devices` (plus RESTful)

3. **ActivityController**
   - Décider: "Activity" = événement réel ou préférence?
   - Si événement: créer nouveau controller `EventController`
   - Si préférence: renommer frontend `activity.api.ts` → `category.api.ts`

### Développement Sprint +1

4. **ProgramController**
   - Ajouter `/programs/{id}/join` (POST)
   - Ajouter `/programs/{id}/leave` (POST)
   - Ajouter `/users/me/programs` (GET) avec filtres
   - Ajouter `/users/me/programs/{id}` (PATCH/DELETE)

5. **ChatController**
   - Ajouter `/conversations/{id}` (GET/DELETE)
   - Ajouter `/messages/{id}` (PATCH/DELETE)

6. **UserController**
   - Ajouter `/users/me/privacy` (GET/PUT)
   - Ajouter `/users/me/change-password` (POST)
   - Ajouter `/users` (GET) avec search

### Développement Sprint +2

7. **MapController**
   - Implémenter bounds/clustering/geocoding

8. **ReviewController** (nouveau)
   - Créer controller pour program reviews

9. **SearchController**
   - Ajouter tags/popular/recent endpoints

## 📝 Conventions à Adopter

### Naming
- Ressources au pluriel: `/users`, `/programs`, `/activities`
- Actions: POST (create), GET (read), PUT/PATCH (update), DELETE (delete)
- Sous-ressources: `/programs/{id}/schedules`, `/users/{id}/activities`
- Actions custom: POST `/programs/{id}/join`, POST `/notifications/read-all`

### Réponses
- Success: `200 OK`, `201 Created`, `204 No Content`
- Erreurs: `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`, `404 Not Found`, `500 Internal Server Error`
- Body JSON standard: `{message, data, errors?}`

### Documentation
- Swagger/OpenAPI sur tous les endpoints
- Tests de contrats API (Pact ou Spring Cloud Contract)
- Documentation dans `docs/api/`

## ✅ Checklist Validation

- [ ] Tous les endpoints frontend ont un équivalent backend
- [ ] Les méthodes HTTP correspondent (GET/POST/PUT/PATCH/DELETE)
- [ ] Les noms de paramètres sont identiques (camelCase frontend, snake_case ou camelCase backend)
- [ ] Les structures de réponse sont documentées
- [ ] Les codes d'erreur sont cohérents
- [ ] Tests d'intégration frontend ↔ backend en place
- [ ] Documentation OpenAPI à jour
