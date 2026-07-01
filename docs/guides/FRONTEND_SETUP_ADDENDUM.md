# 📝 FRONTEND_SETUP - Addendum (Mis à jour 2026-06-24)

## ⚠️ Mises à Jour Importantes

Ce document complète `FRONTEND_SETUP.md` avec les changements récents.

---

## ✅ Configuration CORS (Mise à Jour)

### Configuration Actuelle du Backend

La configuration CORS est **déjà active** dans `SecurityConfig.java`:

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    // Origines autorisées (wildcard patterns)
    configuration.setAllowedOriginPatterns(Arrays.asList(
        "http://localhost:*",           // Tous ports localhost
        "http://127.0.0.1:*",           // Alias localhost
        "http://192.168.*.*:*",         // Réseau local
        "http://10.*.*.*:*",            // Réseaux privés
        "https://localhost:*",          // HTTPS local
        "https://*.pair.app"            // Production
    ));

    // Méthodes HTTP autorisées
    configuration.setAllowedMethods(Arrays.asList(
        "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    ));

    // Headers autorisés
    configuration.setAllowedHeaders(Arrays.asList(
        "Authorization",
        "Content-Type",
        "Accept",
        "Origin",
        "X-Requested-With",
        "Access-Control-Request-Method",
        "Access-Control-Request-Headers"
    ));

    // Headers exposés
    configuration.setExposedHeaders(Arrays.asList(
        "Authorization",
        "Content-Disposition"
    ));

    // Credentials autorisés (JWT)
    configuration.setAllowCredentials(true);

    // Cache preflight 1h
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);
    return source;
}
```

### Ce Qui Est Automatiquement Autorisé

✅ **React** (localhost:3000, localhost:5173)  
✅ **Vue** (localhost:8080, localhost:5173)  
✅ **Angular** (localhost:4200)  
✅ **Réseau local** (192.168.x.x:*)  
✅ **N'importe quel port** en localhost  

**Vous n'avez RIEN à configurer** pour CORS en développement!

---

## 🔥 Services Optionnels (Phase 4)

### Firebase (Notifications Push)

**Status**: ✅ **Désactivé par défaut**

Firebase n'est **pas requis** pour faire fonctionner l'application.

**Si vous voulez activer Firebase**:
1. Obtenir credentials Firebase
2. Configurer dans `application.properties`:
```properties
firebase.enabled=true
firebase.credentials-path=classpath:firebase-service-account.json
```
3. Voir `FIREBASE_FIX.md` pour les détails

**Sans Firebase**:
- ✅ L'application fonctionne normalement
- ✅ Notifications in-app fonctionnent
- ✅ Notifications email fonctionnent
- ❌ Notifications push désactivées

---

### Redis (Cache & Rate Limiting)

**Status**: ✅ **Désactivé par défaut**

Redis n'est **pas requis** pour faire fonctionner l'application.

**Si vous voulez activer Redis**:
1. Installer Redis:
```bash
docker run -d --name pair-redis -p 6379:6379 redis:7-alpine
```

2. Décommenter dans `pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

3. Recompiler:
```bash
mvn clean compile
mvn spring-boot:run
```

4. Voir `REDIS_FIX.md` pour les détails

**Sans Redis**:
- ✅ L'application fonctionne normalement
- ✅ Cache in-memory (JVM)
- ✅ Rate limiting in-memory
- ❌ Cache partagé entre instances

---

## 🔐 Authentification (Mise à Jour)

### Erreur Courante: 403 Forbidden

Si vous obtenez une erreur `403` sur `/api/conversations` ou d'autres endpoints:

**Ce n'est PAS une erreur CORS**, c'est une **erreur d'authentification**.

### Solution

Tous les endpoints (sauf `/api/auth/*`) nécessitent un JWT:

```javascript
// 1. S'inscrire ou se connecter
const response = await fetch('http://localhost:8090/api/auth/register', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    email: 'test@example.com',
    password: 'password123',
    displayName: 'Test User'
  })
});

const { accessToken } = await response.json();

// 2. Utiliser le token pour tous les appels
const conversations = await fetch('http://localhost:8090/api/conversations', {
  headers: {
    'Authorization': `Bearer ${accessToken}`,
    'Content-Type': 'application/json'
  }
});
```

**Guide complet**: Voir `AUTHENTICATION_GUIDE.md`

---

## 🌐 Endpoints Publics (Sans Authentification)

Ces endpoints sont **accessibles sans JWT**:

```
GET  /api/categories                    ✅ Public
GET  /api/activities                    ✅ Public
POST /api/auth/register                 ✅ Public
POST /api/auth/login                    ✅ Public
POST /api/auth/refresh                  ✅ Public
GET  /api/auth/verify-email             ✅ Public
POST /api/auth/forgot-password          ✅ Public
POST /api/auth/reset-password           ✅ Public
GET  /actuator/health                   ✅ Public
GET  /swagger-ui/**                     ✅ Public

Tous les autres endpoints                ❌ Nécessitent JWT
```

---

## 🚀 Démarrage Rapide Mis à Jour

### Prérequis Minimum

```bash
# 1. PostgreSQL (requis)
docker start pair-postgres

# 2. Application Spring Boot
mvn spring-boot:run

# ✅ C'est tout!
# Firebase et Redis sont OPTIONNELS
```

### Test de Connexion

