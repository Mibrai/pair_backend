# 🔧 Corrections WebSocket - Résumé

## Problèmes identifiés

### 1. ❌ Erreur: `NoResourceFoundException: No static resource ws for request '/ws'`

**Cause**: Le frontend essayait d'accéder à `/ws` avec une requête HTTP GET, alors que:
- L'endpoint WebSocket configuré est `/ws/chat`
- WebSocket nécessite une connexion spéciale (protocole `ws://` ou `wss://`), pas HTTP GET

### 2. ❌ Logs d'erreur excessifs

Chaque tentative de connexion WebSocket incorrecte générait une stacktrace complète dans les logs.

## Solutions appliquées

### 1. ✅ Ajout d'un endpoint informatif `/ws`

**Fichier créé**: `src/main/java/org/program/pair/domain/websocket/WebSocketInfoController.java`

Cet endpoint répond maintenant aux requêtes HTTP (GET/POST/PUT/DELETE/PATCH) sur `/ws` avec des informations utiles:

```json
{
  "error": "Invalid WebSocket endpoint",
  "message": "You are trying to connect to /ws which is not a valid WebSocket endpoint.",
  "correctEndpoint": {
    "url": "ws://localhost:8090/ws/chat",
    "protocol": "STOMP over WebSocket",
    "sockjs": true,
    "description": "Real-time chat messaging"
  },
  "documentation": {
    "connect": "Use STOMP client to connect to ws://localhost:8090/ws/chat",
    "subscribe": "Subscribe to /topic/* or /queue/* for messages",
    "send": "Send messages to /app/* destinations"
  },
  "example": "Use SockJS client: new SockJS('http://localhost:8090/ws/chat')"
}
```

### 2. ✅ Amélioration du GlobalExceptionHandler

**Fichier modifié**: `src/main/java/org/program/pair/shared/exception/GlobalExceptionHandler.java`

Changements:
- Ajout d'un handler spécifique pour `NoResourceFoundException`
- Les erreurs sur les endpoints `/ws*` ne sont plus loguées comme des erreurs complètes
- Passage en niveau `DEBUG` pour les tentatives WebSocket incorrectes

### 3. ✅ Configuration WebSocket améliorée

**Fichier modifié**: `src/main/java/org/program/pair/config/WebSocketConfig.java`

Ajout d'un endpoint WebSocket natif en plus de SockJS:
```java
// Avec SockJS (pour compatibilité navigateurs anciens)
registry.addEndpoint("/ws/chat")
    .setAllowedOriginPatterns("*")
    .withSockJS();

// Sans SockJS (WebSocket natif)
registry.addEndpoint("/ws/chat")
    .setAllowedOriginPatterns("*");
```

## Configuration Frontend

### ❌ URL incorrecte
```javascript
// NE PAS FAIRE ÇA
fetch('http://localhost:8090/ws')  // ❌ HTTP GET sur /ws
```

### ✅ URL correcte pour WebSocket

**Option 1: Avec SockJS (recommandé pour compatibilité)**
```javascript
import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

const socket = new SockJS('http://localhost:8090/ws/chat');
const stompClient = new Client({
  webSocketFactory: () => socket,
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`
  },
  onConnect: () => {
    console.log('Connected to WebSocket');
    
    // S'abonner aux messages privés
    stompClient.subscribe('/user/queue/messages', (message) => {
      console.log('Message reçu:', JSON.parse(message.body));
    });
  }
});

stompClient.activate();
```

**Option 2: WebSocket natif**
```javascript
import { Client } from '@stomp/stompjs';

const stompClient = new Client({
  brokerURL: 'ws://localhost:8090/ws/chat',
  connectHeaders: {
    Authorization: `Bearer ${accessToken}`
  },
  onConnect: () => {
    console.log('Connected to WebSocket');
  }
});

stompClient.activate();
```

## Endpoints WebSocket disponibles

### Connexion
- **URL**: `ws://localhost:8090/ws/chat`
- **Protocol**: STOMP over WebSocket
- **Auth**: Header `Authorization: Bearer <token>` lors du CONNECT

### Destinations (Subscribe)
- `/user/queue/messages` - Messages privés pour l'utilisateur connecté
- `/topic/chat.{conversationId}` - Messages d'une conversation spécifique

### Destinations (Send)
- `/app/chat.send` - Envoyer un message

## Validation après redémarrage

1. **Tester l'endpoint info**:
```bash
curl http://localhost:8090/ws
```

2. **Vérifier les logs**: Les erreurs WebSocket ne devraient plus apparaître comme des stacktraces complètes

3. **Tester la connexion WebSocket** depuis le frontend avec les bonnes URL

## Prochaines étapes

1. ✅ Corriger l'URL WebSocket dans le code frontend
2. ✅ Utiliser `ws://localhost:8090/ws/chat` au lieu de `/ws`
3. ✅ Implémenter l'authentification JWT dans les headers STOMP
4. ⚠️ En production: Restreindre `setAllowedOriginPatterns()` aux origines autorisées

## Notes importantes

- Le protocole WebSocket utilise `ws://` (ou `wss://` pour SSL)
- Les requêtes HTTP `GET/POST` ne fonctionnent pas sur les endpoints WebSocket
- SockJS fournit une couche de compatibilité pour les anciens navigateurs
- L'authentification JWT est validée au moment du CONNECT STOMP

---

Date: 2026-06-25
Status: ✅ Corrections appliquées - Redémarrage requis
