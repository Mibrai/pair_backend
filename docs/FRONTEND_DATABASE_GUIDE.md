# Guide base de données & abonnements — Frontend

Ce document explique, côté frontend, comment est structurée la base de données de Pair
aujourd'hui et détaille la nouvelle fonctionnalité **abonnements** ajoutée récemment
(migration `V36`). Pour le détail SQL colonne par colonne de chaque table, voir
`docs/DATABASE_SCHEMA.md` — ce document-ci se concentre sur ce dont le frontend a besoin :
les concepts, les DTOs renvoyés par l'API, et les identifiants à faire circuler entre les écrans.

---

## 1. Vue d'ensemble conceptuelle

Point important à bien intégrer : **`Activity` n'est pas "l'activité créée par un
utilisateur"**. C'est un référentiel partagé (comme "Football", "Yoga") sans notion de
propriétaire — deux utilisateurs différents peuvent pratiquer la même `Activity`.

**`UserActivity`** est l'entité qui représente réellement "l'activité créée/pratiquée par
un utilisateur" au sens produit : c'est elle qui porte le niveau, le format, la description
personnalisée, et c'est elle qui a un **auteur** (`UserActivity.user`). C'est aussi elle qui
sert d'ancrage à `Program` : un programme est toujours rattaché à une `UserActivity`, jamais
directement à une `Activity`.

```
Category
   └── Activity (référentiel partagé, PAS d'auteur)
          └── UserActivity  ← "l'activité" au sens produit, a un auteur (user)
                 ├── Program (créé par l'auteur de la UserActivity)
                 │      ├── Schedule (créneaux)
                 │      └── UserProgram (participants inscrits au programme)
                 └── Subscription (type USER_ACTIVITY) ← NOUVEAU

User
   ├── UserActivity (activités qu'il pratique / a créées)
   ├── UserProgram (programmes auxquels il participe)
   ├── Notification / NotificationPref
   └── Subscription (abonnements qu'il a faits, ou dont il est la cible en tant qu'auteur) ← NOUVEAU
```

**Participation** : si une `UserActivity` n'a aucun `Program`, l'utilisateur intéressé
participe directement en ajoutant la même `Activity` à son propre profil (sa propre
`UserActivity`). Si elle a des `Program`, il rejoint un programme précis via `UserProgram`
(`POST /api/programs/{id}/join`).

---

## 2. Entités clés et DTOs correspondants

