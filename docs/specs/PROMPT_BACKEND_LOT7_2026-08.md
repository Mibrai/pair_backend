# Demandes backend — lot 7 (août 2026)

Contexte : chantier client couvrant quatre sujets — filtres de carte, socle de
journalisation, notifications push, et logique d'inscription aux programmes.

Huit demandes. Les deux premières (**B1**, **B2**) bloquent une règle métier et une
exigence visuelle explicites : sans elles, le client livre une version dégradée
qu'il devra signaler à l'utilisateur. Les autres suppriment des contournements
coûteux mais ne bloquent rien.

Chaque demande dit ce que le client fait **aujourd'hui sans elle**, pour que la
priorisation soit une décision et non une devinette.

---

## B1 — `409` avec les conflits sur `POST /programs/{id}/join`

**Priorité : bloquant (règle métier).**

Règle produit à appliquer : *un utilisateur ne peut rejoindre un programme que si
aucun de ses créneaux ne chevauche un créneau d'un programme auquel il est déjà
inscrit.* Décision prise : au moindre chevauchement, l'inscription est refusée.

Le client sait détecter le conflit (il développe les `RRULE` et compare les
occurrences), mais **une vérification côté client n'est pas une règle** : deux
appareils qui s'inscrivent en parallèle la contournent, et un client modifié
l'ignore. La règle doit vivre là où elle est vraie.

Demandé :

```http
POST /api/programs/{id}/join
→ 409 Conflict
{
  "code": "SCHEDULE_CONFLICT",
  "message": "<phrase traduite selon Accept-Language>",
  "conflicts": [
    {
      "scheduleId":        "<uuid du créneau du programme demandé>",
      "occurrenceAt":      "2026-08-17T18:30:00Z",
      "conflictingScheduleId": "<uuid du créneau déjà rejoint>",
      "conflictingProgramId":  "<uuid>",
      "conflictingProgramTitle": "Yoga du soir",
      "conflictingOccurrenceAt": "2026-08-17T18:00:00Z",
      "conflictingEndsAt":       "2026-08-17T19:15:00Z"
    }
  ]
}
```

Le client affiche une feuille listant ces conflits, avec un bouton « quitter » par
ligne — d'où le besoin de `conflictingProgramId` **et** de
`conflictingScheduleId` : quitter passe par `POST /programs/{id}/leave` ou
`POST /slots/{id}/leave` selon la nature de l'engagement.

Même besoin sur `POST /slots/{id}/join`, avec la même enveloppe.

**Sans cette demande** : le client pré-vérifie et refuse localement. La règle
reste contournable, et une course entre deux appareils produit deux inscriptions
qui se chevauchent, sans qu'aucune erreur ne soit remontée.

---

## B12 — Le `payload` des notifications est sérialisé à tort (bug)

**Priorité : bloquant.** C'est le plus simple à corriger des dix, et il bloque à
lui seul une exigence produit entière.

Relevé en production sur `GET /api/notifications`, chaque notification porte :

```json
"payload": "{'array': false, 'bigDecimal': false, 'bigInteger': false, 'object': true}"
```

Ce n'est pas du JSON encodé en chaîne : c'est le `toString` réflexif du
**conteneur** `JsonNode` de Jackson. Le sérialiseur a écrit les propriétés du
wrapper (`isArray()`, `isBigDecimal()`, `isObject()`…) au lieu du **contenu** du
nœud. Les identifiants métier ne sont donc pas mal encodés — **ils ne sont pas
transmis du tout**, ils sont perdus à la sérialisation.

Conséquence : `payload` est censé porter « les identifiants métier pour le
deep-link (ex. `scheduleId`, `programId`) », comme le dit la spec elle-même.
Il n'en porte aucun. **Toute navigation depuis une notification est donc
structurellement impossible** — le tap marque comme lu et ne va nulle part.

