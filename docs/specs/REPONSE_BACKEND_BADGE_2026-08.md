# Réponse backend — le badge d'icône

> Réponse à `PROMPT_BACKEND_BADGE_2026-08.md`. D1 à D4 sont livrées. La
> vérification que vous demandiez avant tout le reste a été faite, et elle
> désigne une cause que ni vous ni nous n'avions nommée : **la messagerie
> n'émettait aucune push**. Pas « une push sans badge » — aucune.

---

## D1 — ce que l'audit a trouvé

Vous proposiez trois hypothèses. La réponse tient en trois constats, et la
deuxième était la bonne à un détail près, qui change tout.

### a) Le correctif B3 est bien dans le code, et il est juste

`PushNotificationService` calcule le compteur après enregistrement, le borne aux
entiers positifs des deux plateformes, et le pose sur `aps.badge` **et**
`notification.notification_count`. Rien à y reprendre. Ce n'était pas un
mensonge de la réponse au lot 7.

### b) Il n'existait pas de second producteur avec un `1` en dur — il n'existait aucun second producteur

Un seul chemin construit une charge push dans tout le dépôt :
`NotificationService.notify()` → `PushNotificationService`. Aucune autre classe
n'appelle FCM, ni par le SDK, ni en HTTP direct.

Et `ChatService.sendMessage()` ne l'empruntait pas. Il ne diffusait le message
que par **WebSocket**, qui ne porte que jusqu'à une application ouverte. Le type
`NEW_MESSAGE` existait, ses titres et corps étaient traduits en trois langues
depuis le lot 7 — **aucun producteur ne les émettait**.

Application fermée, un message reçu ne déclenchait donc rien du tout. Aucune
bannière, et surtout aucun `aps.badge` : c'est très exactement votre seconde
lecture — *« la valeur est absente, iOS conserve la précédente »*. Le nombre
figé sur l'icône était le dernier badge qu'une notification hors messagerie
avait posé.

### c) Le doute sur le déploiement était fondé, et il reste à lever de votre côté

`firebase.enabled` vaut `false` par défaut, et **`RAILWAY_ENV_VARS.md` ne
documentait ni `FIREBASE_ENABLED` ni `FIREBASE_CREDENTIALS_PATH`**. Quand le
drapeau est absent, c'est `NoOpPushNotificationService` qui est câblé : les
notifications continuent d'être enregistrées, l'API continue de répondre, et
seul le téléphone se tait. Aucune erreur nulle part.

Deux corrections pour que cela ne puisse plus se produire :

- les deux variables sont documentées, avec la ligne de journal qui tranche au
  démarrage (`Firebase initialized successfully (push notifications enabled)`) ;
- avec `FIREBASE_ENABLED=true`, un identifiant manquant ou illisible **empêche
  désormais le démarrage**. Cette fabrique renvoyait `null` et laissait
  l'application démarrer sur un `WARN` que personne ne relit. Une configuration
  push cassée se voit maintenant au déploiement.

**Ce qui reste à faire de votre côté** : relever cette ligne dans le journal de
démarrage de production. Si elle est absente, aucune push n'est jamais partie et
le reste de cette livraison ne s'observera qu'une fois la variable posée.

---

## D2 — `aps.badge` = notifications + messages, sur tout envoi

Livré, en trois pièces.

**Un compteur unique.** `UnreadCounter` est le seul endroit qui répond à la
question « combien reste-t-il à lire ». Trois chemins en dépendent — la push
d'une notification, celle d'un message, le push silencieux de D4 — précisément
pour qu'ils ne puissent pas diverger.

**La messagerie émet enfin une push.** `sendMessage` publie un événement par
destinataire, l'expéditeur exclu, et cet événement devient une push
`NEW_MESSAGE` portant `conversationId`, `messageId`, `senderId`, `senderName` et
un aperçu tronqué à 120 caractères.

Deux détails d'implémentation qui ont une conséquence visible pour vous :

- **la push part après le commit de la transaction.** Comptée avant, elle
  oublierait le message qui vient d'être écrit : le badge arriverait avec une
  unité de retard, systématiquement ;
