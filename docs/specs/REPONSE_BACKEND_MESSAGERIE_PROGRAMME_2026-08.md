# Réponse backend — messagerie de programme (août 2026)

> Réponse à `PROMPT_BACKEND_MESSAGERIE_PROGRAMME_2026-08.md`. **Les trois points
> sont livrés**, dans l'ordre que vous suggériez.
>
> Le point de départ change : **§3 n'était pas « partiel », il était vide**.
> L'activité que vous pensiez disponible ne l'a jamais été.
>
> §2 est livré avec **une extension de contrat** — `programId` dans le corps de
> `POST /api/conversations` — sans laquelle le refus que vous décrivez n'était pas
> calculable, et avec **un refus de plus que les trois demandés**, sans lequel le
> réglage n'aurait été qu'un drapeau d'affichage.
>
> §1 est livré avec **une seule route nouvelle** au lieu des deux proposées, votre
> alternative ayant été retenue. Un point de conception qui n'était pas dans votre
> demande y est tranché : l'appartenance dérivée aurait emporté le compteur de
> non-lus, et ce qu'il a fallu faire pour l'en empêcher se voit du côté client.
>
> Chaque section détaille ces écarts.

---

## L'activité n'a jamais été servie

Votre tableau classe le contexte de conversation « **Partiel** — l'activité oui,
le programme et la date non ». L'activité non plus.

`ChatService.java:254` et `:294`, avant ce lot :

```java
return new ConversationSummaryDto(
    conv.getId(),
    conv.getType().name(),
    otherUser,
    null, // activityContextName - TODO Phase 2
    ...
```

Le champ existait dans le contrat OpenAPI depuis l'origine et valait `null` en
toutes circonstances, dans le résumé comme dans le détail. La spec publique que
vous avez relevée décrit donc un champ que rien ne remplissait — et c'est bien
ce que la spec pouvait montrer : elle porte les types, pas les valeurs.

La cause est en amont. La colonne `activity_context_id`, créée par la migration
V6, **n'était écrite nulle part** : `createConversation` recevait
`CreateConversationRequest.activityContextId` et le jetait sans l'enregistrer.
En cascade, `SlotService.joinSlot`, dont le commentaire annonce « Ouvrir la
conversation contextualisée », transmettait bien l'activité du programme — et
elle était perdue à l'arrivée.

Conséquence pour vous : la ligne « ce que le frontend a livré en attendant »,
qui pose l'activité dans l'en-tête « dès maintenant », affichait un vide. Ce
n'est plus le cas.

---

## §3 — Livré

### Ce qui arrive maintenant

Sur `ConversationSummaryDto` **et** `ConversationDetailDto` :

| Champ | Type | Source |
|---|---|---|
| `programId` | uuid, nullable | programme du créneau rejoint |
| `programTitle` | string, nullable | `programs.title` |
| `activityName` | string, nullable | `activities.name` |
| `activityContextName` | string, nullable | même valeur qu'`activityName` |
| `scheduleId` | uuid, nullable | créneau qui lie les deux personnes |
| `scheduleStartsAt` | date-time UTC, nullable | `schedules.starts_at` |
| `scheduleEndsAt` | date-time UTC, nullable | `schedules.ends_at` |

`activityContextName` est conservé et servi à l'identique d'`activityName`, comme
vous le demandiez — rien à changer chez vous pour le champ existant, sinon qu'il
cesse d'être nul.

`scheduleEndsAt` est renseigné quand le créneau en porte un ; il est nul sinon,
la colonne étant facultative en base. Votre règle — comparer `scheduleEndsAt`
quand il existe, `scheduleStartsAt` sinon — est donc la bonne.

**Tous ces champs restent nullables**, et le resteront : une conversation ouverte
depuis un profil n'a aucun contexte. C'est vérifié par un test dédié.

### D'où vient le contexte

Du **créneau**, pas de l'activité. Quand quelqu'un rejoint un créneau,
`SlotService` connaît le `Schedule`, donc le `Program` : les trois identifiants
sont écrits sur la conversation à ce moment-là.

Ce choix vous évite un changement de contrat : le corps de
`POST /api/conversations` est **inchangé**, `{ targetUserId, activityContextId }`.
Rien à modifier côté client pour recevoir le contexte.

Il a une limite qu'il vaut mieux énoncer : une conversation ouverte depuis un
écran de programme, sans passer par un créneau, naît sans contexte. Si vous en
avez besoin, il faudra ajouter `programId` / `scheduleId` au corps de la requête —
dites-le et c'est une petite extension.

