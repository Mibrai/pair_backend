# Réponse du 14/09 (TER) — le module `/api/progressions` est retiré, et il fuyait plus qu'une série

**Date :** 2026-09-14
**Module :** `badges`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14-TER.md`

> **Les quatre points sont livrés, en deux déploiements.**
>
> - **Votre point 2 était plus grave que vous ne le supposiez.** `GET /api/progressions/user/{userId}`
>   rendait à **tout compte connecté** toutes les progressions de **n'importe qui**, **privées
>   comprises** : titre, contenu, mesures, nom et dates, sans filtre ni blocage. Nous l'avons fermé en
>   premier, seul, pour que la correction parte sans attendre le reste.
> - **Puis le module entier est retiré** (votre point 3) : aucune route `/api/progressions/**` ne
>   reste au contrat, et `StreakDto` comme `ProgressionStatsDto` en ont disparu.
> - **Les tables `progressions` et `progression_entries` restent en base** pour l'instant : les
>   supprimer efface des données saisies, et c'est une décision à part (§3).

---

## 1. Premier déploiement — la fermeture

| Route | Avant | Après |
|---|---|---|
| `GET /api/progressions/user/{userId}` | **toutes** les progressions de la personne visée, privées comprises, pour tout compte | `404` pour autrui ; ses propres progressions pour l'intéressé |
| `GET /api/progressions/{id}`, progression privée d'autrui | `403` : confirmait qu'elle existe | `404` |
| `GET /api/progressions/my/streak` | série courante, plus longue série, dates actives | retirée |
| `GET /api/progressions/my/stats` | portait la série (`streak`) | sans la série |

## 2. Second déploiement — le retrait

- **Retirés** : le contrôleur, le service, l'entité, le dépôt et les six DTO du module. **Aucune route
  `/api/progressions/**`** ne reste servie, et les schémas `StreakDto`, `ProgressionStatsDto`,
  `ProgressionDto` quittent le contrat.
- **L'export RGPD** (`GET /api/gdpr/export`) rend toujours les progressions **tant que la table en
  contient**. Une personne a droit à ce qui est stocké sur elle. Il les lit désormais directement
  dans la table, et rend **celles que la personne a écrites**. Il rendait jusqu'ici celles des
  programmes qu'elle organise, donc écrites par d'autres.
- **La suppression d'un compte** efface ses progressions comme avant : les clés étrangères de la
  table sont en `ON DELETE CASCADE`.

## 3. Ce qui reste à décider : les tables

`progressions` et `progression_entries` restent en base, sans plus aucun code applicatif pour les
écrire ou les lire hors de l'export RGPD. Les supprimer demande une migration qui efface
**définitivement** les progressions déjà saisies. Nous ne l'avons pas fait sans connaître leur
volume en production. La décision revient à l'utilisateur, et elle ne vous demande rien : aucune
route ne dépend de ces tables.

## 4. Le test

`DoctrineContratSansDecompteTest` porte un troisième cas : **aucun chemin `/api/progressions`**
au contrat, plus de schémas `StreakDto` ni `ProgressionStatsDto`, et aucune propriété
`currentStreak`, `longestStreak` ni `activeDates` nulle part. `GdprExportProgressionsIntegrationTest`
vérifie que l'export rend les progressions de la personne, et seulement les siennes.

## 5. Vérification

Relevé `/v3/api-docs` : aucun chemin `/api/progressions`, ni `currentStreak`, `longestStreak` ou
`activeDates`. Côté app, rien à changer : vous n'appeliez aucune de ces routes.
