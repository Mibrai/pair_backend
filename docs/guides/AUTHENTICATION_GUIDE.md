# 🔐 Guide d'Authentification API - Pair

## 🚨 Erreur: "403 strict-origin-when-cross-origin"

### Explication de l'Erreur

L'erreur **403 Forbidden** avec le message "strict-origin-when-cross-origin" signifie que:

1. ✅ **CORS est configuré** (sinon ce serait une autre erreur)
2. ❌ **Authentification manquante** (pas de token JWT)
3. ❌ L'endpoint nécessite un utilisateur connecté

**Ce n'est PAS une erreur CORS**, mais une **erreur d'authentification**.

---

## 🎯 Solution Rapide

### Endpoint Correct

❌ **Mauvais**: `http://localhost:8090/api/chat/conversations`  
✅ **Correct**: `http://localhost:8090/api/conversations`

### Authentification Requise

Tous les endpoints (sauf `/api/auth/*`) nécessitent un **token JWT**:

```bash
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

---

## 📋 Étapes pour Tester l'API

### Méthode 1: Avec cURL (Recommandé)

#### 1. S'inscrire

```bash
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "displayName": "Test User"
  }'
```

**Réponse**:
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "userId": "123e4567-e89b-12d3-a456-426614174000",
  "displayName": "Test User",
  "verificationStatus": "UNVERIFIED"
}
```

**Copier le `accessToken`** pour les prochaines requêtes.

#### 2. Lister les Conversations

```bash
curl -X GET http://localhost:8090/api/conversations \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIs..."
```

**Réponse**:
```json
[]
```
(Vide car aucune conversation créée)

#### 3. Créer une Conversation

```bash
# D'abord créer un second utilisateur
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "user2@example.com",
    "password": "password123",
    "displayName": "User 2"
  }'

# Copier le userId de User 2, puis:
curl -X POST http://localhost:8090/api/conversations \
  -H "Authorization: Bearer <VOTRE_TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{
    "targetUserId": "<USER2_ID>"
  }'
```

---

### Méthode 2: Script Automatisé

**Utiliser le script de test**:

```bash
bash test-conversations.sh
```

Ce script:
- ✅ Crée 2 utilisateurs de test
- ✅ Obtient automatiquement les tokens JWT
- ✅ Crée une conversation
- ✅ Envoie un message
- ✅ Liste les conversations
- ✅ Teste tous les endpoints

---

### Méthode 3: Depuis le Navigateur

#### Étape 1: Obtenir un Token

Ouvrir la Console JavaScript (F12) et exécuter:

```javascript
fetch('http://localhost:8090/api/auth/register', {
  method: 'POST',
  headers: {
    'Content-Type': 'application/json'
  },
  body: JSON.stringify({
    email: 'browser_test@example.com',
    password: 'password123',
    displayName: 'Browser Test'
  })
})
.then(res => res.json())
.then(data => {
  console.log('Token:', data.accessToken);
  localStorage.setItem('pair_token', data.accessToken);
  return data;
});
```

**Copier le token** affiché dans la console.

#### Étape 2: Appeler l'API avec le Token

```javascript
const token = localStorage.getItem('pair_token');

fetch('http://localhost:8090/api/conversations', {
  method: 'GET',
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  }
})
.then(res => res.json())
.then(data => console.log('Conversations:', data));
```

**Résultat**: ✅ Liste des conversations (vide au début)

---

### Méthode 4: Avec Postman/Insomnia

#### Configuration Postman

1. **Créer une nouvelle requête**
   - Method: `POST`
   - URL: `http://localhost:8090/api/auth/register`

2. **Body** (JSON):
```json
{
  "email": "postman@example.com",
  "password": "password123",
  "displayName": "Postman User"
}
```

3. **Send** → Copier le `accessToken`

4. **Nouvelle requête GET**
   - URL: `http://localhost:8090/api/conversations`
   - Headers:
     - Key: `Authorization`
     - Value: `Bearer eyJhbGciOiJIUzI1NiIs...`

5. **Send** → ✅ Devrait fonctionner

---

## 🛠️ Endpoints Chat Disponibles

### 1. Lister les Conversations

```http
GET /api/conversations
Authorization: Bearer <token>
```

**Réponse**:
```json
[
  {
    "id": "uuid",
    "type": "DIRECT",
    "otherUser": {
      "id": "uuid",
      "displayName": "John Doe",
      "avatarUrl": "..."
    },
    "lastMessageContent": "Hello!",
    "lastMessageAt": "2026-06-24T19:00:00Z",
    "unreadCount": 2
  }
]
```

---

### 2. Créer une Conversation

```http
POST /api/conversations
Authorization: Bearer <token>
Content-Type: application/json

{
  "targetUserId": "uuid-of-other-user"
}
```

**Réponse**:
```json
{
  "id": "conversation-uuid",
  "type": "DIRECT",
  "otherUser": { ... }
}
```

