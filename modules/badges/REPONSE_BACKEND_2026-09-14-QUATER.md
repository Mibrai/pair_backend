# Réponse du 14/09 (QUATER) — les tables `progressions` ne contenaient que la graine de démonstration, et sont supprimées

**Date :** 2026-09-15
**Module :** `badges`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14-QUATER.md`

> **Étape 1 : le relevé est fait.** Les 10 progressions en production sont **exactement la graine de
> démonstration `V27`**, sans aucune saisie réelle. `progression_entries` est vide.
>
> **Étape 2 : les deux tables sont supprimées** (`V128`), et les progressions quittent l'export RGPD
> dans le même lot. Suppression confirmée par l'utilisateur.
>
> **La question d'incident** : la seule progression privée en base est une ligne de la graine. La
> route ouverte n'a exposé **aucune progression privée d'un vrai utilisateur**, du moins parmi les
> données encore présentes (§3).

---

## 1. Le relevé (étape 1)

Base de production (`postgresdb.railway.internal`), le 15/09/2026, en une transaction en lecture
seule terminée par `ROLLBACK`, **par agrégats seulement** : aucun titre, contenu ni nom n'est sorti.

| | `progressions` | `progression_entries` |
|---|---|---|
| Lignes | **10** | **0** |
| Comptes distincts | 10 | 0 |
| … encore actifs | 10 | 0 |
| Lignes privées | **1** | 0 |
| Lignes de comptes `demo%@pair.app` | 0 | 0 |
| Saisie la plus récente (`created_at` et `updated_at`) | **2026-07-01** | — |
| Répartition par mois | 2026-06 : 9 · 2026-07 : 1 | — |

**Les deux comptes de test** n'ont pas été relevés à part : nous n'avons pas leurs adresses. Aucune
des 10 lignes ne leur appartient, puisque toutes relèvent de la graine (§2).

## 2. Pourquoi ce sont des données de démonstration

Aucune ligne n'appartient à un compte `demo%@pair.app`, mais ce motif ne couvre pas la graine
allemande. La migration `V27__reset_and_seed_germany.sql` insère, dans sa section 23 :

- **10 progressions**, dont **une seule non publique** ;
- datées de **35 à 8 jours** avant son application, soit 9 en juin et 1 le 1er juillet ;
- pour les comptes de démonstration allemands de la même migration.

Chaque chiffre du relevé correspond. Aucune progression n'a été écrite depuis le 1er juillet, et
`progression_entries` n'a jamais reçu de ligne.

## 3. La question d'incident

- **La route** `GET /api/progressions/user/{userId}` rendait à tout compte connecté les progressions de
  n'importe qui, privées comprises, **depuis le commit initial du 26/06**. Elle est fermée depuis le
  14/09, et le module est retiré.
- **Ce qu'elle pouvait exposer** : la seule progression privée en base est la ligne de graine
  ci-dessus. **Aucune progression privée d'un vrai utilisateur** ne figure dans les données.
- **Ce que nous ne pouvons pas établir** : si une progression réelle a été créée puis supprimée
  entre-temps, elle n'a laissé aucune trace. Et l'application ne journalise pas les chemins de
  requête : rien, côté serveur, ne dit qui a appelé la route. Seuls les journaux HTTP de Railway,
  sur leur durée de rétention, pourraient le montrer.

Notre lecture est donc la suivante : pas de donnée personnelle réelle exposée d'après ce qui est
stocké, avec cette réserve à connaître pour juger d'une éventuelle notification.

## 4. La suppression (étape 2)

- **`V128__suppression_tables_progressions.sql`** : `DROP TABLE progression_entries` et
  `DROP TABLE progressions`, relevé recopié en commentaire.
- **L'export RGPD** (`GET /api/gdpr/export`) ne porte plus de section `progressions` ni de statistique
  `progressions`.
- **La description générale de l'API** ne mentionne plus « Progressions : suivi d'avancement avec
  métriques et streaks ».

**Restent, volontairement** : les valeurs `PROGRESSION_IMAGE` (usage d'un média) et
`PROGRESSION_REMINDER` (type de notification). Elles peuvent figurer dans des lignes existantes, et les
retirer du code empêcherait de relire ces lignes. Aucun code ne les produit plus.

## 5. Vérification

**Côté serveur** : la suite complète passe, migration `V128` appliquée sur la base de test.

**Votre protocole du §4** :

- `/v3/api-docs` inchangé : aucune route `/api/progressions` depuis le 14/09 ;
- export RGPD du compte de test sans progressions ;
- la migration citée ci-dessus.
