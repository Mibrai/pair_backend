# Les compteurs d'une personne ne regardent qu'elle

**Date :** 2026-09-13
**Module :** [`mon-cercle/`](.) — profil d'autrui, recommandations, cartes-souvenirs
**Suite de :** [`REPONSE_BACKEND_2026-09-04.md`](REPONSE_BACKEND_2026-09-04.md)
**Fiches d'audit :** P-BL-17 (étapes 1, 2 et 3a), P-BL-10 (étapes 1 à 3)
**Décisions :** produit, 13/09

> **En bref.**
>
> - **§1 — `GET /users/{id}/practice-stats` rend `404` pour toute autre personne.**
> - **§2 — `UserPublicDto.subscriberCount` vaut toujours `null`.**
> - **§3 — recommandations : plus de note** ; `/recommendations/stats/{id}` rend `404` pour autrui.
> - **§4 — `SlotRecapDto.vibes`**, les ambiances dans l'ordre, sans décompte.

---

## 1. Statistiques de pratique

`GET /api/users/{userId}/practice-stats` rend **`404`** si `userId` n'est pas l'appelant.
`/api/users/me/practice-stats` est inchangé. L'app masque déjà la carte en erreur sur le profil
d'autrui (`profile_page.dart`) : **compatible**.

## 2. Nombre d'abonnés

`UserPublicDto.subscriberCount` est **`null` partout**, déprécié. `subscribed` est inchangé. Le sien
se lit sur `GET /users/me` (`UserPrivateDto.subscriberCount`).

## 3. Recommandations

- `CreateRecommendationRequest.rating` est **ignoré** : accepté sans validation, jamais écrit.
- `PeerRecommendationDto.rating` et `RecommendationStatsDto.averageRating` **quittent le contrat**.
  L'app ne les lit pas.
- `GET /api/recommendations/stats/{userId}` rend **`404`** pour autrui ; `/me/stats` inchangé.

## 4. Ambiances d'une carte-souvenir

`SlotRecapDto.vibes` : `["FRIENDLY", "RELAXED", …]`, de la plus choisie à la moins choisie, **sans
décompte**. `topVibes` reste tel quel pour l'instant. Après la version d'app qui lit `vibes`
(P-MU-25), `topVibes` sera servi vide, puis retiré — nous vous préviendrons.

## 5. Ce que nous vous suggérons

- **P-MU-25** : lire `vibes` et ne plus afficher de nombre sur les puces d'ambiance.
- **P-MU-03** : retirer l'appel à `userPracticeStats` pour autrui.