Cela invalide directement l'exigence : *« le clic dessus redirige vers la page de
détails du programme »*. Le client a la table de routage complète, testée sur les
30 types (`lib/features/notifications/domain/notification_route.dart`) ; elle
attend une donnée qui n'arrive jamais.

Correction, au choix :
- déclarer `payload` comme `Map<String, Object>` dans le DTO plutôt que
  `JsonNode` — le plus sûr, et cela rend la spec exacte du même coup ;
- ou conserver `JsonNode` et le sérialiser par son contenu (`@JsonRawValue`, ou
  `objectMapper.convertValue(node, Map.class)` avant la mise en DTO).

Attendu, pour un rappel de programme :

```json
"payload": { "programId": "…", "scheduleId": "…", "categoryColorRamp": "orange-red" }
```

**Le client ne plante pas** aujourd'hui : `AppNotification` n'accepte le payload
que si c'est une `Map` et retombe sinon sur un objet vide. C'est précisément ce
qui rend le défaut invisible — la liste s'affiche, les taps ne font rien, et rien
ne signale d'erreur.

À traiter avec **B2** (`categoryColorRamp`) et **B10** (texte des notifications) :
les trois portent sur le contenu de la même charge.

---

## B2 — `categoryColorRamp` dans le `payload` de notification

**Priorité : haute.** *(Révisé le 2026-08-10 après lecture de la spec — voir
l'encadré ci-dessous : la moitié de cette demande était en réalité un défaut
client, déjà corrigé.)*

> ### Correction : `scheduledAt` existe déjà, et le client ne le lisait pas
>
> Cette demande portait initialement sur **deux** champs. La lecture de
> `/v3/api-docs` a montré que le second existe depuis un moment.
>
> `GET /notifications` renvoie un `PagedModelNotificationDto` dont le contenu est
> un **`NotificationDto`** — et non le `NotificationDataDto` sur lequel le client
> s'était aligné. Ce schéma porte `payload`, `isRead`, `sentAt`, `readAt`,
> **et `scheduledAt`**, ce dernier documenté comme « dérivé du payload pour
> `PROGRAM_REMINDER` / `PROGRESSION_REMINDER` ». Les descriptions de la spec
> prennent même soin de corriger le malentendu (« Nom de propriété réel :
> `isRead` (pas `read`) »).
>
> Le client cherchait `scheduledAt` **dans** `payload`, où il n'est pas : il ne
> le trouvait jamais et retombait sur une lecture du texte français du message —
> lui-même absent de ce DTO, donc toujours vide. Conséquence : l'échéance valait
> invariablement `null`, et toute la mise en avant des séances imminentes
> (teinte, compte à rebours, remontée en tête de liste) ne s'est jamais
> déclenchée. **Corrigé côté client, aucune action backend requise.**
>
> Rien à faire non plus sur `isRead` / `sentAt` : le client les tolérait déjà.

Reste donc un seul champ demandé, dans le `payload`.

### `categoryColorRamp`

Exigence produit : *une notification de programme imminent doit apparaître avec
les mêmes couleurs que le pin de ce programme sur la carte.*

Le client teinte la carte depuis `categoryColorRamp` (via sa palette Aurora) pour
les créneaux, et depuis une graine déterministe sur `activityId` pour les badges.
La notification, elle, ne porte que `programId`, `userActivityId` et `scheduleId`.
Si la carte sème sur `activityId` et la notification sur `userActivityId`, **les
deux couleurs diffèrent** — et l'exigence est visuellement fausse alors que le
code semble correct.

Envoyer `categoryColorRamp` donne la même source aux deux surfaces, donc la même
couleur par construction. Le champ est **déjà** présent sur
`MapActivityMarkerDto`, `MapActivityDto`, `MapProgramDto` et `SlotFeedItemDto` :
il ne s'agit que de le joindre au `payload` des notifications liées à un
programme ou à une séance.