| Entité | DTO API | Endpoint principal | Notes |
|---|---|---|---|
| `Category` | `CategoryDto(id, name, icon, colorRamp)` | `GET /api/categories` | Référentiel, pas d'abonnement direct à une activité précise, seulement à la catégorie entière. |
| `Activity` | `ActivityDto(id, name, slug, description, icon, parentId, category)` | `GET /api/activities` | Référentiel partagé, sans auteur. |
| `UserActivity` | `UserActivityDto(id, activity, visibleOnMap, customDescription, level, format, createdAt, programs)` | `GET /api/users/me/activities` (uniquement les siennes) | ⚠️ Ne contient **pas** l'id ou le nom du propriétaire — voir limite connue plus bas. |
| `Program` | `ProgramDto(id, title, description, status, isPublic, organizerId, organizerName, organizerAvatarUrl, userActivityId, activityName, activityIcon, categoryId, categoryName, ...)` | `GET /api/programs/{id}`, `GET /api/users/me/programs`, `GET /api/programs/nearby` | **C'est la source la plus fiable pour récupérer `organizerId` (= auteur), `userActivityId` et `categoryId` en un seul appel.** |
| `Schedule` | `ScheduleDto(id, placeName, placeType, lat, lng, displayAddress, startsAt, endsAt, recurrenceRule, maxParticipants)` | inclus dans `ProgramDto.schedules` | |
| `UserProgram` | `UserProgramDto(id, userId, programId, programTitle, scheduleId, status, ...)` | `POST /api/programs/{id}/join`, `GET /api/users/me/programs` | Modèle de participation à un programme. Inchangé par cette mise à jour. |
| `Notification` | `NotificationDto(id, type, channel, payload, isRead, sentAt, readAt)` | `GET /api/notifications` | `payload` est un JSON libre dont la forme dépend de `type` (voir §4). |
| `NotificationPref` | `NotificationPref(user, notificationType, emailEnabled, pushEnabled, frequency)` | `GET/PUT /api/notifications/preferences` | Une préférence par type de notification (`emailEnabled` contrôle bien l'envoi email pour ce type précis). |
| `Subscription` **(nouveau)** | `SubscriptionDto` | voir §3 | |

---

## 3. Nouveauté : Abonnements (`Subscription`)

Trois types d'abonnement, tous portés par une seule table/entité `Subscription` avec un
discriminant `type` :

| Type | Cible | Ce que ça déclenche |
|---|---|---|
| `AUTHOR` | un `User` (l'auteur) | notifié quand cet auteur crée une nouvelle `UserActivity` ou un nouveau `Program` |
| `USER_ACTIVITY` | une `UserActivity` précise | notifié quand cette activité change, ou qu'un nouveau `Program` y est ajouté |
| `CATEGORY` | une `Category` | notifié quand une nouvelle `UserActivity` est créée dans cette catégorie (par n'importe quel utilisateur) |

### Endpoints

Tous nécessitent l'authentification (`Authorization: Bearer <token>`).

| Méthode | Path | Body | Réponse |
|---|---|---|---|
| `POST` | `/api/users/{userId}/subscription` | — | `201` `SubscriptionDto` |
| `DELETE` | `/api/users/{userId}/subscription` | — | `204` |
| `POST` | `/api/user-activities/{userActivityId}/subscription` | — | `201` `SubscriptionDto` |
| `DELETE` | `/api/user-activities/{userActivityId}/subscription` | — | `204` |
| `POST` | `/api/categories/{categoryId}/subscription` | — | `201` `SubscriptionDto` |
| `DELETE` | `/api/categories/{categoryId}/subscription` | — | `204` |
| `GET` | `/api/users/me/subscriptions` | — | `200` `SubscriptionDto[]` — tous mes abonnements, tous types confondus |

### `SubscriptionDto`

```jsonc
{
  "id": "uuid",
  "type": "AUTHOR | USER_ACTIVITY | CATEGORY",
  "targetAuthorId": "uuid | null",
  "targetAuthorName": "string | null",
  "targetUserActivityId": "uuid | null",
  "targetActivityName": "string | null",
  "targetCategoryId": "uuid | null",
  "targetCategoryName": "string | null",
  "createdAt": "instant"
}
```

Seuls les 2 champs correspondant au `type` sont renseignés, les autres sont `null` — pratique
pour un rendu générique côté frontend (switch sur `type`).

### Codes d'erreur

| Code HTTP | Quand |
|---|---|
| `403 FORBIDDEN` | tentative de s'abonner à soi-même comme auteur |
| `404 NOT_FOUND` | auteur / `UserActivity` / catégorie / abonnement (pour un `DELETE`) introuvable |
| `409 CONFLICT` | déjà abonné à cette cible (double `POST`) |

### Où récupérer les IDs cibles

- **`targetAuthorId` (auteur)** et **`targetUserActivityId`** : disponibles directement dans
  `ProgramDto.organizerId` et `ProgramDto.userActivityId` — dès qu'on affiche un programme,
  on a tout pour proposer "s'abonner à l'auteur" et "s'abonner à cette activité".
- **`targetCategoryId`** : disponible dans `ProgramDto.categoryId`, ou directement via
  `GET /api/categories`.

---

## 4. Nouveaux types de notification

`NotificationDto.type` peut désormais valoir, en plus des types existants
(`NEW_MESSAGE`, `NEW_MATCH`, `NEARBY_PROGRAM`, `PEER_RECOMMENDATION`, `PROGRAM_REVIEW`,
`BADGE_EARNED`, `PROGRAM_REMINDER`, `PROGRESSION_REMINDER`, `ACCOUNT_VERIFICATION`,
`PASSWORD_RESET`, `MODERATION_ACTION`) :

| Type | Déclenché quand | Clés dans `payload` |
|---|---|---|
| `NEW_FOLLOWER` | quelqu'un s'abonne à vous comme auteur (existait déjà côté enum, jamais déclenché avant) | `subscriberId`, `followerName` |
| `AUTHOR_NEW_ACTIVITY` | un auteur que vous suivez crée une nouvelle `UserActivity` | `authorId`, `authorName`, `userActivityId`, `activityName` |
| `AUTHOR_NEW_PROGRAM` | un auteur que vous suivez crée un nouveau `Program` | `authorId`, `authorName`, `programId`, `programTitle` |
| `ACTIVITY_UPDATED` | une `UserActivity` que vous suivez a été modifiée | `userActivityId`, `activityName` |
| `ACTIVITY_NEW_PROGRAM` | un nouveau `Program` a été créé sur une `UserActivity` que vous suivez | `userActivityId`, `programId`, `programTitle` |
| `CATEGORY_NEW_ACTIVITY` | une nouvelle `UserActivity` a été créée dans une catégorie que vous suivez | `categoryId`, `userActivityId`, `activityName` |

Le déclenchement, la préférence email/push par type (`NotificationPref`) et l'affichage
in-app (`GET /api/notifications`) fonctionnent exactement comme pour les types existants —
aucun changement d'API sur `/api/notifications/*`.

⚠️ **Limite connue** : `EmailService.sendNotificationEmail()` est aujourd'hui un stub qui ne
fait que journaliser (les emails passent par un digest Quartz non encore implémenté pour ces
nouveaux types). Concrètement : la préférence "recevoir par email" est bien respectée pour
décider *si* on envoie, mais aucun email réel n'est encore envoyé pour ces 6 types. À garder
en tête si le produit veut communiquer "vous recevrez un email".

---

## 5. Limite connue : découverte du `userActivityId` d'un autre utilisateur

`UserActivityDto` (retourné par `GET /api/users/me/activities`) ne contient pas l'id ou le
nom du propriétaire, et l'API carte (`MapActivityBadgeDto`) expose l'id de l'`Activity`
partagée (le référentiel), pas celui de la `UserActivity` de la personne affichée sur la
carte. Aujourd'hui, la seule source fiable de `userActivityId` d'un tiers est
`ProgramDto.userActivityId` (donc il faut que cette personne ait déjà créé au moins un
programme). Si le produit veut permettre "s'abonner à l'activité de cette personne" avant
qu'elle n'ait créé de programme (ex. depuis un profil public ou la carte), il faudra un
nouvel endpoint côté backend — à signaler si ce parcours est nécessaire.

---

## 6. Nettoyage effectué (sans impact frontend)

Les entités `ProgramEnrollment`, `ProgramProgress` et `EnrollmentStatus` ont été supprimées
(tables `program_enrollments`, `program_progress` droppées). **Aucun impact** : ces entités
n'étaient exposées par aucun endpoint et n'étaient jamais réellement utilisées — l'inscription
à un programme (`POST /api/programs/{id}/join`, `GET /api/users/me/programs`, etc.) repose et
a toujours reposé sur `UserProgram`/`UserProgramDto`, qui reste inchangé.
