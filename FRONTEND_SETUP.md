# 🎨 Configuration Frontend - Pair Application

## 📋 Vue d'ensemble

Ce document explique comment configurer un client frontend (React, Vue, Angular, etc.) pour se connecter à l'API Pair.

---

## 🌐 URLs de l'API

### Accès Local (sur la même machine)
```
API REST: http://localhost:8090/api
WebSocket: ws://localhost:8090/ws
```

### Accès Réseau Local (depuis un autre appareil)
```
API REST: http://192.168.2.47:8090/api
WebSocket: ws://192.168.2.47:8090/ws
```

**Note**: Remplacez `192.168.2.47` par votre adresse IP locale. Pour la trouver:
```bash
# Windows
ipconfig

# Mac/Linux
ifconfig
```

---

## 📁 Fichiers de Configuration Disponibles

### 1. `frontend-config.json` ✅
Configuration complète avec tous les endpoints et paramètres.

**Usage**:
```javascript
// React/Vue/Angular
import config from './frontend-config.json';

const API_BASE = config.apiBaseUrl;
const WS_BASE = config.wsBaseUrl;
```

### 2. `frontend-config.local.json` ✅
Configuration pour accès réseau local (autres appareils).

### 3. `.env.example` ✅
Template de variables d'environnement.

**Setup**:
```bash
# Copier le template
cp .env.example .env

# Éditer avec vos valeurs
nano .env
```

---

## 🚀 Démarrage Rapide

### Étape 1: Démarrer le Backend

```bash
# Terminal 1 - Démarrer PostgreSQL (si Docker)
docker start pair-postgres

# Terminal 2 - Démarrer l'application Spring Boot
mvn spring-boot:run

# Vérifier que l'API est accessible
curl http://localhost:8090/api/categories
```

### Étape 2: Configurer le Frontend

#### Option A: React (Create React App / Vite)

```bash
# Copier la config
cp frontend-config.json src/config/api.json

# Copier .env
cp .env.example .env

# Installer axios ou fetch
npm install axios
```

**Exemple `src/config/api.js`**:
```javascript
import configJson from './api.json';

const config = {
  baseURL: process.env.REACT_APP_API_BASE_URL || configJson.apiBaseUrl,
  wsURL: process.env.REACT_APP_WS_BASE_URL || configJson.wsBaseUrl,
  endpoints: configJson.endpoints,
  ...configJson
};

export default config;
```

**Exemple `src/services/api.js`**:
```javascript
import axios from 'axios';
import config from '../config/api';

const api = axios.create({
  baseURL: config.baseURL,
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json'
  }
});

// Intercepteur pour ajouter le JWT
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('pair_access_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// Intercepteur pour gérer le refresh token
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const originalRequest = error.config;
    
    if (error.response?.status === 401 && !originalRequest._retry) {
      originalRequest._retry = true;
      
      try {
        const refreshToken = localStorage.getItem('pair_refresh_token');
        const response = await axios.post(
          `${config.baseURL}/auth/refresh`,
          { refreshToken }
        );
        
        const { accessToken } = response.data;
        localStorage.setItem('pair_access_token', accessToken);
        
        originalRequest.headers.Authorization = `Bearer ${accessToken}`;
        return api(originalRequest);
      } catch (refreshError) {
        // Rediriger vers login
        localStorage.removeItem('pair_access_token');
        localStorage.removeItem('pair_refresh_token');
        window.location.href = '/login';
      }
    }
    
    return Promise.reject(error);
  }
);

export default api;
```

#### Option B: Vue 3

```bash
# Copier la config
cp frontend-config.json src/config/api.json
cp .env.example .env

npm install axios
```

**`src/plugins/axios.js`**:
```javascript
import axios from 'axios';
import config from '@/config/api.json';

const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || config.apiBaseUrl,
  timeout: 10000
});

// Intercepteurs similaires à React...

export default apiClient;
```

#### Option C: Angular

```bash
# Copier la config
cp frontend-config.json src/assets/config/api.json

# Le .env n'est pas utilisé directement, utiliser environment.ts
```

