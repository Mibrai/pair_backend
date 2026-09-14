# Deux comptes que personne ne lit : `currentStreakWeeks` et `practitionersNearby`

**Date :** 2026-09-14
**Module :** `badges` — la question y a été soulevée pour la série ; elle cite `rappel/` pour le
décompte de pratiquants
**Fait suite à :** `REPONSE_BACKEND_2026-09-14.md` de ce module, §3 (« si vous ne l'affichez plus,
dites-le ») ; et `../rappel/REPONSE_BACKEND_2026-09-14.md`, §4 (« dites-le : nous le retirerons du
contrat »)
**Audit :** P-MU-25 (ni paliers, ni séries), P-BL-17 (compteurs), P-MU-22 (écart A1)
**Décision de l'utilisateur, 14/09/2026 :** demander au serveur de **ne plus servir** ces deux
champs, et de **ne plus calculer** la série. Doctrine : ni stats d'effort ni séries ; jamais un
compte là où un booléen suffit.

> **Ce que fait l'app** : elle ne lit **aucun** des deux champs. `currentStreakWeeks` est ignoré
> depuis P-MU-25 (`lib/models/practice_stats_models.dart:17`, tenu par
> `test/doctrine_no_gamification_test.dart:380` et `test/stats/practice_stats_repository_test.dart:190`).
> `practitionersNearby` n'a jamais été lu : l'accueil appelle `GET /activities/suggested`
> (`onboarding_repository.dart:95`) et `SuggestedActivity.tryFromJson` n'en garde que l'identifiant,
> le nom et la catégorie. Pour « déjà pratiquée près de toi », l'app prendra le booléen de
> `GET /api/activities/practised-nearby`, livré le 14/09.
>
> **Ce qui vous appartient** : cesser de les produire. Un compte servi sans être affiché finit
> toujours par être rebranché par quelqu'un. Vous l'avez écrit à propos de `conditionThreshold`.

---

## 1. Relevé du 14/09/2026, 13 h 43 (heure de Paris)

**Contrat** `/v3/api-docs` de production, 220 chemins, `build.time` `2026-09-14T11:39:59Z` (commit
`04aeffb`) :

| Schéma | Propriété | Type | Route |
|---|---|---|---|
| `PracticeStatsDto` | `currentStreakWeeks` | `integer` | `GET /api/users/me/practice-stats` (et `/{userId}/practice-stats`, `404` pour autrui depuis le 13/09) |
| `SuggestedActivityDto` | `practitionersNearby` | `integer` | `GET /api/activities/suggested?lat&lng&limit` |
| `PractisedNearbyDto` | `activityId`, `practisedNearby` | `string`, `boolean` | `GET /api/activities/practised-nearby` : le remplaçant, déjà au contrat |

Aucune lecture HTTP authentifiée n'a été rejouée ce jour-là : les valeurs servies ne sont pas
relevées, seulement les types et le code.

**Code, clone au commit `04aeffb`.**

*La série :*
- `PracticeStatsDto.java:9` : `int currentStreakWeeks, // "5 semaines d'affilée"`.
- `PracticeStatsService.java:47` la calcule (`computeWeeklyStreak`, `:93-111`) **à chaque
  confirmation de présence** (`recalculateFor`, `:42`), `:63` l'écrit sur l'utilisateur, `:83` la
  sert.
- Stockée dénormalisée : `User.java:110-112`, colonne `users.current_streak_weeks`
  (`V41__attendances.sql:21`), remplie par la graine `V44__seed_meetdo_slots_attendance_alerts.sql:252`.
- Route : `UserController.java:91-94` et `:115-123`.

*Le décompte de pratiquants :*
- `SuggestedActivityDto.java:18-23` : `long practitionersNearby`, décrit au contrat comme « c'est
  vivant ici ».
- Il vient de `ActivityRepository.java:197` (`COUNT(DISTINCT ua.user_id) AS practitioners`), lu par
  `SuggestedActivityRow.getPractitioners()` (`:172`), pour la maille locale (`:219`) comme pour le
  repli national (`:244`, `:257`).
- `OnboardingIntegrationTest.java:200` vérifie qu'il vaut `0` sur le repli.
- `PractisedNearbyService.java:22` le nomme déjà comme ce que la nouvelle route ne laisse pas sortir.

