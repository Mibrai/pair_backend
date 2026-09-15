# Réponse du 15/09 — « Recommandé par des participants » : livré selon votre définition

**Date :** 2026-09-15
**Module :** `avis`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15.md`
**Audit :** P-MU-02 étape 2, P-BL-10 (D4)

> **Livré.** `recommend` s'écrit et se lit avec l'avis. `recommendedByParticipants` est un **booléen**
> sur la fiche d'un programme, vrai à partir de **trois personnes distinctes** présentes et qui
> recommandent. Aucun nombre ne sort, ni au contrat ni en base.
>
> **Un écart à connaître** (§3) : le booléen est calculé sur la **fiche** d'un programme, et vaut
> `null` dans les **listes**.

---

## 1. L'écriture

- `POST /api/reviews` accepte `recommend` (booléen, facultatif). `true` compte ; `false` ou absent ne
  compte pas.
- `ReviewDto.recommend` est rendu avec l'avis, donc **à l'organisateur et à l'auteur seulement**,
  comme le commentaire depuis le 14/09.
- **`V129`** ajoute `reviews.recommend BOOLEAN NOT NULL DEFAULT FALSE`. Les avis existants n'ont rien
  recommandé.

## 2. La règle du booléen

`recommendedByParticipants` vaut `true` quand **au moins trois personnes distinctes** remplissent
toutes ces conditions :

- elles ont un avis sur ce programme **avec `recommend = true`** ;
- elles ont une **présence confirmée** (`was_present`) sur au moins une séance de ce programme ;
- ce ne sont **pas l'organisateur** ;
- leur compte est **actif** ;
- aucun **blocage** n'existe entre elles et l'organisateur, dans un sens ou dans l'autre ;
- aucun **blocage** n'existe entre elles et le **lecteur** : nous savons le calculer à la lecture.

**Sur la vie du programme**, sans fenêtre glissante. **Une même personne compte pour une** : un seul
avis par personne et par programme, et le décompte porte sur les auteurs distincts.

La requête rend directement le booléen (`COUNT(DISTINCT …) >= 3`) : le nombre ne quitte pas la base.

## 3. Où il est lu — et l'écart

| Lecture | `recommendedByParticipants` |
|---|---|
| `GET /api/programs/{id}` (fiche) | `true` ou `false` |
| Réponses de `POST /api/programs` et `PUT /api/programs/{id}` | `true` ou `false` |
| **Listes** de programmes (`/users/me/programs`…) | **`null`** |

Le calcul demande une requête par programme. Dans une liste, cela ferait une requête par ligne, sur
des écrans qui en montrent beaucoup. Vous prévoyez la ligne sur la **fiche** : c'est là qu'elle est
calculée. Si vous en avez besoin dans une liste, dites-nous laquelle, et nous l'y calculerons en une
seule requête pour toute la page.

`null` ne veut pas dire « non recommandé » : lisez-le comme « non calculé ici ».

## 4. Vérification

**Test** `RecommandeParParticipantsIntegrationTest` (4 cas) :

- deux recommandations : `false` ; la troisième : `true` ;
- ne comptent pas : un avis sans « oui », une recommandation de l'organisateur, une recommandation sans
  présence confirmée ;
- une personne bloquée par l'organisateur cesse de compter, un compte fermé aussi ;
- `recommend` est rendu à l'auteur dans « mes avis », et `ProgramDto.recommendedByParticipants` est
  de type `boolean` au contrat, sans aucune propriété de décompte.

**Votre protocole du §5** : relevé du contrat, puis lecture d'un programme de test avec le compte de
test.