```bash
# Test endpoint public
curl http://localhost:8090/api/categories

# Devrait retourner:
# [{"id":"...","name":"Sport",...}, ...]
```

Si ça fonctionne, votre backend est prêt! ✅

---

## 🔧 Configuration Frontend Axios (Mise à Jour)

### Configuration Complète avec Gestion d'Erreurs

```javascript
import axios from 'axios';

const api = axios.create({
  baseURL: 'http://localhost:8090/api',
  timeout: 10000,
  withCredentials: true,  // Important pour CORS
  headers: {
    'Content-Type': 'application/json'
  }
});

// Intercepteur pour JWT
api.interceptors.request.use(
  config => {
    const token = localStorage.getItem('pair_access_token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  error => Promise.reject(error)
);

// Intercepteur pour refresh token
api.interceptors.response.use(
  response => response,
  async error => {
    const originalRequest = error.config;

    // Token expiré
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
        // Refresh token invalide, rediriger vers login
        localStorage.clear();
        window.location.href = '/login';
        return Promise.reject(refreshError);
      }
    }

    return Promise.reject(error);
  }
);

export default api;
```

---

## 🐛 Troubleshooting Mis à Jour

### Problème 1: "CORS policy: No 'Access-Control-Allow-Origin'"

**Status**: ✅ **Déjà résolu dans le backend**

Si vous voyez cette erreur:
1. Vérifier que le backend est démarré: `curl http://localhost:8090/api/categories`
2. Vérifier qu'il n'y a pas d'autre instance sur le port 8090: `netstat -ano | findstr ":8090"`
3. Redémarrer le backend

**La configuration CORS est déjà active**, ce n'est normalement pas un problème.

---

### Problème 2: "403 Forbidden" ou "strict-origin-when-cross-origin"

**Cause**: Authentification manquante

**Solution**:
1. Vérifier que vous avez un token JWT
2. Vérifier le format: `Authorization: Bearer <token>`
3. S'inscrire ou se connecter si nécessaire

**Voir**: `AUTHENTICATION_GUIDE.md`

---

### Problème 3: "RedisConnectionFailureException"

**Status**: ✅ **Déjà résolu**

Redis est désactivé par défaut. Si vous voyez cette erreur:
1. Vérifier que la dépendance Redis est commentée dans `pom.xml`
2. Recompiler: `mvn clean compile`

**Voir**: `REDIS_FIX.md`

---

### Problème 4: "Unable to connect to Redis" ou Firebase errors

**Status**: ✅ **Normal et attendu**

Ces services sont **optionnels** (Phase 4). L'application fonctionne sans eux.

**Vous pouvez ignorer ces warnings** dans les logs.

---

## 📚 Documentation Complète

### Guides Disponibles

| Document | Description |
|----------|-------------|
| `FRONTEND_SETUP.md` | Guide principal (745 lignes) |
| `FRONTEND_SETUP_ADDENDUM.md` | Ce document (mises à jour) |
| `AUTHENTICATION_GUIDE.md` | Guide authentification JWT |
| `CORS_FIX.md` | Configuration CORS détaillée |
| `FIREBASE_FIX.md` | Firebase optionnel |
| `REDIS_FIX.md` | Redis optionnel |
| `api-endpoints.md` | 52 endpoints documentés |
| `frontend-config.json` | Configuration complète |
| `COMMANDES_UTILES.md` | Commandes backend |

---

## ✅ Checklist Mise à Jour

### Backend

- [x] PostgreSQL démarré
- [x] Application Spring Boot démarrée
- [x] CORS configuré automatiquement
- [x] JWT fonctionnel
- [ ] Firebase (optionnel Phase 4)
- [ ] Redis (optionnel Phase 4)

### Frontend

- [ ] Fichier `frontend-config.json` copié
- [ ] Service API créé avec Axios/Fetch
- [ ] Intercepteur JWT configuré
- [ ] Gestion refresh token implémentée
- [ ] Gestion erreurs 401/403 implémentée
- [ ] WebSocket configuré (si chat)

### Tests

- [ ] Test endpoint public: `GET /api/categories` ✅
- [ ] Test inscription: `POST /api/auth/register` ✅
- [ ] Test login: `POST /api/auth/login` ✅
- [ ] Test endpoint authentifié: `GET /api/conversations` avec JWT ✅

---

## 🎯 Configuration Minimale pour Démarrer

```bash
# Backend
docker start pair-postgres
mvn spring-boot:run

# Frontend (exemple React)
npm install axios
# Copier frontend-config.json
# Créer le service API avec intercepteurs
# C'est tout!
```

**Pas besoin de**:
- ❌ Configuration CORS (déjà fait)
- ❌ Firebase (optionnel)
- ❌ Redis (optionnel)
- ❌ Proxy reverse (CORS géré)

---

## 🚀 Prêt à Développer!

**L'application backend est**:
- ✅ Production-ready
- ✅ CORS configuré
- ✅ JWT fonctionnel
- ✅ 52 endpoints disponibles
- ✅ WebSocket opérationnel
- ✅ Documentation complète

**Vous pouvez maintenant**:
1. Créer votre application React/Vue/Angular
2. Utiliser `frontend-config.json` pour les endpoints
3. Implémenter l'authentification JWT
4. Appeler l'API sans problème de CORS

**Bon développement!** 🎉

---

**Date**: 2026-06-24  
**Version**: 1.1.0  
**Complète**: `FRONTEND_SETUP.md`  
**Status**: ✅ Production Ready