**`src/environments/environment.ts`**:
```typescript
export const environment = {
  production: false,
  apiBaseUrl: 'http://localhost:8090/api',
  wsBaseUrl: 'ws://localhost:8090/ws'
};
```

**`src/app/services/api.service.ts`**:
```typescript
import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private baseUrl = environment.apiBaseUrl;

  constructor(private http: HttpClient) {}

  private getHeaders(): HttpHeaders {
    const token = localStorage.getItem('pair_access_token');
    return new HttpHeaders({
      'Content-Type': 'application/json',
      'Authorization': token ? `Bearer ${token}` : ''
    });
  }

  get<T>(endpoint: string) {
    return this.http.get<T>(`${this.baseUrl}${endpoint}`, {
      headers: this.getHeaders()
    });
  }

  post<T>(endpoint: string, data: any) {
    return this.http.post<T>(`${this.baseUrl}${endpoint}`, data, {
      headers: this.getHeaders()
    });
  }
}
```

---

## 🔐 Authentification

### Flow d'authentification

```javascript
import api from './services/api';
import config from './config/api';

// 1. Inscription
async function register(email, password, displayName) {
  const response = await api.post(config.endpoints.auth.register, {
    email,
    password,
    displayName
  });
  
  const { accessToken, refreshToken, userId } = response.data;
  
  // Stocker les tokens
  localStorage.setItem('pair_access_token', accessToken);
  localStorage.setItem('pair_refresh_token', refreshToken);
  localStorage.setItem('pair_user_id', userId);
  
  return response.data;
}

// 2. Connexion
async function login(email, password) {
  const response = await api.post(config.endpoints.auth.login, {
    email,
    password
  });
  
  const { accessToken, refreshToken, userId } = response.data;
  
  localStorage.setItem('pair_access_token', accessToken);
  localStorage.setItem('pair_refresh_token', refreshToken);
  localStorage.setItem('pair_user_id', userId);
  
  return response.data;
}

// 3. Déconnexion
function logout() {
  localStorage.removeItem('pair_access_token');
  localStorage.removeItem('pair_refresh_token');
  localStorage.removeItem('pair_user_id');
  
  // Rediriger vers login
  window.location.href = '/login';
}

// 4. Vérifier si authentifié
function isAuthenticated() {
  return !!localStorage.getItem('pair_access_token');
}

// 5. Obtenir le profil utilisateur
async function getMyProfile() {
  const response = await api.get(config.endpoints.users.me);
  return response.data;
}
```

---

## 💬 WebSocket (Chat en Temps Réel)

### Configuration STOMP.js

```bash
npm install @stomp/stompjs sockjs-client
```

**`src/services/chat.js`**:
```javascript
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import config from '../config/api';

class ChatService {
  constructor() {
    this.client = null;
    this.connected = false;
  }

  connect(accessToken) {
    return new Promise((resolve, reject) => {
      this.client = new Client({
        webSocketFactory: () => new SockJS(config.wsBaseUrl + '/chat'),
        connectHeaders: {
          Authorization: `Bearer ${accessToken}`
        },
        debug: (str) => {
          console.log('[STOMP]', str);
        },
        onConnect: () => {
          this.connected = true;
          console.log('WebSocket connected');
          resolve();
        },
        onStompError: (frame) => {
          console.error('STOMP error', frame);
          reject(frame);
        }
      });

      this.client.activate();
    });
  }

  subscribeToMessages(userId, callback) {
    if (!this.connected) {
      throw new Error('WebSocket not connected');
    }

    return this.client.subscribe(`/user/${userId}/queue/messages`, (message) => {
      const data = JSON.parse(message.body);
      callback(data);
    });
  }

  sendMessage(conversationId, content) {
    if (!this.connected) {
      throw new Error('WebSocket not connected');
    }

    this.client.publish({
      destination: '/app/chat.send',
      body: JSON.stringify({
        conversationId,
        content
      })
    });
  }

  disconnect() {
    if (this.client) {
      this.client.deactivate();
      this.connected = false;
    }
  }
}

export default new ChatService();
```

