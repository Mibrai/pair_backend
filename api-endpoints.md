# 🌐 API Endpoints - Pair Application

**Base URL**: `http://localhost:8090/api`  
**WebSocket URL**: `ws://localhost:8090/ws`

---

## 🔐 Authentication (Public)

### POST `/auth/register`
Créer un nouveau compte utilisateur.

**Request**:
```json
{
  "email": "user@example.com",
  "password": "securePassword123",
  "displayName": "John Doe"
}
```

**Response** (201):
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "displayName": "John Doe",
  "verificationStatus": "UNVERIFIED"
}
```

---

### POST `/auth/login`
Se connecter avec email et mot de passe.

**Request**:
```json
{
  "email": "user@example.com",
  "password": "securePassword123"
}
```

**Response** (200):
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "displayName": "John Doe",
  "verificationStatus": "VERIFIED"
}
```

---

### POST `/auth/refresh`
Renouveler le token d'accès.

**Request**:
```json
{
  "refreshToken": "eyJhbGciOiJIUzI1NiIs..."
}
```

**Response** (200):
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "displayName": "John Doe",
  "verificationStatus": "VERIFIED"
}
```

---

### GET `/auth/verify-email?token={token}`
Vérifier l'adresse email.

**Response** (200): `204 No Content`

---

### POST `/auth/forgot-password`
Demander un reset de mot de passe.

**Request**:
```json
{
  "email": "user@example.com"
}
```

**Response** (200): `200 OK` (toujours, même si email inexistant)

---

### POST `/auth/reset-password`
Réinitialiser le mot de passe.

**Request**:
```json
{
  "token": "reset_token_here",
  "newPassword": "newSecurePassword456"
}
```

**Response** (200): `200 OK`

---

## 👤 Users (Authenticated)

### GET `/users/me`
Obtenir mon profil complet.

**Headers**: `Authorization: Bearer {accessToken}`

**Response** (200):
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "email": "user@example.com",
  "phone": null,
  "displayName": "John Doe",
  "bio": "Passionné de yoga et trail running",
  "avatarUrl": "http://localhost:8090/uploads/avatars/123e4567.jpg",
  "lat": 48.8566,
  "lng": 2.3522,
  "blurRadiusM": 500,
  "locationPublic": true,
  "onlineStatusVisible": true,
  "receiveMessages": true,
  "verificationStatus": "VERIFIED",
  "createdAt": "2026-06-01T10:00:00Z",
  "activities": [...]
}
```

---

### PUT `/users/me`
Mettre à jour mon profil.

**Request**:
```json
{
  "displayName": "Jane Doe",
  "bio": "Yoga teacher & trail runner",
  "locationPublic": true,
  "onlineStatusVisible": true,
  "receiveMessages": true,
  "blurRadiusM": 1000
}
```

**Response** (200): User object

---

### PUT `/users/me/location`
Mettre à jour ma position GPS.

**Request**:
```json
{
  "lat": 48.8566,
  "lng": 2.3522
}
```

**Response** (200): `200 OK`

---

### POST `/users/me/avatar`
Upload avatar (multipart/form-data).

**Request**: `FormData` avec clé `file`

**Response** (200):
```json
{
  "url": "http://localhost:8090/uploads/avatars/123e4567.jpg"
}
```

---

### GET `/users/{id}`
Voir le profil public d'un utilisateur.

**Response** (200):
```json
{
  "id": "123e4567-e89b-12d3-a456-426614174000",
  "displayName": "John Doe",
  "bio": "Yoga enthusiast",
  "avatarUrl": "...",
  "verificationStatus": "VERIFIED",
  "badgeCodes": ["EARLY_ADOPTER"],
  "activities": [...],
  "isOnline": true
}
```

---

### DELETE `/users/me`
Désactiver mon compte (soft delete).

**Response** (204): `204 No Content`

---

## 🎯 Activities (Authenticated)

### GET `/categories`
Liste toutes les catégories d'activités.

**Response** (200):
```json
[
  {
    "id": "uuid",
    "name": "Sports & Fitness",
    "icon": "🏃",
    "colorRamp": "#FF5733"
  },
  ...
]
```

---

### GET `/activities?categoryId={uuid}&search={term}&page={n}&size={n}`
Rechercher des activités.

**Query Params**:
- `categoryId` (optional): UUID de la catégorie
- `search` (optional): Terme de recherche
- `page` (default: 0)
- `size` (default: 20, max: 50)

**Response** (200):
```json
{
  "content": [
    {
      "id": "uuid",
      "name": "Yoga Hatha",
      "slug": "yoga-hatha",
      "description": "...",
      "parentId": "uuid",
      "category": { "id": "uuid", "name": "Sports & Fitness", ... }
    }
  ],
  "totalElements": 42,
  "totalPages": 3,
  "number": 0,
  "size": 20
}
```

---

### GET `/users/me/activities`
Mes activités.

**Response** (200):
```json
[
  {
    "id": "uuid",
    "activity": { ... },
    "visibleOnMap": true,
    "customDescription": "Cours de yoga tous niveaux",
    "level": "INTERMEDIATE",
    "format": "IN_PERSON",
    "createdAt": "2026-06-01T10:00:00Z",
    "programs": [...]
  }
]
```

