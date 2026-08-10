# Réponse backend au lot 7 — conflits d'agenda, notifications, filtres de carte

> Réponse à `PROMPT_BACKEND_LOT7_2026-08.md`. Les huit demandes sont livrées,
> plus les deux volets « déjà servis » que votre document avait identifiés.
> L'ordre suit votre priorisation : bloquants, hautes, puis le reste.

---

## B12 — le `payload` sérialisé à tort : déjà corrigé, et voici depuis quand

Le bug que vous décrivez était réel, et il est corrigé : `NotificationDto`
déclare `payload` en `Map<String, Object>` (votre option 1, « la plus sûre »),
avec un commentaire qui documente le chemin Jackson fautif. Le test
d'intégration `NotificationPayloadIntegrationTest` verrouille la non-régression
sur la notification `f1000000-…-0002` du seed : le JSON métier doit être lisible
et `"bigDecimal"` ne doit jamais réapparaître.

Si vous observez encore le dump `{'array': false, …}` en production, ce n'est
pas le code actuel : c'est un déploiement antérieur au correctif. Vérifiez le
SHA déployé — même mécanique que la mise au point du lot 1.

Ce qui restait à faire, en revanche, c'était de garantir le **contenu** — voir
B2/B10 ci-dessous.

## B1 + B9 — la règle de non-chevauchement vit désormais côté serveur

### Le contrat

`POST /api/programs/{id}/join` et `POST /api/slots/{id}/join` répondent :

```http
409 Conflict
{
  "code": "SCHEDULE_CONFLICT",
  "message": "<traduite selon Accept-Language, fr/en/de>",
  "conflicts": [
    {
      "scheduleId":              "<créneau demandé>",
      "occurrenceAt":            "2026-08-17T18:30:00Z",
      "conflictingScheduleId":   "<créneau déjà rejoint>",
      "conflictingProgramId":    "<uuid>",
      "conflictingProgramTitle": "Yoga du soir",
      "conflictingOccurrenceAt": "2026-08-17T18:00:00Z",
      "conflictingEndsAt":       "2026-08-17T19:15:00Z",
      "conflictingEngagementType": "PROGRAM | SLOT",
      "conflictingUserProgramId":  "<uuid ou null>"
    }
  ],
  "timestamp": "…"
}
```

**Deux champs de plus que votre demande**, pour votre bouton « quitter » :

- `conflictingEngagementType` — dit quelle route appeler :
  `POST /programs/{conflictingProgramId}/leave` pour `PROGRAM`,
  `DELETE /slots/{conflictingScheduleId}/join` pour `SLOT` ;
- `conflictingUserProgramId` — `POST /programs/{id}/leave` exige un
  `userProgramId` dans le corps, que votre feuille de conflits n'aurait pas eu
  sans un appel de rattrapage à `GET /users/me/programs`. Il voyage donc avec le
  conflit. Nul pour un engagement `SLOT`, dont la route de sortie n'en a pas
  besoin.

Votre exemple utilisait `POST /slots/{id}/leave` : cette route n'existe pas —
quitter un créneau est `DELETE /slots/{scheduleId}/join` depuis meetDo. Le
champ `conflictingEngagementType` vous évite de la deviner.

### La sémantique, décision par décision

- **Les récurrences sont développées des deux côtés** (RFC 5545, même moteur
  ical4j que le lot 5, même fuseau `pair.recurrence.zone`). Le conflit de la
  semaine 5 entre deux hebdomadaires déphasés est vu — c'est le test
  `conflitDephase_horsPremiereOccurrence_doitEtreVu`.
- **Fenêtre de comparaison : 90 jours** à partir de maintenant, plus 6 heures en
  arrière (une séance déjà commencée occupe encore). Deux séries qui ne se
  croiseraient qu'au sixième mois ne sont pas déclarées en conflit : arbitrage
  assumé, dites-nous si le produit veut plus profond.
- **Les deux mécanismes d'engagement comptent** : inscriptions `ACTIVE` à un
  programme **et** RSVP `CONFIRMED`/`INTERESTED` sur un créneau. Le refus vaut
  sur les deux chemins d'entrée, dans les deux sens — testé croisé.
- **Durée d'une occurrence** : `endsAt - startsAt` si connus, sinon la durée
  déclarée du programme (`sessionDurationMinutes`), sinon 60 minutes par
  convention. Voir B9 pour ce que cela implique chez vous.