**Usage dans un composant React**:
```javascript
import { useEffect, useState } from 'react';
import chatService from '../services/chat';

function ChatComponent({ userId }) {
  const [messages, setMessages] = useState([]);

  useEffect(() => {
    const token = localStorage.getItem('pair_access_token');
    
    // Connexion WebSocket
    chatService.connect(token).then(() => {
      // S'abonner aux messages
      const subscription = chatService.subscribeToMessages(userId, (message) => {
        setMessages(prev => [...prev, message]);
      });

      return () => {
        subscription.unsubscribe();
        chatService.disconnect();
      };
    });
  }, [userId]);

  const handleSendMessage = (conversationId, content) => {
    chatService.sendMessage(conversationId, content);
  };

  return (
    <div>
      {messages.map(msg => (
        <div key={msg.id}>{msg.content}</div>
      ))}
    </div>
  );
}
```

---

## 🗺️ Carte Interactive

### Exemple avec Leaflet

```bash
npm install leaflet react-leaflet
```

```javascript
import { MapContainer, TileLayer, Marker, Popup } from 'react-leaflet';
import { useEffect, useState } from 'react';
import api from '../services/api';
import config from '../config/api';

function MapView() {
  const [users, setUsers] = useState([]);
  const [center, setCenter] = useState([
    config.map.defaultCenter.lat,
    config.map.defaultCenter.lng
  ]);

  useEffect(() => {
    // Obtenir la position de l'utilisateur
    navigator.geolocation.getCurrentPosition((position) => {
      const lat = position.coords.latitude;
      const lng = position.coords.longitude;
      setCenter([lat, lng]);

      // Rechercher des utilisateurs à proximité
      searchNearbyUsers(lat, lng);
    });
  }, []);

  const searchNearbyUsers = async (lat, lng) => {
    try {
      const response = await api.get(config.endpoints.map.users, {
        params: {
          lat,
          lng,
          radiusMeters: config.map.defaultRadiusMeters
        }
      });
      setUsers(response.data);
    } catch (error) {
      console.error('Error fetching users:', error);
    }
  };

  return (
    <MapContainer center={center} zoom={config.map.defaultZoom} style={{ height: '600px' }}>
      <TileLayer
        url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        attribution='&copy; OpenStreetMap contributors'
      />
      {users.map(user => (
        <Marker key={user.userId} position={[user.lat, user.lng]}>
          <Popup>
            <div>
              <h3>{user.displayName}</h3>
              <p>{user.visibleActivities.map(a => a.activityName).join(', ')}</p>
            </div>
          </Popup>
        </Marker>
      ))}
    </MapContainer>
  );
}
```

---

## 📤 Upload de Fichiers

```javascript
import api from './api';
import config from '../config/api';

async function uploadAvatar(file) {
  // Vérifier le type et la taille
  const allowedTypes = config.upload.allowedTypes.avatar;
  if (!allowedTypes.includes(file.type)) {
    throw new Error('Type de fichier non autorisé');
  }

  if (file.size > config.upload.maxFileSize) {
    throw new Error('Fichier trop volumineux (max 10MB)');
  }

  // Créer le FormData
  const formData = new FormData();
  formData.append('file', file);

  // Upload
  const response = await api.post(
    config.endpoints.users.uploadAvatar,
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  );

  return response.data.url;
}

async function uploadProgramMedia(programId, file) {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('entityType', 'PROGRAM');
  formData.append('entityId', programId);

  const response = await api.post(
    config.endpoints.media.upload,
    formData,
    {
      headers: {
        'Content-Type': 'multipart/form-data'
      }
    }
  );

  return response.data;
}
```

---

## 🔍 Recherche Intelligente

