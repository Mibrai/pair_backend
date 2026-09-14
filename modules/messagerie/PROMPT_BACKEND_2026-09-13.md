# Le fil ouvert se relit toutes les 15 s : donnez-nous le temps réel et un curseur

**Date :** 2026-09-13
**Module :** `messagerie` (nouveau)
**Audit :** P-MU-28 (P1b), P-BA-14, P-BA-05

> **Ce que l'app a déjà fait, sans vous.** Un fil ouvert, visible et au premier plan relit
> `GET /api/conversations/{id}/messages` toutes les 15 s. Il se tait en arrière-plan, sous une
> autre page et pendant un envoi. C'est un palliatif : un message met jusqu'à 15 s à apparaître,
> et chaque fil ouvert vous coûte 4 requêtes par minute.
>
> **Ce qui vous appartient** : une relecture qui ne renvoie que ce qui est nouveau, puis la
> confirmation que le canal STOMP est prêt pour un client mobile.

---

## 1. Relevés du 13/09/2026 (backend `1cfe54f`, production, compte de test)

**Historique**
- `ChatController.java:96-103` accepte `limit` (50 par défaut, borné à 100) ; `ChatService.java:735-754` le transmet.
- Mais `MessageRepository.java:18-19` est un `@Query("SELECT m … ORDER BY m.sentAt DESC")` **sans `LIMIT`** : le paramètre n'est pas utilisé.
- En HTTP, sur un même fil : `limit=500`, `limit=2`, `limit=2&before=2026-07-01T00:00:00Z` et `limit=2&cursor=abc` rendent **tous les 36 messages**, en 1,11 à 1,58 s.
- `/v3/api-docs` n'annonce que `limit` (int32, défaut 50), aucun curseur.

**Temps réel — il existe déjà**
- `spring-boot-starter-websocket` (`pom.xml:98`), `WebSocketConfig` avec un courtier simple en mémoire (`/topic`, `/queue`, préfixes `/app` et `/user`).
- Point d'entrée `/ws/chat`, SockJS et natif ; ouvert par `SecurityConfig.java:64`, le `CONNECT` exige `Authorization: Bearer`.
- Diffusion : `/user/queue/messages` (`ChatService.java:418-426`, destinataires filtrés par blocage), `/queue/messages.edited`, `/queue/messages.deleted`, `/queue/typing` ; envoi par `/app/chat.send` et `/app/chat.typing`.
- En production : `GET /ws/chat/info` → 200 `{"websocket":true,…}` ; poignée de main native sur `/ws/chat` → **101** ; `GET /ws` → 404.
- `railway.json:6` : `numReplicas: 1`.
- Rien de cela ne figure dans `/v3/api-docs`. Côté app, `AppConfig.wsBaseUrl` pointe sur `/ws`, qui répond 404.

## 2. Les demandes

1. **Un curseur sur `GET /api/conversations/{id}/messages`** (P-BA-14) :
   - **appliquer `limit`**, borné à 50 selon la règle de P-BA-14 ;
   - **`after=<messageId>`** : seulement les messages postérieurs (la relecture) ;
   - **`before=<messageId>`** : la page précédente (« charger plus ») ;
   - un ordre documenté (nous proposons du plus ancien au plus récent) ;
   - un curseur inconnu rend 400, jamais une liste vide silencieuse ;
   - les deux paramètres au contrat.
   - ⚠️ Appliquer `limit` change ce que reçoit l'app déjà publiée, qui demande 100 : elle ne verrait plus que les 50 derniers messages. À annoncer.
2. **Confirmer et documenter le contrat STOMP pour un client natif** : URL de production (`wss://…/ws/chat`), en-tête du `CONNECT`, destinations et charges utiles, battement de cœur attendu.
3. **L'expiration du jeton pendant une session** : le jeton est vérifié au seul `CONNECT`, alors que le jeton d'accès vit 900 s. Une session ouverte survit-elle à son jeton, à une révocation, à un blocage ?
4. **Au-delà d'une instance** (`numReplicas > 1`), le courtier en mémoire perdra des messages. Un relais est-il prévu ? Sinon l'app garde la relecture en secours.

## 3. La charge d'ici là (P-BA-05)

Une requête toutes les 15 s par fil ouvert, chacune au plancher authentifié (~750 ms) et, tant que
`limit` est ignoré, avec le fil entier. Avec `after=`, une relecture sans nouveauté rendrait `[]`.

## 4. Comment nous vérifierons

Relevé de `/v3/api-docs`, puis en HTTP : `limit=2` rend 2 messages ; `after=` rend `[]`, puis le
message envoyé depuis un second compte. Session STOMP réelle à deux comptes : message reçu en moins
d'une seconde, fil bloqué muet. Côté app, derrière un drapeau éteint ; la relecture des 15 s reste
l'interrupteur si le canal régresse.
