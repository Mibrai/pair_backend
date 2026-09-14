# Avis de programme — étapes A et C livrées ensemble : plus de note, et un avis n'est lu que par l'organisateur et son auteur

**Date :** 2026-09-14
**Module :** `avis`
**Fait suite à :** `REPONSE_APP_2026-09-14.md` (votre réponse à notre `QUESTION_BACKEND_2026-09-14.md`)
**Audit :** P-BL-10, décision D4

> **Votre relevé nous a permis d'aller plus vite que prévu.** Toutes vos versions lisent `score` comme
> facultatif. **A et C partent donc dans le même déploiement**, sans attendre votre étape B.
>
> - **A** : `score` n'est plus exigé. Une note encore envoyée est acceptée, puis **ignorée**.
> - **C** : aucune note n'est plus enregistrée, et **aucune n'est rendue**, y compris celles saisies
>   avant aujourd'hui.
> - **C** : les avis d'un programme ne sont lus que par **son organisateur** et, pour le sien, par
>   **l'auteur de l'avis**. Tout autre compte reçoit **`200` avec une page vide**, comme vous le
>   préfériez, jamais un `403`.
> - **D** (suppression de la colonne et des notes existantes) : dans deux semaines, par un document
>   à part.

---

## 1. L'écriture — `POST /api/reviews`

| | Avant | Maintenant |
|---|---|---|
| `score` absent | `400` « La note est requise » | **`201`** |
| `score` entre 1 et 5 | enregistré | **accepté, pas enregistré** |
| `score` hors de 1 à 5 | `400` | `400`, inchangé |
| `comment` | 30 à 1000 caractères, obligatoire | inchangé |
| Réponse | `score` = la note envoyée | **`score: null`** |

Nous gardons les bornes quand une note est envoyée. Vos versions 16 et 17 n'envoient que 1 à 5, et un
`400` sur une valeur absurde vaut mieux qu'une acceptation silencieuse de n'importe quoi.

**Votre étape B** peut partir dès maintenant : ne plus envoyer `score`, retirer le choix des étoiles.
Au contrat, `CreateReviewRequest.score` n'est plus requis et il est marqué `deprecated`.

## 2. La lecture

### 2.1 La note

`ReviewDto.score` vaut **toujours `null`**, sur les trois routes qui le portent :
`/programs/{id}`, `/programs/{id}/summary` et `/me`. Cela vaut aussi pour les avis écrits avant
aujourd'hui, dont la note reste en base jusqu'à l'étape D mais n'est plus jamais lue. Le champ reste
déclaré au contrat, marqué `deprecated`, et sera retiré à l'étape D.

Sur 16 et 17, l'organisateur verra ses avis avec cinq étoiles vides, comme vous l'aviez anticipé.

### 2.2 Qui lit un avis

`GET /api/reviews/programs/{programId}` :

| Lecteur | Ce qu'il reçoit |
|---|---|
| **L'organisateur du programme** | tous les avis, avec leur commentaire et le nom de leur auteur |
| **L'auteur d'un avis** | son propre avis, et lui seul |
| **Tout autre compte connecté** | **`200`**, `content: []`, `totalElements: 0` |
| Un compte bloqué avec l'organisateur | `404`, inchangé (P-BS-14) |

La réponse garde sa forme de page. `content` vide est ce que vous lisez comme « aucun avis ».

`GET /api/reviews/programs/{programId}/summary` suit la même règle, que vous n'appelez pas. Pour un
autre compte que l'organisateur, `totalReviews` compte ce qu'il a le droit de lire, soit 0 ou 1, et
non plus le total du programme : un nombre public d'avis sur un programme dont on ne lit plus aucun
avis n'aurait pas de sens. `averageScore` reste `null`.

`GET /api/reviews/me` : inchangé, sauf `score` à `null`.

## 3. Ce qui ne change pas

- **`GET /api/reviews/can-review/{programId}`** : même règle. Il faut une conversation avec
  l'organisateur ou une présence partagée confirmée, et ne pas avoir déjà écrit d'avis.
- **L'export RGPD** d'un compte contient toujours la note des avis que la personne a écrits avant
  aujourd'hui. C'est sa propre donnée, encore stockée, et l'export doit dire ce qui est stocké.
  Elle en sortira à l'étape D, avec la colonne.
- **Le signalement d'un avis** (`POST /api/reports`, type `REVIEW`) : inchangé.

## 4. Le booléen « Recommandé par des participants »

Vous nous dites qu'il vous est utile. **Il n'est pas dans cette livraison.** C'est une donnée
nouvelle à définir : qui compte, à partir de combien, et lisible par qui. Elle fera l'objet d'un
document à part, comme annoncé dans notre question. D'ici là, gardez la formulation « Tu le
recommanderais ? » derrière un drapeau éteint.

## 5. Base de données

`V125__avis_note_facultative.sql` retire le `NOT NULL` de `reviews.score`. Le `CHECK` entre 1 et 5
reste en place, puisqu'il accepte `NULL`. Aucune note existante n'est modifiée. L'étape D supprimera
la colonne.

## 6. Vérification

**Test d'intégration** `AvisSansNoteIntegrationTest` (6 tests) :

- un avis sans note est accepté ;
- une note encore envoyée est acceptée, rendue `null` et absente de la base ;
- une note hors bornes reste refusée ;
- une note antérieure, posée en base, n'est rendue ni à l'organisateur ni dans « mes avis » ;
- la liste d'un programme est lue en entier par l'organisateur, réduite à son avis pour l'auteur, et
  vide en `200` pour un visiteur ;
- le résumé suit la même visibilité.

Rejoué sans le correctif, il échoue sur 5 de ses 6 cas.

**Votre protocole du §5 tient tel quel**, après déploiement :

- `/v3/api-docs` : `CreateReviewRequest` sans `score` requis ;
- avec le compte de test, `GET /api/reviews/programs/{id}` par un non-organisateur rend `200` avec
  `content: []`, et par l'organisateur des avis dont `score` vaut `null`.
