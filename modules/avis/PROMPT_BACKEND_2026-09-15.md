# Le booléen « Recommandé par des participants » : la définition retenue

**Date :** 2026-09-15
**Module :** `avis`
**Audit :** P-MU-02 étape 2, P-BL-10 (décision D4)
**Fait suite à :** `REPONSE_BACKEND_2026-09-14.md`, §4 (« c'est une donnée nouvelle à définir : qui
compte, à partir de combien, et lisible par qui »)
**Décision de l'utilisateur, 15/09/2026 :** la définition ci-dessous.

> **Ce que fait l'app** : plus aucune note ni moyenne ; les avis sont privés (lus par l'organisateur
> et leur auteur). Elle voudrait, sur la fiche d'un programme, une seule ligne publique : « Recommandé
> par des participants » — un booléen, jamais un nombre.

---

## 1. La définition

- **Qui compte** : une personne distincte qui a une **présence confirmée** sur au moins une séance du
  programme **et** qui l'a recommandé. La recommandation est le « Tu le recommanderais ? Oui » posé
  avec l'avis privé (voir §2) — pas une note.
- **À partir de combien** : **au moins 3 personnes distinctes**, hors organisateur, hors comptes
  fermés, et hors personnes bloquées dans un sens ou dans l'autre avec le lecteur si vous savez le
  calculer à la lecture (sinon hors blocage avec l'organisateur).
- **Lisible par qui** : tout compte connecté, sur la fiche du programme (`ProgramDto` ou une route
  dédiée légère). **Jamais le nombre**, ni dans la réponse, ni dans un en-tête.
- **Durée** : sur la vie du programme (pas de fenêtre glissante qui ferait clignoter la ligne).

## 2. L'écriture

- `POST /api/reviews` accepte un `recommend: boolean` (facultatif). `true` compte pour le seuil ;
  `false` ou absent ne compte pas. Rendu seulement à l'organisateur et à l'auteur, comme l'avis.

## 3. La demande

1. `recommend` sur `CreateReviewRequest` et `ReviewDto` (même visibilité que l'avis).
2. `recommendedByParticipants: boolean` sur la lecture du programme, calculé selon §1.
3. Un test : 2 recommandations → `false` ; 3 personnes distinctes → `true` ; une même personne deux
   fois compte pour une ; l'organisateur ne compte pas ; aucun nombre au contrat.

## 4. Côté app, ensuite

Remplacer la feuille « Écrire un avis » par « Tu le recommanderais ? Oui / Pas vraiment » + un mot
privé, et afficher la ligne sur la fiche derrière un drapeau `participantRecommendation`, allumé
dans un commit séparé après relevé.

## 5. Comment nous vérifierons

Relevé du contrat (le booléen, aucun nombre), puis lecture d'un programme de test avec le compte de
test.