**Sans cette demande** : la teinte d'une notification est calculée à partir d'une
graine différente de celle de la carte, donc différente de celle du pin — une
exigence produit visuellement fausse alors que le code des deux côtés est
correct.

---

## B3 — `aps.badge` dans la charge APNs

**Priorité : haute.**

Le client va afficher le nombre de notifications non lues sur l'icône de l'app,
comme une application de messagerie. Il sait le faire tant qu'il tourne
(`GET /notifications/unread-count`).

Mais **app fermée, aucun code client ne s'exécute** : le badge ne peut être posé
que par la charge de la notification elle-même. Il faut donc que le serveur
inclue le compteur au moment de l'envoi :

```json
{ "aps": { "alert": { … }, "badge": 7, "sound": "default" } }
```

où `7` est le nombre de notifications non lues de ce destinataire **après**
enregistrement de celle qui part. Équivalent Android : `notification.notification_count`.

**Sans cette demande** : le badge n'est juste qu'après un lancement de l'app,
c'est-à-dire exactement quand il ne sert plus à rien.

---

## B4 — `POST /programs/{id}/duplicate`

**Priorité : moyenne.**

Fonctionnalité demandée : un auteur duplique un de ses programmes pour n'avoir
qu'à le modifier.

Sans endpoint, le client doit enchaîner `GET /programs/{id}` →
`POST /programs` → **N** × `POST /programs/{newId}/schedules` → télécharger
l'image puis `POST /programs/{newId}/image/upload`. Soit une transaction
distribuée sur N+3 appels, sans rollback : si le quatrième créneau échoue, il
reste un programme à moitié copié dans la liste de l'auteur.

Demandé :

```http
POST /api/programs/{id}/duplicate
{ "title": "Yoga du soir (copie)" }   // optionnel, défaut = titre + suffixe
→ 201 ProgramDto
```

Comportement attendu : copie des métadonnées, des créneaux **et** de l'image ;
réservé à l'auteur (403 sinon) ; le nouveau programme est créé en **brouillon**
et **non public**.

Ce dernier point est important au-delà de l'ergonomie : un programme publié
immédiatement déclencherait un `AUTHOR_NEW_PROGRAM` à tous les abonnés de
l'auteur. Une duplication qui notifie est une duplication qui spamme.

**Sans cette demande** : le client implémente l'enchaînement, et en cas d'échec
partiel dépose l'utilisateur sur la page d'édition avec la liste de ce qui n'a
pas été copié. Fonctionnel, mais fragile.

---

## B5 — `createdAt` sur le fil de créneaux, et `createdSince`

**Priorité : moyenne.**

Filtre demandé : « Nouveautés » — les créneaux **publiés** dans les dernières
24 heures, à proximité.

`SlotFeedItem` ne porte aucune date de création, et `GET /slots/feed` n'a aucun
paramètre correspondant. Le filtre est donc impossible, ni côté serveur ni côté
client.

Demandé :
- `createdAt` (ISO-8601 UTC) sur chaque entrée de `GET /slots/feed` ;
- idéalement `createdSince` en paramètre de requête, pour ne pas transporter
  puis jeter les créneaux hors fenêtre.

