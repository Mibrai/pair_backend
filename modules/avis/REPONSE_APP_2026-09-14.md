# Réponse de l'app — la note se lit sans jamais faire tomber une liste, et l'étape C peut suivre A

**Date :** 2026-09-14
**Module :** `avis`
**Fait suite à :** `QUESTION_BACKEND_2026-09-14.md`
**Relevé :** sources de l'app, trois états — builds **1.1.0+16** (`1913ba2`, 09/09) et **1.1.0+17**
(`fac63ac`, 11/09), tous deux antérieurs au retrait des étoiles du 13/09 ; et `main` (`83081e7`,
14/09), qui sera la prochaine version.

> **En une phrase** : toutes les versions lisent `score` comme **facultatif** (`null` → `0`, jamais
> d'échec) ; seules les versions 16 et 17 l'**affichent**, et seulement `main` n'affiche plus les
> commentaires des autres. **C peut suivre A sans attendre B.**

---

## 1. Écriture

**Oui**, `POST /api/reviews`, corps `{programId, score, comment}`, depuis le bouton « Écrire un
avis » de la fiche d'un programme — affiché seulement quand `GET /api/reviews/can-review/{programId}`
rend `true`. La feuille (`program_review_sheet.dart`) fait choisir **1 à 5 étoiles** et exige 30 à
1000 caractères avant d'envoyer : **`score` part toujours**, entier de 1 à 5 envoyé en nombre
décimal. Identique dans les trois états.

Conséquence pour l'étape A : rien ne casse, l'app continue d'envoyer une note que vous pourrez
ignorer.

## 2. Lecture

`ProgramReview.fromJson` (`lib/models/program_models.dart`) :

| État | Lecture de `score` | `score: null` |
|---|---|---|
| 16 et 17 | `(json['score'] as num?)?.toDouble() ?? … ?? 0` | lu `0` ; la liste s'affiche |
| `main` | `readNumOrNull(json, 'score') ?? … ?? 0` (lecture défensive, journalise un type faux) | lu `0` ; la liste s'affiche |

**Le modèle tolère `null`**, dans toutes les versions : un avis sans note ne fait tomber ni la liste
ni la fiche. Une clé retirée du contrat se lit de la même façon.

Ce que l'app **lit** :
- `GET /api/reviews/programs/{programId}` : oui, dans les trois états ;
- `GET /api/reviews/programs/{programId}/summary` : **jamais appelée** ;
- `GET /api/reviews/me` : **jamais appelée** ;
- `averageScore` / `reviewCount` du programme : lus par 16 et 17, retirés du modèle dans `main`
  (14/09) ; déjà servis à `null`, sans effet.

Ce que l'app **affiche** de la note :
- **16 et 17** : l'onglet « Avis » de la fiche, visible **par tous**, dessine cinq étoiles par avis
  (`overallRating.round()`). Avec `score: null`, **cinq étoiles vides** — pas d'erreur, mais une
  note d'apparence nulle à côté du commentaire.
- **`main`** : **aucune étoile nulle part** (P-MU-02, 13/09).

## 3. Le mot

- **16 et 17** : **oui**, les commentaires des autres, signés du nom de leur auteur, sont affichés à
  tout visiteur dans l'onglet « Avis ».
- **`main`** : **non**. L'onglet « Avis » n'est construit que pour l'**organisateur** du programme
  (P-MU-02, étape 1, 13/09). Un visiteur n'y a pas accès.

## 4. Sur votre ordre

- **A** (score facultatif) : sans risque, à faire quand vous voulez.
- **C** (score plus rendu, commentaire rendu seulement à l'organisateur et à l'auteur) : **peut suivre
  A sans attendre B**. Sur 16 et 17, l'effet visible est bénéfique : un visiteur verra une liste
  vide (au lieu des avis signés d'autrui), l'organisateur verra ses avis avec cinq étoiles vides. Rien
  ne tombe.
  - ⚠️ Précisez comment un visiteur reçoit la liste après C : **`200 []`** est lu comme « aucun
    avis » par toutes les versions ; un `403` s'afficherait sur 16 et 17 comme « impossible de
    charger les avis », dans l'onglet public. Nous préférons `200 []`.
- **B** (côté app) : dès que A est déployé et relevé au contrat, la prochaine version cessera
  d'envoyer `score` et retirera le choix des étoiles. La formulation « Tu le recommanderais ? » attend
  le booléen que votre §4 annonce ; **il nous est utile**, écrivez-le quand vous voulez.
- **D** : pas d'objection.

## 5. Ce que nous vérifierons

Après A : `/v3/api-docs` (`CreateReviewRequest` sans `score` requis). Après C : lecture de
`GET /api/reviews/programs/{id}` par un non-organisateur (attendu `200 []`) et par l'organisateur
(`score` absent ou `null`), avec le compte de test.
