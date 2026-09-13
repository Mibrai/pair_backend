# Plus de moyenne publique, un `DELETE` qui ne confirme rien, et une seule règle d'entrée

**Date :** 2026-09-13
**Module :** [`programmes/`](.)
**Suite de :** [`REPONSE_BACKEND_2026-09-13.md`](REPONSE_BACKEND_2026-09-13.md)
**Fiches d'audit :** P-BL-10 (étapes 1, 2, 3 et 5), P-BA-16 étape 2, P-BS-14 étape 3, P-BL-09
**Décisions :** produit, 13/09

> **En bref.**
>
> - **§1 — `averageScore` et `reviewCount` valent toujours `null`**, sur le programme, la recherche
>   et le résumé des avis. Les champs restent au contrat, dépréciés.
> - **§2 — `DELETE /programs/{id}/schedules/{id}` rend `404` à un non-propriétaire**, au lieu de
>   `403`.
> - **§3 — les avis d'un programme rendent `404`** quand son auteur et l'appelant sont bloqués.
> - **§4 — rejoindre par le programme refuse aussi un créneau fermé aux partenaires**
>   (`SLOT_NOT_OPEN_TO_PARTNERS`).
> - **§5 — ce que nous vous suggérons.**

---

## 1. Plus de moyenne publique (P-BL-10)

| Où | Champ | Avant | Maintenant |
|---|---|---|---|
| `ProgramDto` | `averageScore`, `reviewCount` | calculés | **`null`**, `deprecated` |
| `SearchResultDto` | `averageScore`, `reviewCount` | calculés | **`null`**, `deprecated` |
| `ReviewSummaryDto` | `averageScore` | calculé | **`null`**, `deprecated` ; `totalReviews` inchangé |

- **L'app 1.1.0+16 n'a rien à faire** : `result_card.dart` masque déjà la note quand elle est nulle.
- Les deux champs **quitteront le schéma** après la version d'app qui ne les lit plus (P-MU-02).
  Nous vous préviendrons avant.

## 2. Supprimer le créneau d'un autre : `404` (P-BA-16 étape 2)

Un non-propriétaire recevait `403 FORBIDDEN`, ce qui confirmait qu'un créneau existait à cet
identifiant. Il reçoit maintenant **le même `404 NOT_FOUND` qu'un créneau inexistant**, comme
`POST /slots/{id}/cancel`. L'app ne propose la suppression qu'au propriétaire et traite l'erreur
par `mapDioException` : **aucun changement requis**.

## 3. Avis d'un programme à travers un blocage (P-BS-14 étape 3)

`GET /api/reviews/programs/{id}` et `/summary` rendent **`404 NOT_FOUND`** quand l'auteur du
programme et l'appelant se sont bloqués, dans un sens ou dans l'autre. Même règle que le profil, les
badges et les recommandations. Un programme inconnu garde sa page vide (`200`).

## 4. Une seule règle d'entrée sur un créneau (P-BL-09)

`POST /api/programs/{id}/join` avec un `scheduleId` refuse désormais un créneau
`isOpenToPartners=false` avec **`400 SLOT_NOT_OPEN_TO_PARTNERS`**, exactement comme
`POST /slots/{id}/join`. Le code et son message traduit existent déjà dans l'app.

## 5. Ce que nous vous suggérons

- **P-MU-02** : ne plus lire `averageScore` / `reviewCount`, pour que nous puissions les retirer.
- Rien d'autre n'est requis.
