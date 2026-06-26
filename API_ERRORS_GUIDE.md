# 📋 Guide des Erreurs API Courantes

## 1. Erreur de Validation: Coordonnées Manquantes

### ❌ Erreur observée
```
MethodArgumentNotValidException: Validation failed
- Field error: lat: rejected value [null]; default message [La latitude est requise]
- Field error: lng: rejected value [null]; default message [La longitude est requise]
```

### Cause
L'endpoint `/api/search` nécessite des coordonnées géographiques (`lat` et `lng`) pour effectuer une recherche basée sur la localisation.

### ✅ Requête correcte

```bash
curl -X POST http://localhost:8090/api/search \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "yoga paris",
    "lat": 48.8566,
    "lng": 2.3522,
    "radiusMeters": 5000
  }'
```

### Exemple Frontend (JavaScript)

```javascript
// Obtenir la position de l'utilisateur
navigator.geolocation.getCurrentPosition(
  async (position) => {
    const response = await fetch('http://localhost:8090/api/search', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${accessToken}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        query: 'yoga',
        lat: position.coords.latitude,
        lng: position.coords.longitude,
        radiusMeters: 5000  // optionnel, 5km par défaut
      })
    });
    
    const results = await response.json();
    console.log(results);
  },
  (error) => {
    console.error('Geolocation error:', error);
    // Utiliser des coordonnées par défaut (ex: Paris)
    const defaultLat = 48.8566;
    const defaultLng = 2.3522;
  }
);
```

### Champs du SearchRequest

| Champ | Type | Requis | Description |
|-------|------|--------|-------------|
| `query` | String | ✅ Oui | Texte de recherche (max 500 caractères) |
| `lat` | Double | ✅ Oui | Latitude (ex: 48.8566 pour Paris) |
| `lng` | Double | ✅ Oui | Longitude (ex: 2.3522 pour Paris) |
| `radiusMeters` | Integer | ❌ Non | Rayon de recherche en mètres (par défaut: détecté par IA) |

---

## 2. Erreur WebSocket: No static resource ws

### ❌ Erreur observée
```
NoResourceFoundException: No static resource ws for request '/ws'
```

### Cause
Tentative d'accès HTTP GET sur `/ws` au lieu d'une connexion WebSocket sur `/ws/chat`.

### ✅ Solution
Voir le document détaillé: [WEBSOCKET_FIX.md](./WEBSOCKET_FIX.md)

**Résumé rapide**:
- ❌ Mauvais: `http://localhost:8090/ws`
- ✅ Correct: `ws://localhost:8090/ws/chat`

---

## 3. Erreur d'Authentification: Token Manquant ou Invalide

### ❌ Erreurs possibles
```json
{
  "code": "INVALID_TOKEN",
  "message": "Token invalide ou expiré",
  "timestamp": "2026-06-25T17:15:00Z"
}
```

### ✅ Solution

```javascript
// Vérifier que le token est présent
const token = localStorage.getItem('pair_access_token');

if (!token) {
  // Rediriger vers la page de login
  window.location.href = '/login';
  return;
}

// Ajouter le token dans toutes les requêtes
const response = await fetch('http://localhost:8090/api/users/me', {
  headers: {
    'Authorization': `Bearer ${token}`
  }
});

if (response.status === 401) {
  // Token expiré, essayer de refresh
  const refreshToken = localStorage.getItem('pair_refresh_token');
  const refreshResponse = await fetch('http://localhost:8090/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken })
  });
  
  if (refreshResponse.ok) {
    const data = await refreshResponse.json();
    localStorage.setItem('pair_access_token', data.accessToken);
    // Réessayer la requête originale
  } else {
    // Refresh échoué, déconnecter l'utilisateur
    localStorage.clear();
    window.location.href = '/login';
  }
}
```

---

## 4. Erreur CORS

### ❌ Erreur dans la console du navigateur
```
Access to fetch at 'http://localhost:8090/api/...' from origin 'http://localhost:3000' 
has been blocked by CORS policy: No 'Access-Control-Allow-Origin' header is present
```

### ✅ Solution
Le backend est déjà configuré pour accepter les origines `localhost`. Vérifiez:

1. **Port correct**: Utilisez `http://localhost:8090` (pas 8080)
2. **Headers requis**: Le backend accepte déjà les headers standards
3. **Credentials**: Si vous utilisez des cookies, ajoutez:

```javascript
fetch('http://localhost:8090/api/...', {
  credentials: 'include',  // Envoyer les cookies
  // ...
});
```

---

## 5. Erreur de Port Incorrect

### ❌ Erreur
```
Failed to fetch
// ou
CSRF token not found
// ou
Connection refused
```

### ✅ Solution
Vérifiez que vous utilisez le bon port:

```javascript
// ❌ MAUVAIS (port Jenkins)
const API_URL = 'http://localhost:8080/api';

// ✅ CORRECT (port Spring Boot)
const API_URL = 'http://localhost:8090/api';
```

**Rappel**: 
- Port **8080** = Jenkins/Jetty (erreur!)
- Port **8090** = Application Pair Spring Boot ✅

---

## 6. Format de Données Invalide

### ❌ Erreur
```json
{
  "code": "VALIDATION_ERROR",
  "message": "email : must be a well-formed email address",
  "timestamp": "2026-06-25T17:15:00Z"
}
```

### ✅ Solution
Vérifiez le format des données envoyées:

```javascript
// Format d'inscription
{
  "email": "user@example.com",      // Email valide
  "password": "SecureP@ss123",      // Min 8 caractères, 1 majuscule, 1 minuscule, 1 chiffre, 1 caractère spécial
  "displayName": "John Doe"         // Non vide
}

// Format de connexion
{
  "email": "user@example.com",      // Email valide
  "password": "SecureP@ss123"       // Mot de passe
}
```

---

## Checklist de Débogage

Quand une requête API échoue:

1. ✅ Vérifier le **port** (8090, pas 8080)
2. ✅ Vérifier que le **serveur est démarré** (`curl http://localhost:8090/actuator/health`)
3. ✅ Vérifier le **token d'authentification** (présent et valide)
4. ✅ Vérifier le **format de la requête** (JSON valide)
5. ✅ Vérifier les **champs requis** (consulter la doc API)
6. ✅ Regarder les **logs du serveur** pour plus de détails
7. ✅ Utiliser les **DevTools du navigateur** (onglet Network)

---

## Endpoints Publics (pas d'authentification requise)

- `POST /api/auth/register` - Inscription
- `POST /api/auth/login` - Connexion
- `POST /api/auth/refresh` - Refresh token
- `GET /api/auth/verify-email` - Vérification email
- `GET /api/categories` - Liste des catégories
- `GET /api/activities` - Liste des activités
- `GET /actuator/health` - Health check

## Endpoints Authentifiés (token requis)

Tous les autres endpoints nécessitent un token JWT valide dans le header:
```
Authorization: Bearer <votre_token>
```

---

Date: 2026-06-25
Auteur: System
