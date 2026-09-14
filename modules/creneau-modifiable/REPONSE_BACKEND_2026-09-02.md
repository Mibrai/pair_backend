# Réponse à la demande du 02/09 — `SCHEDULE_CHANGED` part depuis le 12/09, et les trois questions de contrat sont tranchées

**Date :** 2026-09-14 — **douze jours après votre demande**, et nous vous le devons : elle n'avait
jamais reçu de réponse écrite.
**Fait suite à :** `PROMPT_BACKEND_2026-09-02.md`

> **§1 — `SCHEDULE_CHANGED` est émis depuis le 12/09** (`f0522ba`, fiche P-BL-06), dans la forme que
> vous décriviez : inscrits seulement, une notification par modification, rien si l'heure ou le lieu
> n'ont pas bougé. **Il manquait le `collapse-id`**, livré aujourd'hui (§1.3).
>
> **§2 — le `PUT` fusionne.** Un champ absent ou `null` reste ce qu'il était. C'est désormais écrit
> au contrat. En le vérifiant, nous avons trouvé et corrigé une exception qui aurait pris en défaut
> un client qui fusionne (§2.2).
>
> **§3 — `showExactAddress` se relit sur `ScheduleDto`**, pour l'organisateur seul.
>
> **§4 — `isPubliclyShareable` : c'était votre cas 2, le pire.** La clé était ignorée en silence, et
> tous les créneaux créés depuis l'app sont partis partageables. Elle est honorée à la création
> depuis aujourd'hui (§4).

---

## 1. `SCHEDULE_CHANGED`

### 1.1 Ce qui est en place depuis le 12/09

| Votre demande | État |
|---|---|
| Destinataires : les inscrits du créneau, pas les abonnés du programme | ✅ `CONFIRMED`, `INTERESTED` et `WAITLISTED` du créneau, plus les inscrits du programme structuré **rattachés à ce créneau**. Pas les abonnés. |
| Pas l'auteur de son propre geste | ✅ |
| Type `SCHEDULE_CHANGED`, charge de séance avec `scheduleId` | ✅ `NotificationPayload.ofSchedule` : `scheduleId`, `programId`, `programTitle`, `activityId`, `activityName`, `categoryId`, `categoryColorRamp`, `placeName`, `sessionAt`. |
| Une notification par modification, pas une par champ | ✅ |
| Rien si rien n'a changé | ✅ Verrouillé aujourd'hui par un test qui renvoie **tous** les champs à l'identique, comme votre formulaire pré-rempli (§5). |
| `collapse-id` stable par créneau | ❌ **manquait** → livré, §1.3 |

**Ce que la charge porte en plus**, et que votre demande n'avait pas :

- **`changedFields`** : `["TIME"]`, `["PLACE"]` ou les deux ;
- **l'ancienne valeur de ce qui a bougé** : `previousStartsAt`, `previousEndsAt`,
  `previousPlaceName`, et `previousAddress` **seulement si l'ancienne adresse était diffusable** ;
- **`watchShifted: true`** quand une veille retour armée sur la séance a suivi le nouvel horaire.

La notification part aussi **par e-mail**, et passe outre les heures de silence : le type est
classé critique et destiné à l'e-mail.

### 1.2 Votre question de conception : quels champs notifient

Nous avons suivi votre avis, à un détail près.

- **Notifient** : `startsAt`, `endsAt` (catégorie `TIME`) ; `placeName`, `placeType`, la position,
  l'adresse **diffusable** (catégorie `PLACE`).
- **Ne notifient pas** : `welcomeNote`, `accessibilityTags`, `maxParticipants`,
  `isOpenToPartners`, `primaryLanguage`, `level`, `recurrenceRule`.
- **Le détail** : l'adresse est comparée sur sa **forme diffusable**, pas sur la colonne. Rendre
  visible l'adresse exacte d'un domicile est un changement de visibilité, pas un déplacement. Il ne
  produit une notification que si l'adresse que voient les inscrits change réellement.

Robinet de retour arrière : `pair.notifications.schedule-changed.enabled=false` coupe l'émission
sans redéploiement.

### 1.3 Le `collapse-id`, livré aujourd'hui

- **APNs** : `apns-collapse-id: slot-changed-<scheduleId>` ;
- **Android** : le même identifiant en `tag` de la notification, qui produit le même remplacement
  dans le tiroir.

**Réservé à `SCHEDULE_CHANGED`.** Une annulation du même créneau n'est pas regroupée avec ses
modifications : elle remplacerait la bannière, ou la bannière la remplacerait, et c'est
l'annulation qui disparaîtrait.

## 2. Le `PUT` fusionne

### 2.1 La règle, désormais au contrat

`PUT /api/programs/{programId}/schedules/{scheduleId}` porte maintenant une description OpenAPI qui
dit :

- **un champ absent ou `null` reste ce qu'il était** ;
- **pour retirer une valeur** : chaîne vide pour `primaryLanguage` et `level`, liste vide pour
  `accessibilityTags`. `endsAt` ne se retire pas ;
- passer `placeType` à `ONLINE` efface la position. Revenir en présentiel exige `lat`/`lng`.

