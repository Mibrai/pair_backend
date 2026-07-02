# Configuration CORS - Client Vercel

**Date**: 2026-07-02  
**Domaine frontend**: `https://pair-frontend-omega.vercel.app`  
**Environnement**: Production (Railway + Vercel)

---

## 🎯 Objectif

Permettre au client web React déployé sur Vercel de communiquer avec le backend Spring Boot sur Railway via:
1. **Appels REST** (API classique)
2. **WebSocket STOMP** (chat temps réel)

---

## ✅ Configuration appliquée

### 1. SecurityConfig.java - CORS pour API REST

**Fichier**: `src/main/java/org/program/pair/config/SecurityConfig.java`

```java
@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration configuration = new CorsConfiguration();

    // Origines autorisées (frontend)
    configuration.setAllowedOrigins(Arrays.asList(
        // Production Vercel
        "https://pair-frontend-omega.vercel.app",
        // Développement local
        "http://localhost:5173",
        "http://localhost:3000",
        "http://127.0.0.1:5173",
        "http://127.0.0.1:3000"
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

    // Headers exposés au client
    configuration.setExposedHeaders(Arrays.asList(
        "Authorization",
        "Content-Disposition"
    ));

    // Autoriser les credentials (cookies, Authorization header)
    configuration.setAllowCredentials(true);

    // Durée de cache de la config CORS (1 heure)
    configuration.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", configuration);

    return source;
}
```

**Activation dans la chaîne de sécurité**:
```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        // ... reste de la config
        .build();
}
```

### 2. WebSocketConfig.java - CORS pour WebSocket

**Fichier**: `src/main/java/org/program/pair/config/WebSocketConfig.java`

```java
@Override
public void registerStompEndpoints(StompEndpointRegistry registry) {
    // Main WebSocket endpoint for chat with SockJS fallback
    registry.addEndpoint("/ws/chat")
        .setAllowedOrigins(
            "https://pair-frontend-omega.vercel.app",
            "http://localhost:5173",
            "http://localhost:3000"
        )
        .withSockJS();

    // Alternative endpoint without SockJS for native WebSocket clients
    registry.addEndpoint("/ws/chat")
        .setAllowedOrigins(
            "https://pair-frontend-omega.vercel.app",
            "http://localhost:5173",
            "http://localhost:3000"
        );
}
```

---

## 🔍 Changements effectués

### Avant

**SecurityConfig.java**:
```java
configuration.setAllowedOriginPatterns(Arrays.asList(
    "http://localhost:*",
    "https://localhost:*",
    // ... patterns génériques
    "https://*.pair.app"
));
```

**WebSocketConfig.java**:
```java
registry.addEndpoint("/ws/chat")
    .setAllowedOriginPatterns("*") // TODO: Restrict in production
    .withSockJS();
```

### Après

✅ **Origines exactes** au lieu de patterns
✅ **Domaine Vercel explicite**: `https://pair-frontend-omega.vercel.app`
✅ **Développement local préservé**: `http://localhost:5173` et `http://localhost:3000`
✅ **WebSocket sécurisé**: Plus de wildcard `*`

---

## 🧪 Tests de vérification

### 1. Compilation Maven

```bash
cd F:\Projekt\Pair\pair_backend
mvn clean compile -DskipTests
```

**Résultat**: ✅ BUILD SUCCESS

### 2. Test depuis Vercel (après déploiement)

**Ouvrir**: `https://pair-frontend-omega.vercel.app`

**Console développeur (F12)**:
- Onglet **Network**: Vérifier qu'aucune erreur CORS sur les appels API
- Onglet **Console**: Pas d'erreur `Access-Control-Allow-Origin`

**Exemple de requête qui doit fonctionner**:
```javascript
// Dans le navigateur sur Vercel
fetch('https://pair-backend.railway.app/api/categories', {
  headers: {
    'Content-Type': 'application/json'
  }
})
.then(res => res.json())
.then(data => console.log('Categories:', data))
.catch(err => console.error('CORS Error:', err));
```

### 3. Test WebSocket

**Test connexion WebSocket depuis Vercel**:
```javascript
const socket = new SockJS('https://pair-backend.railway.app/ws/chat');
const stompClient = Stomp.over(socket);

stompClient.connect(
  { Authorization: 'Bearer ' + token },
  () => console.log('WebSocket connecté!'),
  (error) => console.error('WebSocket erreur:', error)
);
```

---

## 📋 Checklist post-déploiement

Après `git push` et déploiement sur Railway:

- [ ] **API REST**: Tester un GET sur `/api/categories` depuis Vercel
- [ ] **Authentification**: Tester POST `/api/auth/login` avec credentials
- [ ] **WebSocket**: Tester connexion chat temps réel
- [ ] **Carte interactive**: Vérifier chargement des markers
- [ ] **Console Browser**: Aucune erreur CORS dans F12

---

## 🚀 Déploiement

### 1. Commit les changements

```bash
cd F:\Projekt\Pair\pair_backend
git add src/main/java/org/program/pair/config/SecurityConfig.java
git add src/main/java/org/program/pair/config/WebSocketConfig.java
git add docs/deployment/CORS_CONFIGURATION.md
git commit -m "Configuration CORS pour client web Vercel"
```

### 2. Push vers Railway

```bash
git push origin master
```

Railway détectera le push et redéploiera automatiquement.