- **un message ne crée pas de notification in-app.** Il compterait sinon deux
  fois dans le badge — une fois comme message, une fois comme notification — et
  remplirait votre centre de notifications d'entrées que personne n'a demandées.
  La préférence `pushEnabled` du destinataire continue de faire foi.

**Des messages, pas des conversations.** Le compte porte sur les messages, comme
vous le demandiez. Et il fallait le corriger : `ConversationSummaryDto.unreadCount`
comptait **tous** les messages postérieurs à la dernière lecture, *y compris ceux
que l'utilisateur avait lui-même envoyés*, et les messages supprimés. Sur un fil
jamais ouvert, il comptait le fil entier, ses propres messages compris.

Autrement dit : le champ que vous venez de vous mettre à sommer était gonflé.
Votre correction et la nôtre étaient toutes deux nécessaires — vous comptiez des
fils, nous comptions vos propres phrases.

Zéro est envoyé comme une valeur ordinaire, et l'est déjà depuis le lot 7.

---

## D3 — `GET /api/conversations/unread-count`

```http
GET /api/conversations/unread-count
→ 200 { "unreadCount": 4 }
```

Même forme que `GET /api/notifications/unread-count`. `4` est un nombre de
messages, tous fils confondus.

La garantie de cohérence que vous cherchiez est structurelle et non
documentaire : **cet endpoint et le compte par fil sortent de la même requête**,
et c'est cette même requête que `aps.badge` additionne. Un test d'intégration
vérifie que la somme des `unreadCount` de `GET /api/conversations` retombe sur
l'entier servi ici.

---

## D4 — effacement quand la lecture a eu lieu ailleurs

Livré, **vers tous les appareils du compte**, y compris celui qui vient de lire.
C'est le choix qui ne vous demande rien : le serveur ne sait pas quel appareil
émet une requête, et un push silencieux reçu par l'appareil qui vient de lire n'a
aucun effet visible — il repose le badge sur la valeur qu'il affiche déjà.

Charge émise :

```json
{
  "aps": { "content-available": 1, "badge": 0 }
}
```

sans `alert`, avec les en-têtes `apns-push-type: background` et
`apns-priority: 5` — APNs **rejette** une push de fond qui en manque.

Déclenché sur `PUT /notifications/read-all` et sur la lecture d'une conversation
(`POST /api/conversations/{id}/read` et `/read-all`), après commit, hors du fil
de la requête. `badge` porte le total recalculé, pas un `0` en dur : lire ses
notifications alors qu'il reste des messages non lus laisse le badge sur le
nombre de messages.

**Côté Android**, la charge est une charge de données pure, avec `badge` en
donnée. `notification_count` n'existe que sur une notification affichée, ce que
précisément on ne veut pas ici.

---

## Récapitulatif

| # | Demande | État |
| --- | --- | --- |
| D1 | vérifier B3 en production | correctif confirmé juste ; **cause réelle trouvée** (messagerie muette) ; démarrage rendu bruyant ; **une vérification vous revient** |
| D2 | `aps.badge` = notifications + messages | livré ; comptage des messages corrigé au passage |
| D3 | `GET /api/conversations/unread-count` | livré |
| D4 | push silencieux d'effacement | livré, vers tous les appareils |

## Votre question ouverte

**Non, il n'y a rien d'autre à compter.** Tout ce qui réclame une action passe
déjà par le flux des notifications — `SLOT_JOINED`, `ATTENDANCE_PROMPT`,
`NEW_FOLLOWER`, `ACTIVITY_ALERT_MATCH`, les nouveautés d'auteur et de catégorie.
Il n'existe pas de « demande de partenaire en attente » qui vivrait hors de ces
deux flux. *Notifications + messages* est bien le total.

## Ce qui est vérifié par un test

- `ConversationUnreadCountIntegrationTest` — ses propres messages ne comptent
  jamais ; la somme par fil retombe sur le total ; lire ramène à zéro ; un
  message supprimé cesse de compter.
- `ChatServiceTest` — une push est demandée pour chaque destinataire et jamais
  pour l'expéditeur ; l'aperçu est tronqué.
- `PushNotificationServiceTest` — le push silencieux part en un seul envoi quelle
  que soit la langue des appareils, et pas du tout sans appareil.
