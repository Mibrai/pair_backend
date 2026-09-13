# Un curseur sur l'historique, et le contrat STOMP écrit

**Date :** 2026-09-13
**Module :** [`messagerie/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-13.md`](PROMPT_BACKEND_2026-09-13.md) — P-MU-28 (P1b), P-BA-14, P-BA-05

> **En bref.**
>
> - **§1 — votre relevé est exact** : `limit` était reçu puis ignoré, et le fil entier revenait à
>   chaque appel (aperçu de la liste des conversations compris).
> - **§2 — `limit` est appliqué, plafonné à 50 ; `after=` et `before=` existent.**
> - **§3 — le contrat STOMP**, pour un client natif.
> - **§4 — une session STOMP ne survit plus à son jeton.**
> - **§5 — au-delà d'une instance** : pas de relais prévu ; gardez la relecture en secours.

---

## 1. Constat

`MessageRepository` portait un `int limit` que la requête n'utilisait pas : Spring Data ignore un
paramètre ni nommé ni pageable. Corrigé par un `Pageable`, aussi sur l'aperçu du dernier message de la
liste des conversations, qui lisait le fil entier pour la même raison.

## 2. `GET /api/conversations/{id}/messages`

| Paramètre | Effet | Ordre de la réponse |
|---|---|---|
| *(aucun curseur)* | les `limit` derniers messages | **du plus récent au plus ancien** — inchangé, c'est ce que lit 1.1.0+16 |
| `after=<messageId>` | les messages postérieurs à ce message ; `[]` s'il n'y a rien de neuf | **du plus ancien au plus récent** |
| `before=<messageId>` | la page précédente (« charger plus ») | **du plus ancien au plus récent** |
| `limit` | défaut 50, **plafonné à 50** ; `limit < 1` → `400` | — |

- Un curseur **inconnu ou d'un autre fil** → **`400 INVALID_PARAMETER`**. Les deux curseurs ensemble
  aussi.
- L'ordre sans curseur n'a pas été inversé : l'app publiée l'aurait lu à l'envers.
- ⚠️ **Changement visible pour 1.1.0+16** : elle demande `limit=100` et reçoit désormais au plus les
  **50 derniers** messages. Au-delà, `before=<id du plus ancien reçu>`.
- Dans un fil de groupe ou de diffusion, les messages d'une personne bloquée sont retirés **après**
  la limite : une page peut contenir moins de `limit` éléments sans que le fil soit épuisé. Seul un
  `[]` sur `before=` dit qu'il n'y a plus rien avant.

La relecture de 15 s devient : `after=<id du dernier message affiché>`.

## 3. Contrat STOMP

- **URL** : `wss://lien.meetdo.fun/ws/chat` (WebSocket natif), ou la même avec SockJS. `…/ws` rend
  404 : corrigez `AppConfig.wsBaseUrl`.
- **`CONNECT`** : en-tête natif `Authorization: Bearer <jeton d'accès>`. Jeton de rafraîchissement,
  compte désactivé ou jeton émis avant un changement de mot de passe → connexion refusée (trame
  `ERROR`).
- **Battement de cœur** : le courtier n'en émet pas (`heart-beat` serveur `0,0`). Le client peut en
  proposer ; ne comptez pas sur le serveur pour détecter une connexion morte.

| Sens | Destination | Charge utile |
|---|---|---|
| s'abonner | `/user/queue/messages` | `MessageDto` (celui de l'historique) |
| s'abonner | `/user/queue/messages.edited` | `MessageDto` modifié |
| s'abonner | `/user/queue/messages.deleted` | l'`id` du message (UUID en JSON) |
| s'abonner | `/user/queue/typing` | `TypingEventDto {conversationId, userId, typing}` |
| envoyer | `/app/chat.send` | `{conversationId, content}` (même validation que le `POST`) |
| envoyer | `/app/chat.typing` | `{conversationId, typing}` |

Le blocage est appliqué à **chaque envoi** : un fil bloqué ne pousse rien, sans dépendre de la
session.

## 4. Expiration du jeton pendant une session

L'échéance du jeton présenté au `CONNECT` est gardée dans la session. **Passé cette échéance, tout
`SEND` et tout `SUBSCRIBE` sont refusés** (trame `ERROR`). Le client doit se reconnecter avec un jeton
frais — le plus simple : reconnecter à chaque rafraîchissement. Une révocation de session suit la même
règle qu'en HTTP : le jeton d'accès vaut jusqu'à son échéance ; un changement de mot de passe, lui,
refuse le `CONNECT` suivant.

Limite connue : les messages **reçus** continuent d'arriver sur une session ouverte après l'échéance,
jusqu'à la déconnexion ; c'est l'envoi qui est refusé.

## 5. Plusieurs instances

Aucun relais n'est prévu : la production reste à **une instance** (`numReplicas: 1`, gardé par un
test). Si cela change, le courtier en mémoire perdra des messages entre instances. **Gardez la
relecture par `after=` comme secours**, déclenchée au retour au premier plan et à chaque reconnexion.