---

### 3. Envoyer un Message

```http
POST /api/conversations/{conversationId}/messages
Authorization: Bearer <token>
Content-Type: application/json

{
  "conversationId": "conversation-uuid",
  "content": "Hello, world!"
}
```

**Réponse**:
```json
{
  "id": "message-uuid",
  "conversationId": "conversation-uuid",
  "senderId": "your-user-id",
  "senderName": "Your Name",
  "content": "Hello, world!",
  "status": "SENT",
  "sentAt": "2026-06-24T19:00:00Z"
}
```

---

### 4. Récupérer les Messages

```http
GET /api/conversations/{conversationId}/messages?limit=50
Authorization: Bearer <token>
```

**Réponse**:
```json
[
  {
    "id": "message-uuid",
    "content": "Hello!",
    "senderId": "uuid",
    "senderName": "John",
    "sentAt": "2026-06-24T19:00:00Z"
  }
]
```

---

### 5. Marquer comme Lu

```http
POST /api/conversations/{conversationId}/read
Authorization: Bearer <token>
```

**Réponse**: `204 No Content`

---

## ❌ Erreurs Courantes

### Erreur 1: 403 Forbidden

**Symptôme**:
```json
{
  "timestamp": "2026-06-24T19:00:00Z",
  "status": 403,
  "error": "Forbidden"
}
```

**Causes**:
- ❌ Pas de header `Authorization`
- ❌ Token JWT manquant
- ❌ Token JWT invalide ou expiré
- ❌ Format incorrect (doit être `Bearer <token>`)

**Solution**:
1. Vérifier que le header `Authorization` est présent
2. Vérifier le format: `Bearer eyJhbGciOi...`
3. S'inscrire/se connecter pour obtenir un nouveau token

---

### Erreur 2: 401 Unauthorized

**Symptôme**:
```json
{
  "code": "INVALID_CREDENTIALS",
  "message": "Identifiants invalides."
}
```

**Cause**: Email ou mot de passe incorrect lors du login

**Solution**: Vérifier les credentials ou s'inscrire

---

### Erreur 3: 404 Not Found

**Symptôme**:
```
Cannot GET /api/chat/conversations
```

**Cause**: Mauvais endpoint

**Solution**: Utiliser `/api/conversations` (pas `/api/chat/conversations`)

---

### Erreur 4: CORS Error (dans le navigateur)

**Symptôme**:
```
Access to fetch at 'http://localhost:8090/api/conversations' from origin 'http://localhost:3000'
has been blocked by CORS policy
```

**Cause**: Application Spring Boot pas démarrée ou CORS mal configuré

**Solution**:
1. Démarrer l'application: `mvn spring-boot:run`
2. Vérifier que CORS est configuré (déjà fait)

---

### Erreur 5: Token Expiré

**Symptôme**:
```json
{
  "code": "INVALID_TOKEN",
  "message": "Token expiré"
}
```

**Cause**: Le JWT access token expire après 15 minutes

**Solution**: Utiliser le refresh token

```bash
curl -X POST http://localhost:8090/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzI1NiIs..."
  }'
```

---

## 🔐 Cycle d'Authentification Complet

### 1. Inscription

```javascript
const register = async () => {
  const response = await fetch('http://localhost:8090/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      email: 'user@example.com',
      password: 'password123',
      displayName: 'User Name'
    })
  });

  const data = await response.json();
  
  // Sauvegarder les tokens
  localStorage.setItem('pair_access_token', data.accessToken);
  localStorage.setItem('pair_refresh_token', data.refreshToken);
  localStorage.setItem('pair_user_id', data.userId);
  
  return data;
};
```

### 2. Appel API Authentifié

```javascript
const getConversations = async () => {
  const token = localStorage.getItem('pair_access_token');
  
  const response = await fetch('http://localhost:8090/api/conversations', {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    }
  });

  if (response.status === 401) {
    // Token expiré, utiliser le refresh token
    await refreshToken();
    return getConversations(); // Réessayer
  }

  return response.json();
};
```

### 3. Refresh Token

```javascript
const refreshToken = async () => {
  const refreshToken = localStorage.getItem('pair_refresh_token');
  
  const response = await fetch('http://localhost:8090/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken })
  });

  if (!response.ok) {
    // Refresh token invalide, rediriger vers login
    localStorage.clear();
    window.location.href = '/login';
    return;
  }

  const data = await response.json();
  
  // Mettre à jour les tokens
  localStorage.setItem('pair_access_token', data.accessToken);
  localStorage.setItem('pair_refresh_token', data.refreshToken);
  
  return data;
};
```

### 4. Déconnexion

```javascript
const logout = () => {
  localStorage.removeItem('pair_access_token');
  localStorage.removeItem('pair_refresh_token');
  localStorage.removeItem('pair_user_id');
  
  window.location.href = '/login';
};
```

