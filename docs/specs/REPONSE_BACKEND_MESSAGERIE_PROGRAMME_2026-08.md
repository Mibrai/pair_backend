# Réponse backend — messagerie de programme (août 2026)

> Réponse à `PROMPT_BACKEND_MESSAGERIE_PROGRAMME_2026-08.md`. **§3 et §2 sont
> livrés.** §1 ne l'est pas, et le document dit pourquoi il coûte plus cher que
> vous ne l'avez chiffré.
>
> Le point de départ change : **§3 n'était pas « partiel », il était vide**.
> L'activité que vous pensiez disponible ne l'a jamais été.
>
> §2 est livré avec **une extension de contrat** — `programId` dans le corps de
> `POST /api/conversations` — sans laquelle le refus que vous décrivez n'était pas
> calculable, et avec **un refus de plus que les trois demandés**, sans lequel le
> réglage n'aurait été qu'un drapeau d'affichage. Les deux points sont détaillés
> au §2.

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

## §1 — Non livré : deux obstacles à connaître avant de le chiffrer

Votre lecture du modèle est juste sur un point important : `ConversationType`
porte **déjà** `DIRECT` et `GROUP`, et `conversation_members` existe. Le point
d'extension est bien là. Deux choses vous manquent.

### `PROGRAM_BROADCAST` ne rentre pas dans la colonne

`conversations.type` est un `VARCHAR(10)` (migration V6). `PROGRAM_BROADCAST`
fait 17 caractères : sans migration d'élargissement, tout enregistrement échoue.
Rien de grave, mais c'est une migration de plus, et le genre de détail qui fait
échouer une mise en production plutôt qu'une revue.

### L'appartenance dérivée coûte le compteur de non-lus

Vous demandez, à raison, que l'appartenance soit dérivée des `UserProgram`
`ACTIVE` plutôt que recopiée. Mais `conversation_members` ne porte pas que
l'appartenance : il porte `last_read_at`, d'où sortent le nombre de non-lus par
fil et le badge d'icône. Une appartenance purement dérivée supprimerait le suivi
de lecture sur les fils de diffusion — le badge cesserait de compter les
diffusions, et un fil de trente personnes n'aurait plus de « non lu ».

La sortie tient en une phrase : garder `conversation_members` comme **porteur de
la lecture**, et ne dériver que l'**autorisation**. Un participant qui quitte le
programme perd l'accès, y compris à l'historique, exactement comme vous le
demandez ; sa ligne de lecture reste, sans lui donner de droit.

C'est faisable, et c'est le vrai contenu de §1 — pas le modèle de messages, qui
lui existe déjà.

### Notifications

`PROGRAM_BROADCAST` manque à `NotificationType`, sans contrainte en base : pas de
migration. Il faut en revanche deux entrées dans `PushNotificationService` et les
clés de traduction, faute de quoi la push part avec un libellé générique.

### Sur la forme des routes

Votre alternative — pas de routes parallèles, le fil apparaît dans
`GET /api/conversations` avec `type: "PROGRAM_BROADCAST"` — est la bonne, et pour
la raison que vous donnez. Nous la prendrons. Cela suppose de rendre `otherUser`
nullable et d'ajouter `title` + `memberCount`, comme vous le décrivez.

---

## Ce qui est livré

| Point | État | Où |
|---|---|---|
| §3 — contexte programme, activité, dates | **Livré** | `ConversationSummaryDto`, `ConversationDetailDto`, `V51` |
| §3 — activité enfin servie (`activityContextName`) | **Livré**, correctif | `ChatService` |
| §3 — contexte écrit en rejoignant un créneau | **Livré** | `SlotService` |
| §2 — `allowParticipantMessages` | **Livré** | `ProgramDto`, `CreateProgramRequest`, `UpdateProgramRequest`, `V52` |
| §2 — refus à l'ouverture et à l'envoi | **Livré** | `ChatService`, code `PROGRAM_MESSAGES_DISABLED` |
| §1 — diffusion de groupe | Non livré | chiffrage revu à la hausse, voir ci-dessus |

Aucun champ existant n'a changé de type ni disparu. Les ajouts sont tous
nullables ou facultatifs. Ce qui était nul cesse de l'être.

Trois changements de comportement à connaître :

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
