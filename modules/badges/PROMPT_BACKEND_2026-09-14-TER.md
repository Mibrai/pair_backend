# Les dernières séries : `/progressions/my/streak` et `/my/stats`

**Date :** 2026-09-14
**Module :** `badges`
**Fait suite à :** `REPONSE_BACKEND_2026-09-14-BIS.md`, §4
**Décision de l'utilisateur, 14/09/2026 :** demander le retrait de ces séries, au nom de la même
doctrine que `currentStreakWeeks`.

> **Ce que fait l'app** : elle n'appelle **aucune** route `/api/progressions/**` (relevé des sources
> du 14/09 : aucun chemin `progressions` dans `ApiConstants`).
>
> **Ce qui vous appartient** : ne plus calculer ni servir une série de régularité. Une série gardée
> « parce qu'aucun client ne la lit » reste une donnée sur la régularité de quelqu'un — votre propre
> raison de V122 et V126.

---

## 1. Relevé du 14/09/2026 à 19 h 29 (contrat `/v3/api-docs`, production `bd9f10b`)

- `GET /api/progressions/my/streak` → `StreakDto {currentStreak, longestStreak, lastProgressionDate,
  totalProgressions, activeDates}` (`ProgressionController.java:80`).
- `GET /api/progressions/my/stats` → `ProgressionStatsDto` (`ProgressionController.java:85`).
- Et, au même contrôleur : `GET /api/progressions/user/{userId}`, `/program/{programId}`, `/my`,
  `POST /api/progressions`, `GET|PUT|DELETE /api/progressions/{id}`.

## 2. La demande

1. **Retirer la série** : `currentStreak`, `longestStreak` et `activeDates` de `StreakDto` — ou la
   route `/my/streak` entière — et tout calcul qui les produit ; même question pour les champs de
   série ou de décompte d'effort de `ProgressionStatsDto`.
2. **`GET /api/progressions/user/{userId}`** : dites-nous ce qu'elle rend **sur autrui**, et à qui.
   Si elle expose la progression d'une autre personne (dates, fréquence), c'est le même défaut que
   les statistiques d'autrui retirées le 14/09 (P-MU-03) : la fermer à l'intéressé.
3. **Le module entier** : si `/progressions` n'a plus de client, dites-nous s'il peut partir ; l'app
   ne s'y opposera pas.
4. Étendre `DoctrineContratSansDecompteTest` à ces schémas (aucune propriété `streak`, `longest`,
   `activeDates`).

## 3. Comment nous vérifierons

Relevé `/v3/api-docs` : plus de `currentStreak`/`longestStreak`/`activeDates` ; la réponse au point
2 écrite. Côté app, rien à changer : aucune route n'est appelée.