**Votre écran, qui renvoie tout, reste correct.** Vous pouvez aussi n'envoyer que ce qui change.
Dans ce cas, le §3 de votre demande cesse d'être bloquant, et il est de toute façon livré.

### 2.2 L'exception que la fusion révélait, corrigée

`addressPublic` n'était enregistrée, pour un lieu non public, que si **le même corps** portait
`showExactAddress: true`. Un client qui fusionne et n'envoie que la nouvelle adresse d'un domicile
**déjà montré** la voyait **ignorée, sans erreur**. Votre écran y échappait parce qu'il renvoie tout.

L'adresse est désormais jugée sur la valeur **effective** : celle du corps si elle y est, sinon
celle déjà en place.

### 2.3 Vos autres questions du §5

- **Capacité sous le nombre d'inscrits** : acceptée. **Personne n'est désinscrit**, le créneau passe
  `FULL` et n'accepte plus d'entrée. Si la capacité remonte, la file d'attente est promue dans la
  même requête. Choisir qui perd sa place à la place de l'organisateur serait pire que ce créneau à
  six inscrits pour quatre places.
- **`409 SCHEDULE_CONFLICT` sur cette route : il n'arrive jamais.** Le chevauchement d'agenda n'est
  vérifié qu'à l'inscription (`POST /slots/{id}/join`, `POST /programs/{id}/join`), pas quand un
  organisateur déplace son créneau. Votre affichage du message reste juste là où le code existe.
  Sur ce `PUT`, les refus possibles sont des `400 VALIDATION_ERROR` (fin avant début, début déplacé
  dans le passé, séance terminée, lieu physique sans coordonnées) et `SLOT_CANCELLED_READONLY`.
- **`DELETE`** : nous prenons note que l'écran ne l'appelle pas. Annuler reste
  `POST /slots/{id}/cancel`, et un `DELETE` sur un créneau à inscrits passe par le même chemin
  d'annulation depuis le 12/09.

## 3. `showExactAddress` sur `ScheduleDto`

- **`true` ou `false` pour l'organisateur**, `null` pour tout autre lecteur. Un autre lecteur voit
  déjà l'effet du réglage : l'adresse, ou son absence.
- Sur `ScheduleDto` seulement, donc `GET /api/programs/{id}` et les réponses des routes de créneau
  de programme. **Pas sur `SlotFeedItemDto`**, comme vous le demandiez.

**La phrase d'excuse de votre écran peut partir** : la case peut repartir de l'état réel.

## 4. `isPubliclyShareable` — votre cas 2

**Ce qui se passait** : la clé n'était déclarée par aucun des deux corps, et Jackson ignore une clé
inconnue sans erreur. L'entité vaut `true` par défaut. **Chaque créneau créé depuis l'app est donc
parti partageable**, même quand l'hôte avait décoché la case.

**La portée réelle est limitée**, et nous la mesurons honnêtement. Un créneau « partageable » n'a
pas de page publique tant que son hôte n'a pas demandé le lien (`GET /slots/{id}/share-link`), et
personne d'autre que lui ne peut le fabriquer. La case décochée n'a donc rien exposé d'elle-même :
elle n'a simplement pas empêché l'hôte de partager ensuite.

**Ce qui change** :

- **`POST /programs/{id}/schedules`** : `isPubliclyShareable` est lu. `false` crée un créneau non
  partageable ; absent ou `null`, le défaut `true` comme avant ;
- **`PUT`** : la clé reste **ignorée**. `PATCH /slots/{id}/shareable` est le seul chemin après la
  création, et c'est ce que vous faisiez déjà en ne la renvoyant pas. Écrit au contrat des deux
  routes ;
- **`/quick-slots`** : inchangé. Le chemin court n'a pas de case, il prend le défaut.

**Les créneaux déjà créés ne sont pas repris** : nous ne savons pas ce que chaque hôte avait coché.
Un hôte qui veut couper le partage d'un créneau existant a la route dédiée.

## 5. Vérification

**Tests ajoutés** :

- `ContratModificationCreneauIntegrationTest` (7) :
  - fusion d'un champ seul ;
  - formulaire renvoyé à l'identique sans notification ;
  - adresse seule d'un domicile déjà montré prise en compte ;
  - `showExactAddress` rendu `true`/`false` à l'organisateur et absent pour un tiers ;
  - `isPubliclyShareable` honoré à la création (`false`, et absent qui vaut `true`), ignoré par le
    `PUT`.
- `RegroupementCreneauModifieTest` (3) : l'identifiant de regroupement par créneau, jamais pour une
  annulation.

**Déjà en place depuis le 12/09** : `ScheduleChangeNotificationIntegrationTest` (6). Il vérifie
l'ancienne heure dans la charge, la file d'attente prévenue d'un changement de lieu, le silence sur
la note d'accueil, le silence sur une modification refusée, l'organisateur non notifié et l'adresse
privée absente de la charge.

**Côté app, après déploiement** :

- `GET /v3/api-docs` : `ScheduleDto.showExactAddress`, `CreateScheduleRequest.isPubliclyShareable`
  et la description du `PUT` ;
- trois modifications d'affilée du même créneau sur un iPhone inscrit : **une** bannière, le texte
  de la dernière.