---

### POST `/users/me/activities`
Ajouter une activité à mon profil.

**Request**:
```json
{
  "activityId": "uuid",
  "visibleOnMap": true,
  "customDescription": "Cours de yoga pour débutants",
  "level": "BEGINNER",
  "format": "IN_PERSON"
}
```

**Response** (201): UserActivity object

---

### PUT `/users/me/activities/{userActivityId}`
Modifier une activité sur mon profil.

**Response** (200): UserActivity object

---

### DELETE `/users/me/activities/{userActivityId}`
Retirer une activité de mon profil.

**Response** (204): `204 No Content`

---

### PATCH `/users/me/activities/{userActivityId}/visibility`
Toggle la visibilité sur la carte.

**Request**:
```json
{
  "visible": false
}
```

**Response** (200): UserActivity object

---

## 📅 Programs (Authenticated)

### GET `/programs?userActivityId={uuid}&status={status}&page={n}&size={n}`
Mes programmes.

**Query Params**:
- `userActivityId` (optional)
- `status` (optional): DRAFT, PUBLISHED, ARCHIVED
- `page`, `size`

**Response** (200): Page of programs

---

### GET `/programs/{id}`
Détails d'un programme.

**Response** (200):
```json
{
  "id": "uuid",
  "title": "Yoga Matinal",
  "description": "Séances de yoga tous les matins",
  "status": "PUBLISHED",
  "isPublic": true,
  "createdAt": "...",
  "updatedAt": "...",
  "schedules": [...],
  "media": [...],
  "averageScore": 4.5,
  "reviewCount": 12
}
```

---

### POST `/programs`
Créer un programme.

**Request**:
```json
{
  "userActivityId": "uuid",
  "title": "Yoga du Matin",
  "description": "Cours de yoga relaxant",
  "isPublic": true
}
```

**Response** (201): Program object

---

### PUT `/programs/{id}`
Modifier un programme.

**Request**:
```json
{
  "title": "Nouveau titre",
  "description": "...",
  "status": "PUBLISHED",
  "isPublic": true
}
```

**Response** (200): Program object

---

### DELETE `/programs/{id}`
Archiver un programme (soft delete).

**Response** (204): `204 No Content`

---

### POST `/programs/{id}/schedules`
Ajouter un créneau.

**Request**:
```json
{
  "placeName": "Parc Monceau",
  "placeType": "PUBLIC",
  "lat": 48.8799,
  "lng": 2.3089,
  "addressPublic": "35 Boulevard de Courcelles, 75008 Paris",
  "showExactAddress": true,
  "startsAt": "2026-07-01T08:00:00Z",
  "endsAt": "2026-07-01T09:30:00Z",
  "recurrenceRule": "FREQ=WEEKLY;BYDAY=MO,WE,FR",
  "maxParticipants": 10
}
```

**Response** (201): Schedule object

---

### PUT `/programs/{id}/schedules/{scheduleId}`
Modifier un créneau.

**Response** (200): Schedule object

---

### DELETE `/programs/{id}/schedules/{scheduleId}`
Supprimer un créneau.

**Response** (204): `204 No Content`

---

## 🗺️ Map (Authenticated)

### GET `/map/users?lat={lat}&lng={lng}&radiusMeters={n}&activityId={uuid}`
Utilisateurs visibles sur la carte.

**Query Params**:
- `lat`, `lng` (required): Coordonnées GPS
- `radiusMeters` (required): Rayon de recherche (500-50000)
- `activityId` (optional): Filtrer par activité
- `level`, `format` (optional)

**Response** (200):
```json
[
  {
    "userId": "uuid",
    "displayName": "John Doe",
    "avatarUrl": "...",
    "lat": 48.8566,
    "lng": 2.3522,
    "isOnline": true,
    "visibleActivities": [
      {
        "activityId": "uuid",
        "activityName": "Yoga",
        "level": "INTERMEDIATE",
        "format": "IN_PERSON",
        "categoryColorRamp": "#FF5733"
      }
    ],
    "verificationStatus": "VERIFIED"
  }
]
```

---

## 💬 Chat (Authenticated)

### GET `/conversations`
Mes conversations.

**Response** (200):
```json
[
  {
    "id": "uuid",
    "type": "DIRECT",
    "otherUser": { ... },
    "activityContextName": "Yoga",
    "lastMessageContent": "Salut!",
    "lastMessageAt": "2026-06-20T15:30:00Z",
    "unreadCount": 2
  }
]
```

---

### POST `/conversations`
Créer une conversation.

**Request**:
```json
{
  "targetUserId": "uuid",
  "activityContextId": "uuid"
}
```

**Response** (201): Conversation object

---

### GET `/conversations/{id}/messages?before={timestamp}&size={n}`
Historique des messages.

**Query Params**:
- `before` (optional): Timestamp pour pagination
- `size` (default: 30, max: 50)