- **Se toucher n'est pas se chevaucher** : finir à 19 h et commencer à 19 h est
  un enchaînement. Bornes strictes.
- **`join` sans `scheduleId`** (programme entier) : ce sont **tous** les
  créneaux du programme qui sont confrontés à l'agenda.
- **Ordre des refus** : les refus propres au créneau passent d'abord (complet,
  passé, déjà rejoint…). Un créneau complet dit qu'il est complet, pas qu'il
  chevauche. Et un créneau déjà rejoint ne se conflicte jamais avec lui-même.
- **La course entre deux appareils** est fermée : la vérification se fait sous
  le même verrou pessimiste que la capacité (`ScheduleRepository.lockById`).
- **Plafond : 20 conflits** rapportés, du plus proche au plus lointain. Un
  agenda saturé produit un refus lisible, pas une liste de 400 lignes.

### B9, la condition de justesse

`SlotFeedItemDto` porte désormais `recurrenceRule` (même format que
`ScheduleDto`, sans préfixe `RRULE:`) — donc sur `/slots/feed`, `/slots/mine`
et `/slots/{id}`. Votre pré-vérification locale, si vous la gardez comme
confort d'UX, peut enfin développer les engagements récurrents.

`sessionDurationMinutes` est là aussi : mesuré quand `endsAt` existe, sinon la
durée déclarée du programme, sinon **null — pas 60**. L'API ne devine pas de
durée ; quand le champ est nul, vous savez que votre verdict local serait
probabiliste, et le serveur tranche de toute façon.

## B11 — `categoryId` sur `MapActivityMarkerDto`, et le filtre en base

- `categoryId` (UUID) est sur chaque marqueur. Votre règle de filtrage actuelle
  fonctionne sans modification — le champ qu'elle lit existe désormais.
- `categoryIds` (tableau) est accepté par `GET /map/activities`, appliqué **en
  SQL**, cumulable avec le rayon et la bbox. Les requêtes `/map/activities` et
  `/map/bounds` sont maintenant cohérentes.
- Le rattrapage par noms normalisés peut être supprimé.

Au passage, le filtre de catégorie de `/map/bounds` (couche programmes), qui
chargeait puis filtrait en Java, est descendu en base lui aussi.

## B3 — le badge voyage avec la charge push

`aps.badge` (iOS) et `notification_count` (Android) portent le nombre de non
lues du destinataire **après** enregistrement de la notification qui part —
c'était codé en dur à `1`. Zéro est une valeur légitime (c'est ainsi qu'un badge
s'efface) ; la valeur est bornée aux entiers positifs des deux plateformes.

## B2 + B10 — le contenu du `payload`, unifié

Tous les producteurs de notifications passent par un constructeur unique
(`NotificationPayload`) qui garantit trois choses :

1. **les identifiants de deep-link** — `scheduleId`, `programId`, `activityId`,
   `userActivityId`, `categoryId` selon le contexte ;
2. **de quoi écrire la phrase** — `programTitle`, `activityName`, `placeName` ;
3. **`categoryColorRamp`** — la même valeur que celle des DTO de la carte. Votre
   notification et votre pin sèment sur la même graine, donc même couleur par
   construction.

S'y ajoutent `sessionAt` (l'instant de la séance concernée, clé que le serveur
relit pour dériver `scheduledAt`) et les clés spécifiques au type
(`participantName`, `authorName`, …). Une clé dont la valeur serait nulle est
**absente**, jamais présente à `null`. UUID et instants sont toujours des
chaînes JSON.

`scheduledAt` est en outre servi pour deux types de plus : `SLOT_JOINED` et
`ACTIVITY_ALERT_MATCH`, dont le payload porte désormais une séance à venir.
`SLOT_CANCELLED` et `ATTENDANCE_PROMPT` en portent une aussi mais n'alimentent
volontairement pas `scheduledAt` : un compte à rebours vers une séance annulée
ou passée serait faux.

### B10, la décision : option 2, actée

**Le serveur n'enverra ni `title` ni `message`**, et c'est désormais documenté
comme un choix dans l'OpenAPI, pas comme un manque. Vos trois raisons sont les
nôtres : les trois langues garanties par construction, pas de texte figé en
base dans une langue de l'instant d'émission, et le rendu suit vos tokens. En
échange, le serveur s'engage sur le contenu du `payload` ci-dessus — c'est le
contrat qui rend l'option 2 tenable.

