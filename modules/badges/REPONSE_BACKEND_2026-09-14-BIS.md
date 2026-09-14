# Réponse du 14/09 (BIS) — `currentStreakWeeks` et `practitionersNearby` ne sont plus servis, et la série n'est plus calculée

**Date :** 2026-09-14
**Module :** `badges`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14-BIS.md`

> **Les six points sont livrés.** Votre relevé du §1 est exact, ligne pour ligne.
>
> - `currentStreakWeeks` **quitte `PracticeStatsDto`** : absent, ni `null` ni `0`.
> - La série **n'est plus calculée**, et **la colonne `users.current_streak_weeks` est supprimée**
>   avec les valeurs déjà calculées (§3 de votre demande : nous retenons la suppression).
> - `practitionersNearby` **quitte `SuggestedActivityDto`**. **L'ordre des propositions ne change
>   pas** : le décompte trie encore la requête, et n'en sort plus.
> - Un test tient la porte fermée au contrat.

---

## 1. La série

- **`PracticeStatsDto`** : `attendanceCount`, `distinctPartnersCount`, `lastAttendanceAt` et
  `byActivity`, sans autre changement.
- **`PracticeStatsService.recalculateFor`** ne calcule plus de série : le calcul hebdomadaire, son
  écriture sur l'utilisateur et la requête de dates qui ne servait qu'à lui sont retirés. Les autres
  compteurs sont recalculés comme avant.
- **`V126__plus_de_serie_de_pratique.sql`** : `ALTER TABLE users DROP COLUMN current_streak_weeks`.
  Nous suivons votre raison, qui était celle de V122 : une série gardée « au cas où » reste une
  donnée sur la régularité de quelqu'un.
- **Export RGPD** : la série n'y figurait pas. Rien à retirer.

Retour arrière vers une version antérieure du serveur : la migration documente la colonne à
recréer. Les valeurs, elles, ne reviennent pas.

## 2. Le décompte de pratiquants

- **`SuggestedActivityDto`** : `id`, `name`, `slug`, `icon`, `imageUrl`, `categoryId`,
  `categoryName`, `fallback`.
- **Le classement est inchangé** : `ORDER BY practitioners DESC` reste dans les deux requêtes,
  locale et repli national, et le service ne recopie plus la valeur.
- **Au contrat** : la description du schéma ne parle plus de nombre, et `GET /api/activities/suggested`
  porte désormais une description qui renvoie à `GET /api/activities/practised-nearby` pour « se
  pratique ici ».

## 3. Le test

`DoctrineContratSansDecompteTest` lit `/v3/api-docs` et vérifie deux choses :

- `currentStreakWeeks` et `practitionersNearby` n'apparaissent **nulle part** dans le contrat ;
- `PracticeStatsDto`, `SuggestedActivityDto` et `PractisedNearbyDto` n'ont aucune propriété dont le
  nom évoque une série (`streak`) ou un décompte de personnes (`practitioner`, `people`, `persons`,
  `users`).

## 4. Ce que nous ne touchons pas, comme vous le signalez

- **`GET /api/progressions/my/streak` et `/my/stats`** servent encore `StreakDto.currentStreak` et
  `longestStreak`. Aucune décision n'est prise, et vous n'appelez aucune route `/progressions`. Nous
  ne les retirons pas sans demande, mais la question mérite d'être posée : c'est la même doctrine.
- **`WatchService.retoursConfirmesDAffilee`** relève de `tracabilite` : il compte des retours de
  veille confirmés, pas une pratique.

## 5. Vérification

- `/v3/api-docs` : `PracticeStatsDto` sans `currentStreakWeeks`, `SuggestedActivityDto` sans
  `practitionersNearby`, `PractisedNearbyDto` inchangé.
- `GET /api/users/me/practice-stats` sans la clé, et `GET /api/activities/suggested?lat=48.137&lng=11.575`
  sans `practitionersNearby`, **dans le même ordre** qu'avant le déploiement. Relevez-le avant :
  ce lot part avec le suivant (`inscription`) dans un même déploiement, annoncé dans sa réponse.
