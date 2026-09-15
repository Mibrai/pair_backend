# Les tables `progressions` : dites-nous ce qu'elles contiennent, puis supprimons-les

**Date :** 2026-09-14
**Module :** `badges`
**Fait suite à :** `REPONSE_BACKEND_2026-09-14-TER.md`, §3
**Décision de l'utilisateur, 14/09/2026 :** d'abord connaître le volume et l'ancienneté des données ;
ensuite, faute d'usage récent, supprimer les tables (minimisation, RGPD art. 5.1.c).

> **Ce que fait l'app** : rien ne lit ni n'écrit ces données — aucune route `/api/progressions/**`
> n'a jamais été appelée par l'app (relevé des sources du 14/09).
>
> **Ce qui vous appartient** : `progressions` et `progression_entries` restent en base sans aucune
> fonction qui s'en serve, hors l'export RGPD. Garder des données personnelles qui ne servent plus
> rien, c'est les exposer sans raison.

---

## 1. Constat (votre réponse TER)

- Le module `/api/progressions` est retiré (second déploiement annoncé) ; les tables restent.
- Jusqu'au 14/09, `GET /api/progressions/user/{userId}` rendait à tout compte connecté les
  progressions de n'importe qui, **privées comprises**. Ces données ont donc pu être lues par
  d'autres pendant toute la durée de vie de la route.
- La suppression d'un compte les efface déjà (`ON DELETE CASCADE`) ; l'export RGPD les rend à leur
  auteur.

## 2. La demande — en deux temps

### Étape 1 : le relevé (lecture seule, aucune modification)

Sur la base de **production**, par une requête d'agrégat qui ne sort aucun contenu :

1. le nombre de lignes de `progressions` et de `progression_entries` ;
2. le nombre de **comptes distincts** concernés, et combien sont encore actifs ;
3. la date de **la plus récente** saisie (`created_at` / `updated_at`), et la répartition par mois ;
4. combien de lignes étaient **privées** ;
5. combien appartiennent aux comptes de démonstration (`demo%@pair.app`) et aux deux comptes de test.

Écrivez-nous ces chiffres dans votre réponse. **Aucun titre, contenu ni nom** : des comptes seulement.

### Étape 2 : selon le relevé

- **Si aucune saisie récente** (rien depuis que l'app existe sous sa forme actuelle, ou uniquement des
  comptes de démonstration) : une migration qui **supprime les deux tables**, et le retrait des
  progressions de l'export RGPD dans le même lot. Rien n'est à prévenir.
- **S'il existe des saisies réelles** de vrais utilisateurs : ne supprimez rien encore. Proposez-nous
  une option (par exemple : prévenir les personnes concernées et leur laisser un délai pour exporter,
  puis supprimer) ; l'utilisateur tranchera.
- **Dans les deux cas**, dites-nous si la route ouverte a pu exposer des progressions **privées de
  vrais utilisateurs** à d'autres comptes (journaux d'accès, si vous en avez) : c'est une question
  d'incident, que l'utilisateur doit connaître pour juger s'il y a une notification à faire.

## 3. Côté app

Rien à changer.

## 4. Comment nous vérifierons

Étape 1 : les chiffres dans votre réponse. Étape 2 : relevé `/v3/api-docs` inchangé, export RGPD du
compte de test sans progressions, et la migration citée.