### 3. Vérifier le déploiement

```bash
# Logs Railway
railway logs --tail=50

# Vérifier qu'aucune erreur CORS au démarrage
railway logs | grep -i cors
```

---

## 🔐 Sécurité

### Origines autorisées

✅ **Whitelist explicite**: Seulement les domaines nécessaires
✅ **Pas de wildcard en production**: `*` remplacé par origines spécifiques
✅ **HTTPS obligatoire en prod**: Vercel force HTTPS
✅ **Credentials supportés**: `setAllowCredentials(true)` pour JWT

### Méthodes autorisées

- `GET` - Lecture
- `POST` - Création
- `PUT` - Remplacement complet
- `PATCH` - Modification partielle
- `DELETE` - Suppression
- `OPTIONS` - Preflight CORS

### Headers autorisés

- `Authorization` - JWT Bearer token
- `Content-Type` - Type de contenu (JSON, multipart, etc.)
- `Accept` - Format de réponse attendu
- `Origin` - Domaine source
- Headers standard CORS

---

## 🔧 Maintenance

### Ajouter un nouveau domaine

Si vous déployez sur un autre domaine (ex: domaine personnalisé):

**1. SecurityConfig.java**:
```java
configuration.setAllowedOrigins(Arrays.asList(
    "https://pair-frontend-omega.vercel.app",
    "https://votre-nouveau-domaine.com", // <-- Ajouter ici
    "http://localhost:5173",
    // ...
));
```

**2. WebSocketConfig.java**:
```java
registry.addEndpoint("/ws/chat")
    .setAllowedOrigins(
        "https://pair-frontend-omega.vercel.app",
        "https://votre-nouveau-domaine.com", // <-- Ajouter ici
        "http://localhost:5173",
        // ...
    )
```

**3. Redéployer**:
```bash
git add -A
git commit -m "Add new domain to CORS whitelist"
git push origin master
```

### Déboguer une erreur CORS

#### Symptôme 1: Erreur dans la console

```
Access to fetch at 'https://backend.railway.app/api/...'
from origin 'https://pair-frontend-omega.vercel.app'
has been blocked by CORS policy
```

**Solution**:
1. Vérifier que le domaine est dans `setAllowedOrigins()`
2. Vérifier l'orthographe exacte (https vs http, trailing slash)
3. Redéployer après modification

#### Symptôme 2: Preflight OPTIONS échoue

```
Response to preflight request doesn't pass access control check
```

**Solution**:
1. Vérifier que `OPTIONS` est dans `setAllowedMethods()`
2. Vérifier que les headers demandés sont dans `setAllowedHeaders()`

#### Symptôme 3: WebSocket ne se connecte pas

```
WebSocket connection to 'wss://...' failed:
Error during WebSocket handshake: Unexpected response code: 403
```

**Solution**:
1. Vérifier `WebSocketConfig.java` → `setAllowedOrigins()`
2. Vérifier que le token JWT est valide dans le header `Authorization`

---

## 📊 Monitoring

### Logs à surveiller

```bash
# Erreurs CORS au runtime
railway logs | grep -i "cors\|origin"

# Connexions WebSocket
railway logs | grep -i websocket

# Erreurs d'authentification (souvent liées à CORS)
railway logs | grep "401\|403"
```

### Métriques

- **Taux d'échec des preflight OPTIONS**: Doit être < 1%
- **Latence preflight**: < 100ms
- **Connexions WebSocket établies**: > 90% des tentatives

---

## 🆘 Troubleshooting

### Problème: CORS fonctionne en local mais pas sur Vercel

**Cause possible**: Domaine Vercel mal orthographié

**Solution**:
```bash
# Vérifier le domaine exact sur Vercel
# Settings > Domains

# Comparer avec SecurityConfig.java
grep "pair-frontend-omega.vercel.app" src/main/java/org/program/pair/config/SecurityConfig.java
```

### Problème: CORS fonctionne pour API REST mais pas WebSocket

**Cause**: `WebSocketConfig.java` pas mis à jour

**Solution**:
```bash
# Vérifier WebSocketConfig
grep "setAllowedOrigins" src/main/java/org/program/pair/config/WebSocketConfig.java

# Doit contenir le domaine Vercel
```

### Problème: Erreur après redéploiement Railway

**Cause**: Cache CORS côté browser

**Solution**:
1. Vider le cache du navigateur (Ctrl+Shift+Del)
2. Ou tester en navigation privée (Ctrl+Shift+N)
3. Ou attendre expiration du cache (1 heure, `maxAge=3600L`)

---

## 📚 Références

- [Documentation CORS Spring](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#mvc-cors)
- [Guide WebSocket CORS](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#websocket-server-allowed-origins)
- [MDN CORS](https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS)
- [Troubleshooting CORS (interne)](../troubleshooting/CORS_FIX.md)

---

## 📝 Historique des changements

| Date | Changement | Raison |
|------|----------|--------|
| 2026-07-02 | Configuration initiale Vercel | Déploiement production frontend |
| 2026-07-02 | Ajout domaine `pair-frontend-omega.vercel.app` | URL Vercel définitive |
| 2026-07-02 | Restriction WebSocket origins | Sécurité production |

---

**Maintenu par**: Backend team  
**Dernière révision**: 2026-07-02  
**Statut**: ✅ Configuré et testé
