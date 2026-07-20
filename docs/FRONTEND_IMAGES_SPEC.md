# Images (avatar, icône d'activité, image de programme) — Spec Frontend

Ce document décrit les endpoints permettant à l'utilisateur d'**ajouter** et de
**supprimer** une image représentative sur trois entités :

- sa **photo de profil** (`User.avatarUrl`)
- l'**icône représentative** d'une **activité** (`Activity.icon`)
- l'**image représentative** d'un **programme** (`Program.imageUrl`) — **nouveau champ**

Les endpoints d'upload pour User et Activity existaient déjà. Ce qui est **nouveau** dans
ce lot :
- la suppression (`DELETE`) pour les trois entités,
- l'ajout complet (upload + suppression) pour `Program`, qui n'avait aucune image
  représentative jusqu'ici (migration `V37__add_program_image_url.sql`, colonne
  `programs.image_url`).

Toutes les routes ci-dessous nécessitent d'être authentifié (`Authorization: Bearer <token>`).

---

## 1. Fonctionnement commun (upload)

Les trois endpoints d'upload suivent le même contrat :

- `Content-Type: multipart/form-data`
- Champ de formulaire : **`file`**
- Contraintes de fichier (validées côté serveur via détection MIME réelle, pas juste
  l'extension) :
  - Types acceptés : `image/jpeg`, `image/jpg`, `image/png`, `image/webp`
  - Taille max : **10 Mo**
  - Le serveur redimensionne automatiquement (max 1920×1080) et recompresse l'image
    (qualité ~0.85) avant stockage — inutile de compresser côté client, mais ça reste
    recommandé pour réduire le temps d'upload.
- En cas de fichier invalide : `400 Bad Request`
  ```json
  { "code": "BAD_REQUEST", "message": "Invalid file type. Only JPEG, PNG, and WebP images are allowed. Detected: ...", "timestamp": "..." }
  ```
- L'URL renvoyée dans la réponse a toujours la forme `/api/media/files/<type>/<uuid>.<ext>`
  (ex. `/api/media/files/user_avatar/3f1e...jpg`). C'est une **URL relative à l'API** —
  préfixer avec la base URL du back pour l'afficher (`<API_BASE_URL>/api/media/files/...`).
  Cette route de lecture (`GET /api/media/files/**`) nécessite aussi d'être authentifié.

## 2. Fonctionnement commun (suppression)

Les trois endpoints `DELETE` :
- suppriment le fichier physiquement stocké côté serveur,
- réinitialisent le champ correspondant (voir détail par entité ci-dessous),
- renvoient l'entité mise à jour (pas de `204 No Content`) pour permettre un rafraîchissement
  immédiat de l'UI sans second appel.
- Appeler `DELETE` alors qu'il n'y a **pas** d'image existante n'est pas une erreur : c'est
  un no-op qui renvoie simplement l'entité (avec le champ déjà à `null`/valeur par défaut).

---

## 3. Photo de profil — `User.avatarUrl`

### Upload (existant, inchangé)
`POST /api/users/me/avatar`

Réponse `200 OK` → `UserPrivateDto` complet (voir §6) avec `avatarUrl` mis à jour.

### Suppression (nouveau)
`DELETE /api/users/me/avatar`

- Pas de body.
- Réponse `200 OK` → `UserPrivateDto` complet avec `avatarUrl: null`.
- Aucune restriction de propriétaire à gérer côté frontend : l'utilisateur ne peut agir
  que sur `/me`.

---

## 4. Icône d'activité — `Activity.icon`

⚠️ Contrairement à `User` et `Program`, `Activity` est un **référentiel partagé** (ex.
"Football", "Yoga"), pas une entité possédée par un utilisateur précis. Les endpoints
existants ne vérifient **pas** de propriétaire — à confirmer avec le back si un rôle
admin doit restreindre l'accès à ces routes avant de les exposer dans une UI grand public.

### Upload (existant, inchangé)
`POST /api/activities/{activityId}/icon/upload`

Réponse `200 OK` → `ActivityDto` (voir §6) avec `icon` mis à jour.

### Définir manuellement (existant, inchangé — pas un upload de fichier)
`PATCH /api/activities/{activityId}/icon?icon=<valeur>`

### Suppression (nouveau)
`DELETE /api/activities/{activityId}/icon`

- Pas de body.
- Réinitialise `icon` à la valeur par défaut **`"sports"`** (il n'y a jamais de `null` sur
  ce champ — `icon` est `NOT NULL` en base).
- Réponse `200 OK` → `ActivityDto` avec `icon: "sports"`.

---

## 5. Image représentative de programme — `Program.imageUrl` (entièrement nouveau)

Jusqu'ici, `Program` n'avait aucune image représentative unique : seulement une galerie
(`Program.media` / `ProgramMediaDto[]`, plusieurs photos/vidéos ordonnées) et un
`organizerAvatarUrl` (copie de l'avatar de l'organisateur, pas une image du programme).
Le nouveau champ `imageUrl` est indépendant de cette galerie — c'est **une seule image de
couverture**, affichée par exemple en vignette dans les listes de programmes.

### Upload
`POST /api/programs/{programId}/image/upload`

- Réservé au **propriétaire** du programme (celui qui a créé la `UserActivity` associée).
  Si l'appelant n'est pas propriétaire → `403 Forbidden`.
- Si `programId` inconnu → `404 Not Found`.
- Réponse `200 OK` → `ProgramDto` complet (voir §6) avec `imageUrl` mis à jour.
- Uploader une nouvelle image alors qu'une image existe déjà **remplace** simplement le
  champ `imageUrl` en base — l'ancien fichier physique n'est **pas** automatiquement
  supprimé du stockage dans ce cas précis (seul le `DELETE` explicite nettoie le fichier).
  Si l'UI propose "changer l'image", il est donc préférable de faire un `DELETE` suivi d'un
  nouvel upload plutôt qu'un upload direct, pour éviter d'accumuler des fichiers orphelins.

### Suppression
`DELETE /api/programs/{programId}/image`

- Réservé au propriétaire du programme (mêmes règles `403`/`404` que ci-dessus).
- Réponse `200 OK` → `ProgramDto` complet avec `imageUrl: null`.

---

## 6. DTOs concernés (champs pertinents uniquement)

### `UserPrivateDto` (`GET/PUT /api/users/me`, `POST/DELETE /api/users/me/avatar`)
```jsonc
{
  "id": "uuid",
  "displayName": "string",
  "bio": "string | null",
  "avatarUrl": "string | null",   // ex: "/api/media/files/user_avatar/xxx.jpg"
  // ... autres champs inchangés
}
```

### `ActivityDto` (`.../activities`, `.../icon`, `.../icon/upload`)
```jsonc
{
  "id": "uuid",
  "name": "string",
  "slug": "string",
  "description": "string | null",
  "icon": "string",   // valeur par défaut "sports", ou une URL "/api/media/files/activity_icon/xxx.jpg"
  "parentId": "uuid | null",
  "category": { "id": "uuid", "name": "string", "icon": "string", "colorRamp": "string" }
}
```
Note : `icon` peut être soit un mot-clé logique (ex. `"sports"`, utilisé par le frontend
pour choisir une icône locale), soit une URL d'image uploadée. Le frontend doit déjà gérer
ce double sens puisque c'était vrai avant ce changement — la suppression ramène simplement
au premier cas.

### `ProgramDto` (`GET/POST/PUT /api/programs/...`) — **nouveau champ `imageUrl`**
```jsonc
{
  "id": "uuid",
  "title": "string",
  "description": "string | null",
  "status": "DRAFT | PUBLISHED | ARCHIVED",
  "isPublic": true,
  "organizerId": "uuid",
  "organizerName": "string",
  "organizerAvatarUrl": "string | null",
  "imageUrl": "string | null",   // 🆕 image représentative du programme (distincte de organizerAvatarUrl et de media[])
  "userActivityId": "uuid",
  "activityName": "string",
  "activityIcon": "string",
  "categoryId": "uuid | null",
  "categoryName": "string | null",
  "nextSessionAt": "instant | null",
  "createdAt": "instant",
  "updatedAt": "instant | null",
  "schedules": [ /* ScheduleDto[] */ ],
  "media": [ /* ProgramMediaDto[] — galerie, inchangée, pas liée à imageUrl */ ],
  "averageScore": "number | null",
  "reviewCount": "number",
  "enrolledCount": "number"
  // ... champs V26 inchangés (durationWeeks, sessionsPerWeek, etc.)
}
```

`imageUrl` apparaît désormais dans **toutes** les réponses contenant un `ProgramDto`
(création, lecture, mise à jour, listing `GET /api/programs`, `GET /api/users/{id}/programs`,
programmes à proximité, etc.) — c'est un champ additif, aucune réponse existante n'est cassée.

---

## 7. Récapitulatif des routes

| Entité    | Action      | Méthode | Route                                      | Body                        | Auth propriétaire |
|-----------|-------------|---------|---------------------------------------------|------------------------------|--------------------|
| User      | Upload      | POST    | `/api/users/me/avatar`                      | `multipart/form-data: file` | implicite (`/me`)  |
| User      | Suppression | DELETE  | `/api/users/me/avatar`                      | —                             | implicite (`/me`)  |
| Activity  | Upload      | POST    | `/api/activities/{activityId}/icon/upload`  | `multipart/form-data: file` | non vérifiée ⚠️     |
| Activity  | Set manuel  | PATCH   | `/api/activities/{activityId}/icon`         | query param `icon`           | non vérifiée ⚠️     |
| Activity  | Suppression | DELETE  | `/api/activities/{activityId}/icon`         | —                             | non vérifiée ⚠️     |
| Program   | Upload      | POST    | `/api/programs/{programId}/image/upload`    | `multipart/form-data: file` | oui (403 sinon)    |
| Program   | Suppression | DELETE  | `/api/programs/{programId}/image`           | —                             | oui (403 sinon)    |
| User      | Lecture (autre utilisateur) | GET | `/api/users/{id}`                    | —                             | n/a (lecture publique) |
| User      | Activités publiques (autre utilisateur) | GET | `/api/users/{id}/activities` | —                     | n/a (lecture publique) |

## 8. Erreurs communes

Toutes les erreurs suivent le format `ErrorResponse` global :
```json
{ "code": "STRING", "message": "string", "timestamp": "instant" }
```

| Code HTTP | Cas                                                                 |
|-----------|----------------------------------------------------------------------|
| 400       | Fichier vide, type MIME non autorisé, fichier > 10 Mo                |
| 401       | Token manquant / invalide                                            |
| 403       | (Program uniquement) l'utilisateur n'est pas propriétaire            |
| 404       | `programId` / `activityId` inconnu                                   |

---

## 9. Correctif : consultation de l'avatar / des icônes d'un **autre** utilisateur

Ce correctif (voir `docs/BACKEND_PROFILE_FIX.md`) concerne directement l'affichage des
images documentées ci-dessus sur le **profil public d'un autre utilisateur** — jusqu'ici
cassé, donc `avatarUrl` et les `activityIcon` d'un tiers n'étaient tout simplement pas
consultables.

### 9.1 `GET /api/users/{id}` — 500 corrigé

Avant ce correctif, cette route renvoyait systématiquement `500 INTERNAL_ERROR` pour tout
utilisateur possédant un badge dont la catégorie ou le type de condition en base ne
correspondait à aucune valeur des enums `BadgeCategory` / `BadgeConditionType` côté backend
(cas réel : les badges seedés `CREATION`/`SOCIAL`/`REPUTATION`/`ACTIVITY` et des
`condition_type` comme `PROGRAMS_CREATED`). Concrètement, **aucun profil public d'un autre
utilisateur ne pouvait s'afficher** dès que cet utilisateur avait un badge — donc son
`avatarUrl` restait inaccessible pour le frontend.

- Root cause corrigée côté enums (`BadgeCategory`, `BadgeConditionType` étendus pour
  couvrir les valeurs déjà en base).
- Filet de sécurité ajouté en plus : si un badge reste malgré tout illisible (donnée
  future corrompue), il est désormais silencieusement exclu de `badgeCodes` au lieu de
  faire échouer tout le profil — `GET /api/users/{id}` ne renverra plus jamais `500` pour
  un utilisateur existant.
- `GET /api/users/{id}` pour un id **inexistant** renvoie maintenant, comme avant, un
  `404 NOT_FOUND` propre (comportement déjà correct, désormais couvert par un test
  d'intégration).
- Aucun changement de contrat : toujours un `UserPublicDto` (voir §6), avec `avatarUrl`
  qui reflète bien la photo de profil de l'utilisateur consulté.

### 9.2 `GET /api/users/{id}/activities` — nouvelle route

N'existait pas auparavant (`404`). Renvoie désormais les activités **publiques**
(`visibleOnMap = true`) d'un autre utilisateur, au même format que
`GET /api/users/me/activities` :

```
GET /api/users/{id}/activities
```

- Auth requise (`Authorization: Bearer <token>`).
- Réponse `200 OK` → `List<UserActivityDto>` (voir §6 pour `ActivityDto`, imbriqué dans
  chaque élément via le champ `activity`) — chaque élément expose `activity.icon`,
  utile pour afficher l'icône/image de l'activité sur le profil public.
- `404 NOT_FOUND` si `id` ne correspond à aucun utilisateur.
- Contrairement à `GET /api/users/me/activities` (qui renvoie **toutes** les activités,
  y compris celles masquées sur la carte), cette route filtre sur `visibleOnMap = true` :
  seules les activités que l'utilisateur a choisi de rendre visibles apparaissent.
- Le champ `activities` du `UserPublicDto` renvoyé par `GET /api/users/{id}` reste vide
  (`[]`) — il n'est pas rempli automatiquement. Pour afficher les activités (et leurs
  icônes) sur un profil public, le frontend doit appeler cette nouvelle route séparément.