## 2. La demande

1. **`currentStreakWeeks` quitte `PracticeStatsDto`** et le contrat. Pas `null`, pas `0` : absent.
   Le reste du DTO ne change pas dans cette demande.
2. **La série n'est plus calculée** : `computeWeeklyStreak` et son appel dans `recalculateFor`
   disparaissent, avec l'écriture `setCurrentStreakWeeks`.
3. **La colonne `users.current_streak_weeks`** : nous penchons pour la supprimer par migration, pour
   la raison que vous avez retenue pour les attributions de badges (V122) : une série gardée « au cas
   où » reste une donnée sur la régularité de quelqu'un. Dites-nous ce que vous retenez. Si elle
   reste, qu'aucun export ne la serve (export RGPD compris, où elle n'aurait de sens que remise à
   zéro).
4. **`practitionersNearby` quitte `SuggestedActivityDto`** et le contrat. **Le classement ne change
   pas** : `ORDER BY practitioners DESC` (`ActivityRepository.java:216`, `:254`) peut garder le
   décompte **dans la requête**, pour trier, tant qu'il ne sort pas du service. `fallback` reste :
   c'est un booléen, et il dit ce que l'app a besoin de savoir.
5. **Au contrat**, la description de `SuggestedActivityDto` ne parle plus de nombre, et celle de
   `/activities/suggested` renvoie à `/activities/practised-nearby` pour « se pratique ici ».
6. **Un test** : `/v3/api-docs` ne porte plus `currentStreakWeeks` ni `practitionersNearby`, et
   `PracticeStatsDto`, `SuggestedActivityDto` et `PractisedNearbyDto` n'ont aucune propriété dont le
   nom évoque une série (`streak`) ni un décompte de personnes. Il tient la porte fermée, comme
   `chk_badges_ni_serie_ni_note` le fait en base pour les badges.

**Compatibilité** : les versions de l'app déjà installées ne lisent ni l'un ni l'autre (le modèle
de statistiques ignore la clé depuis P-MU-25, et `SuggestedActivity` ne l'a jamais eue). Rien ne
casse chez nous. Si vous voyez un autre client qui les lit, dites-le avant de retirer.

**Hors de cette demande**, et nous le signalons sans le demander (aucune décision n'a été prise) :

- `GET /api/progressions/my/streak` et `/my/stats` (`ProgressionController.java:80-82`) servent
  `StreakDto.currentStreak` et `longestStreak`, au contrat le 14/09. L'app n'appelle aucune route
  `/progressions` (`api_constants.dart`, relu le 14/09) ;
- `WatchService.retoursConfirmesDAffilee` (`WatchService.java:306-320`) compte aussi « d'affilée »,
  mais des retours de veille confirmés et non de la pratique. Il relève du module `tracabilite`.

## 3. Côté app, ensuite

- Quand le relevé confirmera le retrait, les tests qui fabriquent encore un JSON **avec**
  `currentStreakWeeks` pour prouver qu'on l'ignore (`test/practice_stats_models_test.dart`,
  `test/stats/practice_stats_repository_test.dart`, `test/doctrine_no_gamification_test.dart`)
  garderont cette clé : un serveur qui régresserait doit toujours trouver l'app sourde. Seuls leurs
  cartouches changeront (« n'est plus servi depuis le … »).
- `lib/models/practice_stats_models.dart:17-20` citera le relevé de retrait au lieu de celui du
  13/09.
- Le drapeau `activityPractisedNearby` (`feature_flags.dart:214`) suit son propre chemin, dans
  `rappel/` : il ne dépend pas de ce retrait.

## 4. Comment nous vérifierons

- **Relevé** `/v3/api-docs` : `PracticeStatsDto` sans `currentStreakWeeks`, `SuggestedActivityDto`
  sans `practitionersNearby`, `PractisedNearbyDto` inchangé.
- **HTTP réel avec le compte de test** (lectures seules) : `GET /api/users/me/practice-stats` ne
  porte plus la clé ; `GET /api/activities/suggested?lat=48.137&lng=11.575` rend des entrées sans
  `practitionersNearby`, dans le même ordre que la même lecture faite avant votre déploiement (nous
  la relèverons dès que vous annoncerez la date).
- **Suite de l'app** verte, sans changement de code.