**Response** (200):
```json
[
  {
    "id": "uuid",
    "conversationId": "uuid",
    "senderId": "uuid",
    "senderName": "John Doe",
    "senderAvatarUrl": "...",
    "content": "Hello!",
    "status": "DELIVERED",
    "sentAt": "2026-06-20T15:30:00Z"
  }
]
```

---

### POST `/conversations/{id}/read`
Marquer comme lu.

**Response** (200): `200 OK`

---

## 🔍 Search (Authenticated - Phase 2)

### POST `/search`
Recherche intelligente multi-entités.

**Request**:
```json
{
  "query": "yoga paris débutant",
  "limit": 20
}
```

**Response** (200):
```json
{
  "query": "yoga paris débutant",
  "intent": {
    "detectedType": "ACTIVITY",
    "location": "paris",
    "level": "BEGINNER",
    "confidence": 0.85
  },
  "results": [
    {
      "type": "activity",
      "id": "uuid",
      "score": 0.92,
      "activity": { ... }
    },
    {
      "type": "user",
      "id": "uuid",
      "score": 0.78,
      "user": { ... }
    },
    {
      "type": "program",
      "id": "uuid",
      "score": 0.65,
      "program": { ... }
    }
  ],
  "totalResults": 15
}
```

---

## 📊 Progressions (Authenticated - Phase 2)

### GET `/progressions?userActivityId={uuid}&page={n}&size={n}`
Mes entrées de progression.

**Response** (200): Page of progressions

---

### POST `/progressions`
Créer une entrée de progression.

**Request**:
```json
{
  "userActivityId": "uuid",
  "title": "Première pose de headstand",
  "description": "Réussi à tenir 10 secondes",
  "metricType": "DURATION",
  "metricValue": 10.0,
  "metricUnit": "seconds",
  "mood": "EXCITED",
  "visibility": "PUBLIC"
}
```

**Response** (201): Progression object

---

## 📤 Media (Authenticated - Phase 2)

### POST `/media/upload`
Upload un fichier (multipart/form-data).

**Request**: FormData avec:
- `file`: Le fichier
- `entityType`: PROGRAM, PROGRESSION, MESSAGE
- `entityId`: UUID de l'entité

**Response** (201):
```json
{
  "id": "uuid",
  "fileName": "image.jpg",
  "fileType": "IMAGE",
  "contentType": "image/jpeg",
  "fileSize": 245678,
  "storagePath": "/uploads/programs/uuid/image.jpg",
  "entityType": "PROGRAM",
  "entityId": "uuid",
  "uploadedAt": "2026-06-20T16:00:00Z"
}
```

---

### GET `/media/{id}`
Télécharger un fichier.

**Response** (200): Fichier binaire

---

### DELETE `/media/{id}`
Supprimer un fichier.

**Response** (204): `204 No Content`

---

## 🔌 WebSocket (Authenticated)

### Connection
```
Endpoint: ws://localhost:8090/ws/chat
Protocol: STOMP over SockJS
```

### Authentication
Envoyer le token JWT dans le header `Authorization` lors du CONNECT:
```
CONNECT
Authorization: Bearer {accessToken}

```

### Subscribe to Messages
```
SUBSCRIBE
destination: /user/{userId}/queue/messages
id: sub-0

```

### Send Message
```
SEND
destination: /app/chat.send
content-type: application/json

{"conversationId":"uuid","content":"Hello!"}
```

### Typing Indicator
```
SEND
destination: /app/chat.typing
content-type: application/json

{"conversationId":"uuid","targetUserId":"uuid"}
```

---

## 🚨 Error Responses

Toutes les erreurs suivent le format:
```json
{
  "code": "ERROR_CODE",
  "message": "Human readable message",
  "timestamp": "2026-06-20T16:00:00Z"
}
```

### Common Error Codes

| Code | HTTP | Description |
|------|------|-------------|
| VALIDATION_ERROR | 400 | Données invalides |
| INVALID_CREDENTIALS | 401 | Email/password incorrect |
| UNAUTHORIZED | 401 | Token manquant/invalide |
| FORBIDDEN | 403 | Accès refusé |
| NOT_FOUND | 404 | Ressource introuvable |
| EMAIL_ALREADY_EXISTS | 409 | Email déjà utilisé |
| RATE_LIMITED | 429 | Trop de requêtes |
| INTERNAL_ERROR | 500 | Erreur serveur |

---

## 📝 Notes

### Pagination
Paramètres standard:
- `page`: Numéro de page (commence à 0)
- `size`: Taille de page (max 50)

Réponse:
```json
{
  "content": [...],
  "totalElements": 100,
  "totalPages": 5,
  "number": 0,
  "size": 20,
  "first": true,
  "last": false
}
```

### Authentication
Toutes les routes nécessitent un JWT sauf `/auth/*`.

Header requis:
```
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

### Rate Limiting
- Login: 10 tentatives / 15 min
- Register: 5 comptes / heure / IP
- Password reset: 3 demandes / heure / IP

### File Upload Limits
- Avatar: 5 MB max (JPEG, PNG, WebP)
- Program media: 10 MB max (JPEG, PNG, WebP, PDF, MP4)

---

**API Documentation v1.0** - Dernière mise à jour: 2026-06-24