**Sans cette demande** : le client ne livre que « séances des 24 h à venir »
(fenêtre de début, qu'il sait déjà exprimer). Les deux filtres ne répondent pas
à la même question — « qu'y a-t-il de neuf ? » n'est pas « qu'y a-t-il bientôt ? ».

---

## B6 — Catégories multiples sur `GET /slots/feed`

**Priorité : moyenne.** *(Réduite : `GET /map/bounds` couvre déjà le besoin pour
les activités — voir l'encadré.)*

L'utilisateur doit pouvoir sélectionner **plusieurs** catégories dans les filtres
de la carte.

> ### `GET /map/bounds` sait déjà le faire, et le client ne l'utilise pas
>
> `MapBoundsRequest` accepte `categoryIds` (**tableau**), `activityLevels`
> (tableau), `formats` (tableau), plus `limit`/`offset` et une bbox
> `north/south/east/west`. Il renvoie un `MapMarkersResponse { users, activities,
> programs, truncated, totalInBounds }`.
>
> Autrement dit, la multi-sélection que le client croyait impossible est servie
> depuis toujours pour les marqueurs de la carte — et par une requête en **bbox**,
> qui est précisément la forme d'un viewport, là où le client raisonne en
> centre + rayon. Quatre routes de la famille `/map` sont dans ce cas :
> `/map/bounds`, `/map/clusters`, `/map/geocode`, `/map/reverse-geocode`, plus
> `/map/nearby/{type}`. Aucune n'est appelée par l'app.
>
> **Aucune action backend requise pour ce volet** : c'est un chantier client.

Reste le vrai manque, sur le mode « Créneaux » de la carte, qui passe par
`/slots/feed` :

1. `SlotFeedRequest` n'accepte qu'un seul `categoryId` (il accepte aussi un
   `activityId` unique, que le client n'exploite pas encore) ;
2. `SlotFeedItemDto` ne renvoie **pas** de `categoryId` — seulement un
   `categoryColorRamp`, qui est une intention de teinte, pas un identifiant.

La seconde limite est la plus gênante : elle interdit même de rattraper le filtre
côté client sur la réponse en main.

Demandé :
- `categoryIds` (tableau, comme `MapBoundsRequest` le fait déjà) sur
  `GET /slots/feed` ;
- `categoryId` sur chaque entrée de la réponse.

**Sans cette demande** : en mode Créneaux, le client émet une requête par
catégorie (plafonné à 3) et fusionne en dédoublonnant par `scheduleId`. La
dégradation au-delà de 3 doit être signalée à l'utilisateur.

---

## B7 — Identifiants des membres d'un agrégat de `/map/activities`

**Priorité : basse.**

Au zoom maximal, la maille d'agrégation vaut encore ~1 km : deux activités plus
proches restent groupées, et taper la pastille ne peut pas les faire apparaître —
mesuré en production, trois taps sans effet. C'était le seul marqueur de la carte
dont le tap pouvait ne rien produire.

Le schéma `MapCluster` porte `latitude`, `longitude`, `count`, `type`,
`categoryIcon` et les quatre bornes `boundsNorth/South/East/West` — mais **aucun
identifiant de membre**. Le client ne peut donc pas savoir ce que le groupe
contient.

Demandé : `activityIds` (ou `userActivityIds`) sur chaque objet de `clusters`.

**Sans cette demande** : au zoom maximal, le client interroge
`GET /activities/browse` centré sur le cluster avec un rayon de 1,2 km pour
reconstituer la liste. Un aller-retour réseau de plus pour une information que la
réponse d'origine avait déjà en main.

---

## B11 — `categoryId` sur `MapActivityMarkerDto`

**Priorité : haute — un filtre de la carte est inopérant sans lui.**

`GET /map/activities` renvoie des `MapActivityMarkerDto`, dont les champs sont
`activityId`, `activityName`, `activitySlug`, `categoryName`, `categoryIcon`,
`categoryColorRamp`, `lat`, `lng`, `distanceKm`, `programCount`,
`scheduleCount`… — **sans `categoryId`**.

Le client lit pourtant `categoryId` sur ces marqueurs, et sa règle de filtrage
écarte tout marqueur dont l'identifiant de catégorie est absent :

```dart
if (query.categoryIds.isNotEmpty) {
  final id = activity.categoryId;          // toujours null sur un marqueur
  if (id == null || !query.categoryIds.contains(id)) return false;
}
```

Conséquence : **dès qu'une catégorie est sélectionnée dans les filtres, tous les
badges d'activité disparaissent de la carte.** Le filtre est cassé aujourd'hui, en
choix unique comme en sélection multiple. Aucun paramètre de
`MapActivitiesRequest` ne permet de contourner : cette requête accepte
`userLat`/`userLng`/`radiusMeters`, une bbox, `limit` et `zoom` — **aucun filtre
de catégorie**.

Demandé : `categoryId` (UUID) sur `MapActivityMarkerDto`. Idéalement aussi
`categoryIds` en paramètre de `MapActivitiesRequest`, pour filtrer en base plutôt
qu'à l'arrivée — `MapBoundsRequest` le fait déjà, les deux requêtes gagneraient à
être cohérentes.

**Sans cette demande** : le client résout les catégories sélectionnées en **noms**
via le catalogue `/categories` et les compare à `categoryName` en normalisant
casse et accents. Ça fonctionne, mais c'est un rattrapage fragile — il casse au
premier renommage de catégorie et il est sensible à la langue de service.

---

## B9 — `recurrenceRule` sur les DTO de `/slots/**`

**Priorité : haute — condition de justesse de la règle B1.**

C'est le complément indispensable de **B1**, découvert en implémentant la
détection de conflits.

La source des engagements existants d'un utilisateur est
`GET /slots/mine?upcoming=true`. Or `SlotFeedItemDto` porte `startsAt` et
`endsAt`, **mais pas `recurrenceRule`** — alors que `ScheduleDto`, servi par
`/programs/{programId}`, le porte.

Conséquence : un engagement **récurrent** n'est connu du client que par sa
prochaine séance. Un utilisateur inscrit à « Yoga tous les lundis 18 h » et qui
tente de rejoindre « Escalade le lundi 18 h 30 » ne verra le conflit que si les
deux tombent la même semaine. **Le conflit de la semaine 5 passe inaperçu**, et la
règle B1 n'est alors appliquée que sur la première occurrence.

Le client ne peut pas contourner proprement : reconstituer les règles
demanderait un `GET /programs/{id}` par programme rejoint, à chaque ouverture
d'une fiche.

Demandé : `recurrenceRule` (même format RFC 5545 que `ScheduleDto`) sur
`SlotFeedItemDto` — donc sur `/slots/feed`, `/slots/mine` et `/slots/{id}`.

Utile au passage, même priorité basse : `sessionDurationMinutes` (ou simplement
un `endsAt` toujours renseigné). `endsAt` étant nullable, le client doit supposer
60 minutes quand il manque, et un verdict de conflit fondé sur une durée supposée
reste probabiliste.

**Sans cette demande** : la détection ne couvre que la première occurrence de
chaque engagement existant. L'app annonce une règle qu'elle n'applique
qu'imparfaitement, ce qui est pire qu'une règle absente.

---

## B10 — Un texte de notification, ou aucun

**Priorité : à décider ensemble (voir les questions ouvertes).**

`NotificationDto` porte `id`, `type`, `channel`, `payload`, `isRead`, `sentAt`,
`readAt`, `scheduledAt`. Il ne porte **ni `title` ni `message`**.

Le client lisait ces deux champs : ils sont donc toujours vides, et l'écran
retombe sur un libellé dérivé du type. La liste des notifications n'affiche
aujourd'hui **aucun texte venu du serveur**.

Deux issues possibles, et la seconde est celle que le client recommande :

1. **le serveur envoie `title` et `message`** — il faut alors qu'ils soient
   traduits selon `Accept-Language`, comme le sont déjà `clarificationQuestion`
   et les messages de refus ;
2. **le serveur n'envoie aucun texte**, et le client compose chaque libellé à
   partir de `type` + `payload`. C'est la solution préférée : les trois langues
   de l'app sont alors garanties par construction, un texte déjà stocké en base
   dans une langue ne peut pas être retraduit, et le rendu suit les tokens du
   design system au lieu d'une phrase figée.

L'option 2 suppose que le `payload` contienne de quoi écrire la phrase — au
minimum le titre du programme ou le nom de l'activité concernée, en plus des
identifiants de deep-link.

**Sans décision** : le client continue d'afficher un libellé générique par type,
sans le nom du programme concerné.

---

## B8 — Écho de l'en-tête `X-Request-Id`

**Priorité : basse, coût quasi nul.**

Le client pose désormais un `X-Request-Id` (16 caractères hexadécimaux) sur
chaque requête et le consigne dans son journal, avec la durée et le statut.

Si le serveur le renvoie dans la réponse **et** l'inscrit dans ses propres
journaux, une trace client et une trace serveur deviennent joignables. C'est ce
qui transforme « ça a échoué chez un utilisateur » en un incident retrouvable des
deux côtés.

Si l'en-tête est absent, le serveur peut en générer un et le renvoyer : le client
préfère le sien mais accepte celui de la réponse.

---

## Récapitulatif

| # | Demande | Priorité | Ce qui se dégrade sans elle |
|---|---|---|---|
| **B12** | **`payload` sérialisé à tort (bug)** | **bloquant** | **aucune navigation depuis une notification n'est possible** |
| B1 | `409` + conflits sur `join` | **bloquant** | règle de non-chevauchement contournable |
| **B9** | **`recurrenceRule` sur `/slots/**`** | **haute** | **conflit détecté sur la 1ʳᵉ occurrence seulement** |
| **B11** | **`categoryId` sur `MapActivityMarkerDto`** | **haute** | **filtrer par catégorie vide la carte** |
| B3 | `aps.badge` dans la charge APNs | haute | badge d'icône faux app fermée |
| B2 | `categoryColorRamp` dans le `payload` | haute | teinte de notification ≠ teinte du pin |
| B10 | texte de notification (ou décision de ne pas en envoyer) | à décider | libellé générique, sans nom de programme |
| B4 | `POST /programs/{id}/duplicate` | moyenne | duplication non atomique, copies partielles possibles |
| B5 | `createdAt` / `createdSince` sur `/slots/feed` | moyenne | filtre « Nouveautés » non livrable |
| B6 | `categoryIds` + `categoryId` sur `/slots/feed` | moyenne | multi-catégories plafonné à 3 requêtes (mode Créneaux seulement) |
| B7 | `activityIds` sur les clusters | basse | un appel réseau de rattrapage |
| B8 | écho de `X-Request-Id` | basse | pas de corrélation client ↔ serveur |

Deux demandes de la première version de ce document ont **disparu** après lecture
de la spec : `scheduledAt` existait déjà (défaut de lecture côté client, corrigé),
et la multi-sélection de catégories est déjà servie par `GET /map/bounds` pour les
activités.

## Questions ouvertes

1. **Texte des notifications** (cf. **B10**) — le serveur doit-il envoyer
   `title`/`message` traduits, ou le client doit-il composer les libellés depuis
   `type` + `payload` ? Cette décision conditionne le contenu attendu du
   `payload`.

2. **Modification d'un programme.** L'enum porte `SCHEDULE_CHANGED` et
   `ACTIVITY_UPDATED`, mais rien qui corresponde à « le titre ou la description
   d'un programme a changé ». Un abonné est-il notifié dans ce cas ? L'exigence
   produit dit oui.

3. **Suppression d'un programme.** `PROGRAM_CANCELLED` existe — est-il bien émis
   sur `DELETE /programs/{programId}`, ou seulement sur un passage de statut à
   annulé ?

4. **`GET /programs/new`** apparaît dans la spec sans réponse documentée et sans
   description. À quoi sert cette route ? Le client ne l'appelle pas.

5. **`GET /notifications/preferences`** renvoie-t-il les 30 types
   systématiquement, ou seulement ceux que l'utilisateur a déjà modifiés ? L'UI
   de réglages doit savoir si elle affiche une liste serveur ou une liste locale
   complétée par les valeurs reçues.