Nuance : les **push** (APNs/FCM), eux, portent un texte — il faut bien afficher
quelque chose sur un téléphone verrouillé. Ce texte était français en dur ; il
est désormais traduit selon la langue du **destinataire** — voir la section
« Textes push » ci-dessous.

## Textes push — traduits, langue par appareil

*(Livré à la suite du lot, en complément de B10.)*

Le texte d'une push ne peut pas suivre `Accept-Language` : il n'y a pas de
requête du destinataire au moment d'envoyer — la push part de la requête de
quelqu'un d'autre, ou d'un job planifié. La langue est donc une propriété de
l'**appareil**, posée à l'enregistrement du token :

```http
POST /api/notifications/devices
{ "token": "…", "platform": "IOS", "deviceName": "…", "locale": "de" }
```

- `locale` : étiquette BCP 47, ramenée à la langue servie la plus proche
  (`fr`/`en`/`de` ; hors des trois → `en`, même règle que l'en-tête). La valeur
  **retenue** est renvoyée dans le `DeviceTokenDto`.
- **Absente**, l'`Accept-Language` de la requête d'enregistrement fait foi —
  c'est l'appareil lui-même qui appelle. Sans l'un ni l'autre : français.
- **À faire côté client** : ré-enregistrer le token quand l'utilisateur change
  la langue de l'app — un ré-enregistrement sans `locale` ne touche pas à celle
  déjà posée.
- Par appareil, pas par compte : un iPad en anglais et un téléphone en allemand
  reçoivent chacun leur langue — un envoi FCM par groupe de langue.
- Au passage, les types meetDo (`SLOT_JOINED`, `SLOT_CANCELLED`,
  `ATTENDANCE_PROMPT`, `ACTIVITY_ALERT_MATCH`) ont enfin des textes push dédiés
  — ils tombaient sur « Nouvelle notification » générique.

Les appareils enregistrés avant cette colonne restent en français jusqu'à leur
prochain ré-enregistrement.

## B4 — `POST /api/programs/{id}/duplicate`

```http
POST /api/programs/{id}/duplicate
{ "title": "Yoga du soir v2" }        // optionnel
→ 201 ProgramDto
```

- Métadonnées, **tous les créneaux**, et l'image — en **une transaction** : tout
  aboutit ou rien n'est créé. L'image est copiée **physiquement** : deux
  programmes pointant le même fichier, supprimer l'image de l'un aurait cassé
  celle de l'autre.
- Réservé à l'auteur (403 sinon), copie en **brouillon non public**.
- Titre par défaut : original + « (copie) », tronqué pour tenir en 150.
- Chaque créneau copié repart à zéro : statut `OPEN`, aucun participant.
- **Aucune notification n'est émise** — la duplication ne passe pas par le
  chemin de création qui notifie les abonnés.
- Non copiés, délibérément : les médias additionnels (`ProgramMedia`, la
  demande portait sur l'image), les avis, les inscriptions.

Trouvé en chemin, à savoir : `POST /programs` notifie `AUTHOR_NEW_PROGRAM` **à
la création**, y compris pour un brouillon. Un auteur qui crée puis publie ne
notifie qu'à la création ; un abonné peut donc être notifié d'un programme
encore invisible. C'est un défaut existant, hors périmètre de ce lot — on le
signale pour votre question ouverte n° 2.

## B5 + B6 — `/slots/feed` : nouveautés et multi-catégories

Sur chaque entrée du fil (donc aussi `/slots/mine` et `/slots/{id}`) :
`createdAt`, `activityId`, `categoryId` — en plus de `recurrenceRule` et
`sessionDurationMinutes` de B9.

En paramètres de `GET /slots/feed` :

- `createdSince` (ISO-8601 UTC) — filtré en base, sur la date de **publication**.
  Votre filtre « Nouveautés » est `createdSince=now-24h`, cumulable avec
  `from`/`to` qui, eux, parlent des séances.
- `categoryIds` (tableau, répétable ou séparé par virgules) — filtré en base.
  `categoryId` singulier reste accepté (les clients déployés l'envoient) ; les
  deux se **cumulent en union**. Le plafond de 3 requêtes fusionnées peut
  disparaître.

## B7 — `activityIds` sur les clusters

Chaque objet de `clusters` (réponse de `/map/activities` avec `zoom`) porte
`activityIds` : les identifiants des activités membres, dédoublonnés. Le tap au
zoom maximal a enfin quelque chose à ouvrir, sans le `GET /activities/browse`
de rattrapage.

Deux précisions : `activityIds.length` peut être inférieur à `count` (un
marqueur = un couple (activité, lieu) — une activité sur deux lieux compte deux
fois dans `count`, une fois dans la liste) ; et le champ est `null` sur les
clusters d'**utilisateurs** de `/map/clusters`, qui n'agrègent pas des
activités.

## B8 — `X-Request-Id` : écho, génération, et journaux

- L'en-tête est **renvoyé** sur toute réponse, succès comme erreur, y compris
  les 401/403 (le filtre passe avant Spring Security — c'est justement ce
  genre d'échec qu'on veut corréler).
- **Absent, le serveur en génère un** (même forme que le vôtre : 16 hex) et le
  renvoie — vous avez dit préférer le vôtre mais accepter le nôtre.
- Chaque ligne de journal serveur porte l'identifiant : `[rid:774f91a9…]`.
  « Ça a échoué chez un utilisateur » devient un `grep`.
- Une valeur reçue qui ne ressemble pas à un identifiant (hors
  `[A-Za-z0-9_-]{8,64}`) est **remplacée**, pas nettoyée — une clé corrigée
  silencieusement corrélerait faux. En pratique, vos 16 hex passent toujours.
- CORS : `X-Request-Id` est autorisé en requête **et exposé** en réponse —
  sans quoi un client web n'aurait jamais pu lire l'écho.

---

## Vérification

- `ScheduleConflictDetectorTest` — 6 cas : chevauchement hebdomadaire, conflit
  déphasé hors première occurrence, séances qui se touchent, jours différents,
  agenda vide, auto-conflit exclu.
- `ScheduleConflictIntegrationTest` — l'enveloppe 409 de bout en bout, sur les
  **deux** chemins d'entrée, engagement pris par l'autre mécanisme.
- `NotificationPayloadTest` — deep-link + libellés + `categoryColorRamp`,
  absence des clés nulles, normalisation UUID/Instant.
- Les suites existantes passent — dont `CombinedSlotCapacityIntegrationTest`
  (l'ordre des refus est préservé : « complet » avant « conflit ») et
  `NotificationPayloadIntegrationTest` (B12).

## Vos questions ouvertes, réponses

1. **Texte des notifications** — tranché ci-dessus (B10) : option 2, le
   serveur s'engage sur le contenu du `payload` en contrepartie. Reste le texte
   des push, chantier séparé proposé.

2. **Modification d'un programme** — aujourd'hui, **aucune notification**
   n'est émise sur `PUT/PATCH /programs/{id}`. `ACTIVITY_UPDATED` n'est émis
   que sur la mise à jour d'une *activité* (`SubscriptionService`), et
   `SCHEDULE_CHANGED` n'est **jamais émis** par le code applicatif (valeur de
   seed). Si l'exigence produit dit qu'un abonné est notifié du changement de
   titre/description, c'est une demande nouvelle — chiffrable, dites-nous.

3. **Suppression d'un programme** — `DELETE /programs/{id}` **archive** le
   programme (statut `ARCHIVED`) et n'émet **rien**. `PROGRAM_CANCELLED`
   n'est jamais émis par le code applicatif non plus (seed uniquement). Seule
   la suppression d'un **créneau** avec participants notifie (`SLOT_CANCELLED`).
   Même statut que la question 2 : à spécifier si le produit le veut.

4. **`GET /programs/new`** — c'est un pare-chocs, pas une fonctionnalité : la
   route répond 400 avec « Utilisez POST /api/programs ». Elle existe parce que
   `GET /programs/{programId}` matcherait sinon « new » comme un UUID et
   répondrait un 500 confus. Ne l'appelez pas ; elle sera documentée comme
   telle dans l'OpenAPI.

5. **`GET /notifications/preferences`** — renvoie **uniquement les types que
   l'utilisateur a déjà modifiés**, pas les 30. Votre UI de réglages doit donc
   afficher une liste locale complète, surchargée par les valeurs reçues ; le
   défaut serveur pour un type absent est « tout activé, fréquence immédiate »
   (`NotificationService.defaultPref`).

## Suite

Trois chantiers avaient été identifiés en faisant ce lot. Le premier — la
traduction des textes push selon la langue du destinataire — est livré (voir
« Textes push » ; action côté client : envoyer `locale` sur
`POST /notifications/devices` et ré-enregistrer au changement de langue).
Restent, si le produit les confirme : le `AUTHOR_NEW_PROGRAM` émis sur les
brouillons (cf. B4), et les notifications de modification/suppression de
programme (questions 2 et 3).