### Le contexte est rafraîchi, pas figé

Deux personnes qui se retrouvent sur une nouvelle séance gardent leur fil ; le
contexte suit la dernière séance rejointe ensemble. Garder la première figerait
l'en-tête sur une date passée, et comme vous grisez sur cette date, le fil se
griserait alors qu'un créneau est à venir.

### Ce qui n'est pas rétroactif

Les conversations créées **avant** ce lot n'ont pas de contexte : rien en base ne
dit quel créneau les avait liées. Elles s'afficheront sans en-tête contextuel et
ne griseront pas — le comportement que vous avez déjà pour les conversations nées
d'un profil. Un rattrapage serait possible en rapprochant les participations aux
créneaux, mais il devinerait ; nous ne l'avons pas fait. Les nouvelles
conversations sont complètes dès leur création.

### Détail technique

Migration `V51__conversation_program_context.sql` : deux colonnes sur
`conversations`, `ON DELETE SET NULL` comme `activity_context_id` — la
disparition d'un programme retire le contexte, elle n'emporte pas l'historique.
Le contexte de toute la liste est chargé en une requête, pas une par fil.

---

## §2 — Livré, avec une extension de contrat

`allowParticipantMessages`, défaut `true`, sur `ProgramDto`,
`CreateProgramRequest` et `UpdateProgramRequest`. Défaut à `true` pour la raison
que vous donnez, et la colonne est `NOT NULL DEFAULT TRUE` : les programmes
existants héritent de `true`, personne ne perd une conversation en place.

Une duplication de programme hérite du réglage, contrairement à `isPublic` que la
copie remet à faux : la copie naît en brouillon, mais le choix d'accepter ou non
les messages appartient à l'auteur et n'a pas de raison d'être réinitialisé.

### Ce qu'il a fallu ajouter : `programId` dans le corps

Le premier refus que vous demandez n'était pas calculable :

> `POST /api/conversations` avec un `activityContextId` rattaché à un programme
> dont `allowParticipantMessages = false` → 403

`activityContextId` est un identifiant d'**activité**. Une activité — « Yoga » —
porte autant de programmes que d'auteurs, donc rien ne permettait de savoir quel
réglage consulter.

`CreateConversationRequest` accepte donc un troisième champ, **facultatif** :

```
{ "targetUserId": "…", "activityContextId": "…", "programId": "…" }
```

À vous de le renseigner quand la conversation s'ouvre depuis un écran de
programme. **Sans lui, aucun programme n'est en jeu et rien n'est refusé** — une
conversation ouverte depuis un profil reste hors du réglage, et c'est la limite
assumée du mécanisme. Le champ sert aussi de contexte §3 : une conversation
ouverte depuis un programme porte désormais `programTitle` dans son en-tête.

### Un refus de plus que les trois demandés

Vos trois règles sont en place : ouverture refusée, auteur toujours autorisé dans
les deux sens, et le cas du fil de diffusion viendra avec §1. Il en manquait une
quatrième, sans laquelle les autres ne servent à rien :

**l'envoi dans un fil déjà ouvert est refusé lui aussi.** Ne vérifier qu'à
l'ouverture laisserait passer tout participant ayant déjà écrit une fois — et
comme rejoindre un créneau ouvre automatiquement une conversation, c'est le cas
de presque tous. Un auteur qui ferme sa messagerie aurait continué de recevoir
des messages de tous ceux qui lui avaient déjà écrit. C'est exactement le
« simple drapeau d'affichage » que votre demande écarte.

Le refus est cadré : il ne s'applique qu'aux fils qui portent le programme
concerné et dont l'auteur est membre — deux participants qui discutent entre eux
ne sont pas concernés par un réglage qui porte sur ce que l'auteur reçoit. **La
lecture n'est jamais touchée** : lecture seule veut dire lecture, historique
compris.

Si cette quatrième règle ne vous convient pas, elle s'enlève seule — dites-le.

### Forme du refus

`403` avec `code: "PROGRAM_MESSAGES_DISABLED"`, à traduire chez vous. Le code est
stable et ne changera pas de nom.

### Rejoindre un créneau reste possible

Un créneau appartenant à un programme fermé se rejoint normalement : seule
l'ouverture automatique du fil saute. Rejoindre et écrire sont deux choses, et
fermer sa messagerie ne ferme pas ses créneaux.

### Un réglage de trop, à arbitrer