```javascript
import api from './api';
import config from '../config/api';
import { debounce } from 'lodash';

// Recherche avec debounce
const searchDebounced = debounce(async (query, callback) => {
  if (query.length < config.search.minQueryLength) {
    callback([]);
    return;
  }

  try {
    const response = await api.post(config.endpoints.search.query, {
      query,
      limit: config.search.maxResults
    });

    callback(response.data.results);
  } catch (error) {
    console.error('Search error:', error);
    callback([]);
  }
}, config.search.debounceMs);

// Usage dans un composant
function SearchBox() {
  const [results, setResults] = useState([]);
  const [query, setQuery] = useState('');

  const handleSearch = (e) => {
    const value = e.target.value;
    setQuery(value);
    searchDebounced(value, setResults);
  };

  return (
    <div>
      <input
        type="text"
        value={query}
        onChange={handleSearch}
        placeholder="Rechercher des activités..."
      />
      <ul>
        {results.map(result => (
          <li key={result.id}>
            {result.type === 'activity' && result.activity.name}
            {result.type === 'user' && result.user.displayName}
          </li>
        ))}
      </ul>
    </div>
  );
}
```

---

## 🧪 Tester la Connexion

### Test basique avec cURL

```bash
# 1. Inscription
curl -X POST http://localhost:8090/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123",
    "displayName": "Test User"
  }'

# 2. Connexion
curl -X POST http://localhost:8090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "test@example.com",
    "password": "password123"
  }'

# 3. Obtenir le profil (remplacer TOKEN)
curl -X GET http://localhost:8090/api/users/me \
  -H "Authorization: Bearer TOKEN"

# 4. Rechercher des activités
curl -X GET "http://localhost:8090/api/activities?search=yoga" \
  -H "Authorization: Bearer TOKEN"
```

### Test avec Postman/Insomnia

1. Importer la collection depuis `frontend-config.json`
2. Créer une variable d'environnement `baseUrl` = `http://localhost:8090/api`
3. Tester les endpoints d'authentification
4. Utiliser le token reçu dans les requêtes suivantes

---

## 🐛 Résolution de Problèmes

### CORS Errors

Si vous voyez des erreurs CORS dans la console:

1. Vérifier que le backend autorise votre origine:
```java
// SecurityConfig.java devrait avoir:
.cors(cors -> cors.configurationSource(request -> {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(
        "http://localhost:3000",
        "http://localhost:5173",
        "http://192.168.2.47:3000"
    ));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    return config;
}))
```

### WebSocket Connection Failed

1. Vérifier que le backend est démarré
2. Vérifier l'URL WebSocket (`ws://` et non `wss://` en dev)
3. Vérifier que le token JWT est valide
4. Regarder les logs du backend

### API Timeouts

1. Augmenter le timeout axios:
```javascript
const api = axios.create({
  baseURL: config.baseURL,
  timeout: 30000  // 30 secondes
});
```

2. Vérifier que PostgreSQL est démarré
3. Vérifier les logs Spring Boot

---

## 📚 Ressources

### Documentation API Complète
- Endpoints: Voir `frontend-config.json`
- Specs Phase 1: `src/main/resources/memories/pair-phase1-spec.md`
- Specs Phase 2: `src/main/resources/memories/pair-phase2-spec.md`

### Exemples de Code
- Scripts de test: `test-*.sh` (montrent les payloads requis)
- Postman collection: À créer depuis `frontend-config.json`

### Support
- Issues: Créer un ticket avec les logs backend + frontend
- Logs backend: `logs/pair.log`

---

## ✅ Checklist de Configuration

- [ ] Backend Spring Boot démarré sur port 8090
- [ ] PostgreSQL démarré et accessible
- [ ] Fichier `frontend-config.json` copié dans le projet frontend
- [ ] Variables d'environnement configurées (`.env`)
- [ ] Service API créé avec axios/fetch
- [ ] Intercepteurs JWT configurés
- [ ] Test de connexion réussi (`/api/auth/register`)
- [ ] WebSocket connecté (si chat nécessaire)
- [ ] Gestion des erreurs implémentée
- [ ] Loading states implémentés

---

**Configuration prête! Le frontend peut maintenant communiquer avec l'API Pair.** 🚀
