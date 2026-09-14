# Plus de badge de série, de palier ni de note : le catalogue passe de 31 à 16

**Date :** 2026-09-14
**Module :** [`badges/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md) — P-MU-25
**Migration :** `V122__badges_sans_serie_ni_palier.sql`

> **En bref.**
>
> - **§1 — votre relevé est exact.** Les six badges de série sont bien au catalogue, et trois sont
>   encore évalués.
> - **§2.1 et §2.2 — livrés.** Aucun badge de série n'est plus attribué ni servi. Les trois types
>   sortent de l'énumération, et la base refuse désormais d'en recevoir un.
> - **§2.3 — nous supprimons les attributions**, par migration. Rien n'est gardé en base.
> - **§2.4 — les paliers sortent aussi**, avec le chiffre du nom qui restait. Nous avons ajouté les deux
>   badges de note, que la décision D4 du 13/09 condamnait déjà.
> - **Un changement que vous n'avez pas demandé : `BadgeDto.conditionThreshold` n'est plus servi.**
>   Votre modèle ne le lit pas (`badge_models.dart`), rien ne casse chez vous.

---

## 1. Le relevé, vérifié

Tout est exact au commit `4c3d38b`. Pour être précis :

- **Trois types de série existent.** `PROGRESSION_STREAK` et `WEEKLY_STREAK` étaient évalués par
  `BadgeService`. `STREAK_DAYS` n'était évalué par personne : un seul badge l'utilise, « Engagiert »
  (V27), et il n'est attribué que par la graine de démonstration.
- **Six badges de série** : `STREAK_7`, `STREAK_30`, `STREAK_100`, `STREAK_4_WEEKS`, `STREAK_12_WEEKS`
  (tous dans `seed/data/badges.json`) et `DEDICATED` (V27). « 1 mois de régularité », celui de votre
  compte de test, est `STREAK_4_WEEKS`.
- **Le catalogue a deux sources, et c'est ce qui compte ici.** Les migrations (V27, V66) écrivent
  d'abord. `ReferenceDataSeeder` repasse ensuite **à chaque démarrage** et ajoute les codes de
  `badges.json` qui manquent. Supprimer une ligne en base sans la retirer du JSON la ferait revenir au
  déploiement suivant. Les deux sont donc corrigés.

## 2. La demande

### 2.1 Ne plus attribuer

`BadgeService.isEligible` n'a plus de branche de série. `PROGRESSION_STREAK`, `STREAK_DAYS` et
`WEEKLY_STREAK` **quittent `BadgeConditionType`**. Nous aurions pu les garder avec une évaluation à
`false` ; les retirer oblige à écrire du code pour qu'une série revienne. C'est l'effet recherché.

### 2.2 Ne plus servir

V122 supprime les six badges. `GET /api/badges`, `GET /api/badges/me` et `GET /api/badges/users/{id}`
ne peuvent donc plus en rendre. Il en va de même pour les codes de badges du profil public, qui lisent
les mêmes lignes.

**Et la base refuse qu'ils reviennent.** La contrainte `chk_badges_ni_serie_ni_note` rejette toute
ligne `badges` de ces types, qu'elle vienne d'une migration, du seeder ou d'une écriture à la main.
C'est l'interrupteur côté serveur. Gardez le vôtre.

### 2.3 Les attributions existantes : supprimées

Nous retenons la suppression, pour la raison que vous donnez. Une attribution gardée « au cas où »
reste une donnée sur la régularité de quelqu'un, et elle finit toujours par être resservie. La clé
étrangère `badge_awards → badges` est `ON DELETE CASCADE` : supprimer le badge supprime ses
attributions dans la même instruction. **La migration suffit**, rien à faire chez vous.

### 2.4 Les chiffres dans les noms, et les paliers

**Règle retenue : un badge par condition, au seuil le plus bas.** Il marque un geste, il ne mesure pas
une performance. C'est la règle que `HOST_INVITER` suivait déjà (seuil 1, « la récompense marque un
geste »). V122 l'applique en SQL, sans liste de codes : tout badge dont le seuil dépasse le minimum de
sa condition est supprimé. Les badges de vérification (e-mail, téléphone, identité : trois faits
distincts) et les badges manuels n'entrent pas dans la règle.

| Condition | Reste | Supprimés |
|---|---|---|
| `RECOMMENDATION_COUNT` | « Première recommandation » (1) | `TRUSTED_5`, `TRUSTED_20` |
| `PROGRAM_COUNT` | « Organise régulièrement » (3) | `ACTIVE_ORGANIZER` (5) |
| `PROGRAMS_CREATED` | « Erstes Programm » (1) | `ACTIVE_COACH` (5) |
| `ACTIVITY_DIVERSITY` | « Esprit curieux » (3) | `MULTI_PASSION_5` |
| `ATTENDANCE_COUNT` | « Première rencontre » (1) | `TEN_MEETUPS` — « 10 séances partagées » |
| `DISTINCT_PARTNERS` | `FIVE_PARTNERS`, **renommé « Partenaires variés »** | `TWENTY_PARTNERS` — « 20 partenaires différents » |

**Aucun nom servi ne contient plus de chiffre.** Le code `FIVE_PARTNERS` garde le sien : c'est un
identifiant, que vous ne montrez pas.

**Ce que nous avons ajouté : les badges de note.** « Top Bewertet » (`AVERAGE_REVIEW_SCORE`) et
« Perfekte Bewertung » (`PERFECT_REVIEWS`) sont une note publique sous forme de médaille. La décision
D4 du 13/09 (P-BL-10, plus aucune note ni moyenne publique) les condamnait déjà. Ils sortent aussi,
avec leur type et la même contrainte de base.

**Et `conditionThreshold` n'est plus servi.** C'était le seuil, donc un compte. Votre modèle ne le lit
pas, exprès (`badge_models.dart`, « tant qu'un champ existe dans le modèle, quelqu'un le
rebranche »). Le serveur applique la même prudence.

### Le catalogue, après V122

16 badges au lieu de 31 : les 31 moins 6 séries, 2 notes et 7 paliers. Votre compte de test perd
« 1 mois de régularité », et les paliers qu'il avait obtenus.

## 3. Ce qui reste ailleurs, et que nous ne touchons pas ici

- **`PracticeStatsDto.currentStreakWeeks`** (« 5 semaines d'affilée ») est toujours servi par
  `GET /users/me/practice-stats`. D5 du 13/09 l'a réservé à la personne elle-même. Ce n'est plus un
  badge, mais c'est la même statistique de série. Si vous ne l'affichez plus, dites-le : nous le
  retirerons du contrat.
- **`NotificationType.STREAK_MILESTONE`** est déclaré et jamais émis (P-BL-17). Rien ne l'émettra : il
  n'y a plus de série à célébrer. La valeur reste dans l'énumération, parce que votre catalogue de
  préférences peut la porter.
- `GET /api/badges/me/count` rend toujours le nombre de badges **de la personne elle-même**.

## 4. Vérification

- `BadgesSansSerieNiPalierIntegrationTest` (nouveau) lit ce que `GET /api/badges` rend réellement.
  Il vérifie qu'aucune condition n'est une série ni une note, qu'aucun nom ne contient de chiffre,
  qu'il n'y a qu'un badge par condition et que `conditionThreshold` est absent. Il fait la même
  vérification sur `badges.json`, et prouve que la base refuse un `WEEKLY_STREAK`.
- `UserPublicProfileIntegrationTest` s'appuyait sur `ACTIVE_COACH` pour éprouver une catégorie
  `CREATION`. Il s'appuie désormais sur `FIRST_PROGRAM`, de la même catégorie.
- `BadgeServiceTest`, `SlotInvitationIntegrationTest` (`HOST_INVITER`) : verts.
- **Votre relevé, après déploiement :** `GET /api/badges` avec le compte de test doit rendre 16
  badges, sans aucun `conditionType` contenant `STREAK`.