---

## 📊 Checklist de Test

### Backend

- [ ] Application Spring Boot démarrée sur port 8090
- [ ] Database PostgreSQL accessible
- [ ] CORS configuré (déjà fait)
- [ ] Pas d'erreurs dans les logs

### Authentification

- [ ] Endpoint `/api/auth/register` fonctionne
- [ ] Token JWT reçu dans la réponse
- [ ] Token a le format correct (`eyJhbGciOi...`)
- [ ] Token contient le userId

### Appels API

- [ ] Header `Authorization: Bearer <token>` présent
- [ ] Endpoint correct: `/api/conversations` (pas `/api/chat/...`)
- [ ] Pas d'erreur 403 ou 401
- [ ] Réponse JSON valide

---

## 🎓 Comprendre les Codes HTTP

| Code | Signification | Cause | Solution |
|------|---------------|-------|----------|
| **200** | OK | Requête réussie | ✅ Tout va bien |
| **201** | Created | Ressource créée | ✅ Tout va bien |
| **204** | No Content | Succès sans contenu | ✅ Tout va bien |
| **400** | Bad Request | Données invalides | Vérifier le JSON |
| **401** | Unauthorized | Token manquant/invalide | Se connecter |
| **403** | Forbidden | Pas de permission | Vérifier le token |
| **404** | Not Found | Endpoint inexistant | Vérifier l'URL |
| **500** | Server Error | Erreur backend | Vérifier les logs |

---

## 📝 Exemples Frontend

### React avec Axios

```javascript
import axios from 'axios';

// Configuration Axios
const api = axios.create({
  baseURL: 'http://localhost:8090/api',
  headers: {
    'Content-Type': 'application/json'
  }
});

// Intercepteur pour ajouter le token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('pair_access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Intercepteur pour gérer le refresh token
api.interceptors.response.use(
  response => response,
  async error => {
    const originalRequest = error.config;
    
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;
      
      try {
        const refreshToken = localStorage.getItem('pair_refresh_token');
        const response = await axios.post(
          'http://localhost:8090/api/auth/refresh',
          { refreshToken }
        );
        
        const { accessToken } = response.data;
        localStorage.setItem('pair_access_token', accessToken);
        
        originalRequest.headers.Authorization = `Bearer ${accessToken}`;
        return api(originalRequest);
      } catch (refreshError) {
        // Rediriger vers login
        localStorage.clear();
        window.location.href = '/login';
      }
    }
    
    return Promise.reject(error);
  }
);

// Utilisation
export const getConversations = () => api.get('/conversations');
export const createConversation = (targetUserId) => 
  api.post('/conversations', { targetUserId });
export const sendMessage = (conversationId, content) => 
  api.post(`/conversations/${conversationId}/messages`, {
    conversationId,
    content
  });
```

### Vue 3 Composition API

```javascript
import { ref } from 'vue';
import axios from 'axios';

export function useConversations() {
  const conversations = ref([]);
  const loading = ref(false);
  const error = ref(null);

  const api = axios.create({
    baseURL: 'http://localhost:8090/api',
    headers: {
      'Authorization': `Bearer ${localStorage.getItem('pair_access_token')}`,
      'Content-Type': 'application/json'
    }
  });

  const fetchConversations = async () => {
    loading.value = true;
    error.value = null;
    
    try {
      const response = await api.get('/conversations');
      conversations.value = response.data;
    } catch (err) {
      error.value = err.response?.data?.message || 'Erreur de chargement';
      console.error('Error fetching conversations:', err);
    } finally {
      loading.value = false;
    }
  };

  return {
    conversations,
    loading,
    error,
    fetchConversations
  };
}
```

---

## ✅ Résumé

### Pour Tester l'API Chat

1. **Démarrer l'application**:
   ```bash
   mvn spring-boot:run
   ```

2. **S'inscrire** (obtenir un token):
   ```bash
   curl -X POST http://localhost:8090/api/auth/register \
     -H "Content-Type: application/json" \
     -d '{"email":"test@example.com","password":"password123","displayName":"Test"}'
   ```

3. **Utiliser le token** dans toutes les requêtes:
   ```bash
   curl -X GET http://localhost:8090/api/conversations \
     -H "Authorization: Bearer <VOTRE_TOKEN>"
   ```

### Points Clés

- ✅ Endpoint: `/api/conversations` (pas `/api/chat/conversations`)
- ✅ Header requis: `Authorization: Bearer <token>`
- ✅ Token obtenu via `/api/auth/register` ou `/api/auth/login`
- ✅ Token expire après 15 minutes (utiliser refresh token)
- ✅ CORS configuré pour `localhost:*`

---

**Date**: 2026-06-24  
**Version**: 1.0.0  
**Status**: ✅ Authentification fonctionnelle
