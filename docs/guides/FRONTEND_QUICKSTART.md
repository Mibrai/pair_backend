# 🚀 Frontend Quick Start - Pair Application

## 📋 TL;DR

```bash
# Backend
docker start pair-postgres && mvn spring-boot:run

# Frontend
npm install axios
# Utiliser frontend-config.json
# Ajouter JWT dans headers
# C'est tout! ✅
```

---

## 🌐 URLs de l'API

```
API REST:   http://localhost:8090/api
WebSocket:  ws://localhost:8090/ws
```

**CORS**: ✅ Déjà configuré pour localhost:*

---

## 🔐 Authentification Rapide

### 1. S'inscrire

```javascript
const response = await fetch('http://localhost:8090/api/auth/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    email: 'test@example.com',
    password: 'password123',
    displayName: 'Test User'
  })
});

const { accessToken, refreshToken } = await response.json();
localStorage.setItem('pair_access_token', accessToken);
localStorage.setItem('pair_refresh_token', refreshToken);
```

### 2. Appeler l'API

```javascript
const token = localStorage.getItem('pair_access_token');

const conversations = await fetch('http://localhost:8090/api/conversations', {
  headers: {
    'Authorization': `Bearer ${token}`,
    'Content-Type': 'application/json'
  }
});
```

**C'est tout!** ✅

---

## 📦 Configuration Axios (Recommandé)

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8090/api',
  timeout: 10000,
  withCredentials: true
});

// Ajouter JWT automatiquement
api.interceptors.request.use(config => {
  const token = localStorage.getItem('pair_access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Gestion refresh token
api.interceptors.response.use(
  response => response,
  async error => {
    if (error.response?.status === 401 && !error.config._retry) {
      error.config._retry = true;
      const refreshToken = localStorage.getItem('pair_refresh_token');
      const res = await axios.post('http://localhost:8090/api/auth/refresh', 
        { refreshToken });
      localStorage.setItem('pair_access_token', res.data.accessToken);
      error.config.headers.Authorization = `Bearer ${res.data.accessToken}`;
      return api(error.config);
    }
    return Promise.reject(error);
  }
);

export default api;
```

---

## 🎯 Endpoints Principaux

### Sans JWT (Public)
```
GET  /api/categories
GET  /api/activities
POST /api/auth/register
POST /api/auth/login
```

### Avec JWT (Authentifié)
```
GET  /api/conversations
GET  /api/users/me
GET  /api/users/me/activities
GET  /api/programs
GET  /api/map/users
POST /api/search
```

**Liste complète**: Voir `api-endpoints.md` (52 endpoints)

---

## 💬 WebSocket (Chat)

```javascript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

const token = localStorage.getItem('pair_access_token');

const client = new Client({
  webSocketFactory: () => new SockJS('http://localhost:8090/ws/chat'),
  connectHeaders: {
    Authorization: `Bearer ${token}`
  },
  onConnect: () => {
    // S'abonner aux messages
    client.subscribe(`/user/${userId}/queue/messages`, message => {
      const data = JSON.parse(message.body);
      console.log('Message reçu:', data);
    });
  }
});

client.activate();

// Envoyer un message
client.publish({
  destination: '/app/chat.send',
  body: JSON.stringify({
    conversationId: 'uuid',
    content: 'Hello!'
  })
});
```

---

## 🐛 Erreurs Courantes

### ❌ 403 Forbidden

**Cause**: JWT manquant ou invalide

**Solution**: Ajouter `Authorization: Bearer <token>`

---

### ❌ CORS Error

**Cause**: Backend pas démarré

**Solution**: `mvn spring-boot:run`

---

### ❌ Connection Refused

**Cause**: Mauvais port ou backend arrêté

**Solution**: Vérifier `http://localhost:8090/api/categories`

---

## 📁 Fichiers de Config

```
frontend-config.json          ← Tous les endpoints
frontend-config.local.json    ← Pour réseau local
.env.example                  ← Template variables
```

---

## 📚 Documentation Complète

| Fichier | Contenu |
|---------|---------|
| `FRONTEND_SETUP.md` | Guide complet 745 lignes |
| `FRONTEND_SETUP_ADDENDUM.md` | Mises à jour 2026-06-24 |
| `AUTHENTICATION_GUIDE.md` | JWT détaillé |
| `api-endpoints.md` | 52 endpoints |
| `CORS_FIX.md` | Config CORS |

---

## ✅ Prêt en 5 Minutes

```bash
# 1. Backend
mvn spring-boot:run

# 2. Frontend (React exemple)
npx create-react-app pair-frontend
cd pair-frontend
npm install axios

# 3. Copier config
cp ../frontend-config.json src/config/

# 4. Créer service API (voir code Axios ci-dessus)

# 5. Tester
curl http://localhost:8090/api/categories
```

**Bon développement!** 🎉

---

**Version**: 1.0.0  
**Date**: 2026-06-24  
**Status**: ✅ Production Ready
