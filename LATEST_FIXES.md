# 🔧 Dernières Corrections - 2026-06-25

## Problème Résolu: HttpRequestMethodNotSupportedException

### ❌ Erreur observée
```
HttpRequestMethodNotSupportedException: Request method 'POST' is not supported
```

### Cause
Le frontend essayait d'établir une connexion WebSocket via **POST /ws**, mais:
1. `/ws` n'est pas un vrai endpoint WebSocket (c'est juste informatif)
2. L'endpoint n'acceptait que GET

### ✅ Solutions appliquées

#### 1. Support de toutes les méthodes HTTP sur `/ws`

**Fichier modifié**: `src/main/java/org/program/pair/domain/websocket/WebSocketInfoController.java`

Maintenant l'endpoint accepte **GET, POST, PUT, DELETE, PATCH** et retourne toujours le même message informatif indiquant l'erreur et la bonne URL à utiliser.

```java
@GetMapping
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, Object> getWebSocketInfo() { ... }

@PostMapping
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, Object> postWebSocketInfo() { ... }

@RequestMapping(method = {RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
@ResponseStatus(HttpStatus.NOT_FOUND)
public Map<String, Object> otherMethods() { ... }
```

**Réponse** (Status: 404):
```json
{
  "error": "Invalid WebSocket endpoint",
  "message": "You are trying to connect to /ws which is not a valid WebSocket endpoint.",
  "correctEndpoint": {
    "url": "ws://localhost:8090/ws/chat",
    "protocol": "STOMP over WebSocket",
    "sockjs": true
  },
  "example": "Use SockJS client: new SockJS('http://localhost:8090/ws/chat')"
}
```

#### 2. Handler d'exception amélioré

**Fichier modifié**: `src/main/java/org/program/pair/shared/exception/GlobalExceptionHandler.java`

Ajout d'un handler spécifique pour `HttpRequestMethodNotSupportedException`:

```java
@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
@ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
public ErrorResponse handleMethodNotSupported(
    HttpRequestMethodNotSupportedException ex, 
    HttpServletRequest request) {
    
    String uri = request.getRequestURI();
    String method = request.getMethod();
    String supported = ex.getSupportedHttpMethods().toString();
    
    log.warn("Method not supported: {} {} (supported: {})", method, uri, supported);
    
    return new ErrorResponse(
        "METHOD_NOT_ALLOWED",
        String.format("HTTP %s not supported for %s. Supported methods: %s", 
                     method, uri, supported),
        Instant.now()
    );
}
```

## Impact

### Avant
```
❌ POST /ws → HttpRequestMethodNotSupportedException
❌ Logs pollués avec des stacktraces complètes
❌ Frontend confus sur l'erreur
```

### Après
```
✅ POST /ws → 404 avec message explicite
✅ Log simple et informatif
✅ Frontend reçoit les instructions correctes
```

## Configuration Frontend Correcte

### ❌ Incorrect
```javascript
// NE PAS FAIRE ÇA
fetch('http://localhost:8090/ws', {
  method: 'POST',
  body: JSON.stringify({...})
});

// NI ÇA
const ws = new WebSocket('ws://localhost:8090/ws');
```

### ✅ Correct
```javascript
// Option 1: Avec SockJS (recommandé)
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const socket = new SockJS('http://localhost:8090/ws/chat');
const stompClient = new Client({
  webSocketFactory: () => socket,
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`
  },
  onConnect: () => {
    console.log('✅ Connected!');
  },
  onStompError: (frame) => {
    console.error('❌ STOMP error:', frame);
  }
});

stompClient.activate();

// Option 2: WebSocket natif
const stompClient = new Client({
  brokerURL: 'ws://localhost:8090/ws/chat',
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`
  },
  onConnect: () => {
    console.log('✅ Connected!');
  }
});

stompClient.activate();
```

## Checklist de débogage WebSocket

1. ✅ Vérifier l'URL: `ws://localhost:8090/ws/chat` (pas `/ws`)
2. ✅ Utiliser un client STOMP (pas fetch/axios)
3. ✅ Utiliser le protocole `ws://` (pas `http://`)
4. ✅ Ajouter le token JWT dans les headers STOMP
5. ✅ Vérifier les logs du serveur pour les erreurs de connexion

## Installation des dépendances

```bash
# Pour React/Next.js
npm install sockjs-client @stomp/stompjs

# Pour Vue.js
npm install sockjs-client @stomp/stompjs

# Pour Angular
npm install sockjs-client @stomp/stompjs rxjs-websockets
```

## Test de l'endpoint

```bash
# Test GET
curl http://localhost:8090/ws

# Test POST (devrait retourner le même message)
curl -X POST http://localhost:8090/ws

# Les deux retournent maintenant un message informatif au lieu d'une erreur
```

## Fichiers modifiés

1. ✅ `src/main/java/org/program/pair/domain/websocket/WebSocketInfoController.java`
   - Support de toutes les méthodes HTTP
   - Message d'erreur explicite

2. ✅ `src/main/java/org/program/pair/shared/exception/GlobalExceptionHandler.java`
   - Handler pour `HttpRequestMethodNotSupportedException`
   - Logs informatifs au lieu de stacktraces

3. ✅ `WEBSOCKET_FIX.md` - Guide complet WebSocket
4. ✅ `API_ERRORS_GUIDE.md` - Guide des erreurs API
5. ✅ `LATEST_FIXES.md` - Ce document

## Prochaines étapes

1. **Redémarrez le serveur** si ce n'est pas déjà fait
2. **Corrigez votre code frontend** pour utiliser `ws://localhost:8090/ws/chat`
3. **Testez la connexion WebSocket** avec les exemples fournis
4. **Vérifiez les logs** - plus de stacktraces inutiles!

---

**Status**: ✅ Corrections appliquées
**Date**: 2026-06-25 19:35
**Impact**: Réduction du bruit dans les logs + Messages d'erreur explicites