`PrivacySettings.allowMessages` (`EVERYONE` / `FRIENDS` / `NONE`) existe en base
et **n'est vérifié nulle part**. Avec `User.receiveMessages` et maintenant
`allowParticipantMessages`, le produit compte trois réglages de messagerie dont
un mort. Ce n'est pas bloquant, mais ça mérite une décision plutôt qu'un oubli.

---

## §1 — Livré

Votre lecture du modèle était juste sur un point qui a beaucoup aidé :
`ConversationType` portait **déjà** `DIRECT` et `GROUP`, et
`conversation_members` existait. Le point d'extension était bien là. Deux choses
manquaient à votre chiffrage, et une troisième s'est révélée en chemin.

### Une seule route nouvelle, pas deux

```
POST /api/programs/{programId}/broadcasts
     body : { "content": "…" }
     201 → MessageDto
     403 PROGRAM_BROADCAST_READ_ONLY si l'appelant n'est pas l'auteur
     404 si le programme n'existe pas
```

C'est tout. **La lecture passe par la messagerie que vous avez déjà** : le fil
apparaît dans `GET /api/conversations` avec `type: "PROGRAM_BROADCAST"`, et ses
messages se lisent par `GET /api/conversations/{id}/messages` comme n'importe
quel autre fil. Votre alternative était la bonne, et pour la raison que vous
donniez — vous n'avez pas de second modèle de messages à écrire.

Il fallait tout de même un endroit pour déclencher la **première** diffusion,
puisque le fil naît à ce moment-là et pas à la création du programme : d'où cette
route, et pas la seconde (`GET …/broadcasts`) que vous proposiez, devenue inutile.

Les diffusions suivantes passent indifféremment par cette route ou par
`POST /api/conversations/{id}/messages`, qui refuse en `403
PROGRAM_BROADCAST_READ_ONLY` tout expéditeur autre que l'auteur. Le composeur que
vous masquez chez les participants est donc doublé côté serveur : un client
modifié n'écrit pas dans un fil qui n'est pas le sien.

### `otherUser`, `title`, `memberCount`

Comme vous le demandiez. `otherUser` est **nul** sur un fil de diffusion — le
remplir avec l'auteur aurait affiché « conversation avec X » pour un fil qui en
compte trente, exactement le piège que vous signaliez. À la place, sur les deux
DTO :

```
title       : string  (nullable)  // titre du programme
memberCount : integer (nullable)  // auteur + participants actifs
```

Nuls tous les deux pour une conversation à deux, qui continue de s'annoncer par
`otherUser`.

Le détail du fil (`GET /api/conversations/{id}`) énumère les membres **dérivés**,
pas les lignes de lecture : celles-ci diraient « trois personnes » sur un
programme qui en compte trente dont deux seulement l'ont ouvert.

### L'appartenance est dérivée, et le compteur de non-lus a survécu

C'est le point dur du §1, et il n'était pas dans votre demande.

Vous demandez, à raison, que l'appartenance soit dérivée des `UserProgram`
`ACTIVE` plutôt que recopiée. Mais `conversation_members` ne porte pas que
l'appartenance : il porte `last_read_at`, d'où sortent le nombre de non-lus par
fil et le badge d'icône. Une appartenance *purement* dérivée aurait supprimé le
suivi de lecture des fils de diffusion — un fil de trente personnes sans « non
lu », et le badge qui cesse de compter les diffusions.

Le partage retenu : `conversation_members` reste le **porteur de la lecture**,
l'**autorisation** seule est dérivée. La ligne est créée à la première lecture et
ne donne aucun droit ; l'accès se recalcule à chaque fois, à partir des
inscriptions actives et de l'auteur.

Ce qui en découle, et qui est ce que vous vouliez :

- un **nouvel inscrit** gagne le fil **et tout son historique**, y compris ce qui
  a été diffusé avant son arrivée, sans qu'aucun traitement n'ait à passer
  derrière lui ;
- un **participant qui part** perd le fil et son historique le jour même, sa
  ligne de lecture eût-elle subsisté.

Un troisième effet ne se voyait pas depuis votre côté : sans précaution, le
partant aurait **gardé au badge** les messages non lus d'un fil qu'il ne peut
plus ouvrir — un nombre qu'il lui aurait été impossible de faire retomber. Le
calcul des non-lus écarte donc les fils de diffusion auxquels on n'a plus droit.
C'est vérifié par un test dédié.

### Ce qui compte dans le badge

Une diffusion est un message : elle compte dans
`GET /api/conversations/unread-count` et dans le `unreadCount` du fil, comme
n'importe quel autre. Elle ne crée **pas** de notification in-app en plus — la
doubler ferait compter deux fois la même chose sur l'icône.

### Notifications

Type dédié `PROGRAM_BROADCAST`, distinct de `NEW_MESSAGE` comme vous le
demandiez, avec `programId` et `programTitle` au payload : de quoi router le tap
vers le fil du programme plutôt que vers une conversation à deux. Textes de push
fournis en français, anglais et allemand ; le titre nomme le **programme**, pas
l'auteur — dans un fil de diffusion c'est le programme qu'on suit.

À ajouter de votre côté dans `notification_pref_catalog.dart`.

### Deux détails de schéma

`conversations.type` était un `VARCHAR(10)` depuis V6. `PROGRAM_BROADCAST` fait
17 caractères : sans élargissement, tout enregistrement échouait. `V53` le porte
à 30.

L'unicité « un fil par programme » est un index **partiel**, restreint aux fils
de diffusion : `program_id` sert aussi de contexte aux conversations directes
(§3), où plusieurs conversations partagent légitimement le même programme.

### Une limite à connaître

Un fil de diffusion ne se masque pas : `DELETE /api/conversations/{id}` le refuse
en 400. L'appartenance étant dérivée du programme, le fil reparaîtrait à la
lecture suivante — le dire franchement vaut mieux qu'un masquage qui ne tient
pas. On quitte le fil en quittant le programme.

---

## Ce qui est livré

| Point | État | Où |
|---|---|---|
| §3 — contexte programme, activité, dates | **Livré** | `ConversationSummaryDto`, `ConversationDetailDto`, `V51` |
| §3 — activité enfin servie (`activityContextName`) | **Livré**, correctif | `ChatService` |
| §3 — contexte écrit en rejoignant un créneau | **Livré** | `SlotService` |
| §2 — `allowParticipantMessages` | **Livré** | `ProgramDto`, `CreateProgramRequest`, `UpdateProgramRequest`, `V52` |
| §2 — refus à l'ouverture et à l'envoi | **Livré** | `ChatService`, code `PROGRAM_MESSAGES_DISABLED` |
| §1 — fil de diffusion, une par programme | **Livré** | `POST /api/programs/{id}/broadcasts`, `V53` |
| §1 — appartenance dérivée des inscriptions actives | **Livré** | `ChatService`, `ConversationRepository` |
| §1 — `otherUser` nul, `title` + `memberCount` | **Livré** | `ConversationSummaryDto`, `ConversationDetailDto` |
| §1 — lecture seule des participants | **Livré** | code `PROGRAM_BROADCAST_READ_ONLY` |
| §1 — notification dédiée | **Livré** | `NotificationType.PROGRAM_BROADCAST`, textes fr/en/de |

Aucun champ existant n'a changé de type ni disparu. Les ajouts sont tous
nullables ou facultatifs. Ce qui était nul cesse de l'être.

Cinq changements de comportement à connaître :

1. `activityContextId` étant désormais enregistré, un identifiant d'activité
   inconnu envoyé à `POST /api/conversations` répond **404** au lieu d'être
   ignoré en silence. Le champ reste facultatif — c'est l'envoyer et se tromper
   qui est signalé, pas l'omettre. Ce silence est précisément ce qui a produit le
   défaut corrigé en tête de ce document.
2. `POST /api/conversations` et l'envoi de message peuvent maintenant répondre
   **403 `PROGRAM_MESSAGES_DISABLED`**. Uniquement quand un programme est en jeu
   et que son auteur a fermé sa messagerie.
3. `POST /api/conversations/{conversationId}/messages` refuse en **400** un corps
   dont le `conversationId` diffère de celui de l'URL. Le contrôleur ignorait sa
   variable de chemin ; une autorisation écrite sur la ressource adressée aurait
   porté sur une conversation pendant que l'écriture se faisait dans une autre.
   Si vous envoyez les deux identiques — c'est le cas de tous les appelants que
   nous connaissons — rien ne change pour vous.
4. `GET /api/conversations` peut désormais renvoyer des entrées à
   `type: "PROGRAM_BROADCAST"`, dont `otherUser` est **nul**. Une liste de
   messagerie qui suppose cet objet présent plantera : c'est le seul point du
   lot qui demande une adaptation de votre côté avant mise en service.
5. `DELETE /api/conversations/{id}` refuse en **400** sur un fil de diffusion.
   Voir « Une limite à connaître » au §1.
