# « Frais à prévoir » : les deux champs sont servis, et un `PATCH` qui les ignore ne les efface pas

**Date :** 2026-09-13
**Module :** [`programmes/`](.) — nouveau module, ouvert par ce document
**En réponse à :** [`PROMPT_BACKEND_2026-09-13.md`](PROMPT_BACKEND_2026-09-13.md) — **P-MU-16**,
décision D7 en option C
**Migration :** `V114__programs_cost_to_share.sql`

> **En bref.**
>
> - **§1 — votre constat est exact** : aucun champ de coût n'existait, ni dans le code ni dans les
>   migrations.
> - **§2 — tout est livré** : deux colonnes, acceptées par `POST`, `PUT` et `PATCH`, rendues par
>   `ProgramDto`, `SearchResultDto` et `SlotFeedItemDto`. **Les deux champs sont rendus dans les
>   trois**, pas seulement `costToShare`.
> - **§3 — les règles précises**, dont deux à connaître : une clé absente laisse la valeur en place,
>   et **recocher la case ne ressuscite pas la précision** effacée en la décochant.
> - **§4 — ni tri, ni filtre, ni index.**

---

## 1. Le constat, vérifié

Aucune occurrence de `price`, `cost` ou `fee` dans `src/main` ni dans les migrations. Les seules
correspondances étaient des faux positifs (« feed », « feedback »). Votre relevé est exact.

`PUT` et `PATCH /api/programs/{programId}` sont **la même méthode** côté serveur, avec le même
corps (`UpdateProgramRequest`), et **les deux fonctionnent en fusion** : un champ `null` ou absent
n'est pas modifié. Ce document ne distingue donc pas les deux verbes.

---

## 2. Ce qui est livré

### 2.1 Les colonnes

Exactement celles demandées, sur `programs` :

| Colonne | Type |
|---|---|
| `cost_to_share` | `BOOLEAN NOT NULL DEFAULT FALSE` |
| `cost_note` | `VARCHAR(80) NULL` |

Tous les programmes existants valent `false` / `null`.

### 2.2 Les clés JSON

`costToShare` (booléen) et `costNote` (chaîne, 80 caractères au plus), sur :

- `POST /api/programs` ;
- `PUT /api/programs/{programId}` ;
- `PATCH /api/programs/{programId}`.

### 2.3 Le rendu

| DTO | Routes principales | `costToShare` | `costNote` |
|---|---|---|---|
| `ProgramDto` | `GET /api/programs/{id}`, réponses de `POST`/`PUT`/`PATCH`, duplication | ✓ | ✓ |
| `SlotFeedItemDto` | `/api/slots/feed`, `/api/slots/{id}`, `/api/slots/mine`, `/api/quick-slots`… | ✓, repris du programme | ✓ |
| `SearchResultDto` | `POST /api/search`, résultats `program` **et** `slot` | ✓ | ✓ |

- **`costToShare` est un booléen non nullable** dans les trois : il vaut toujours `true` ou
  `false`, jamais `null`.
- **`costNote` est toujours `null` quand `costToShare` vaut `false`**, quelle que soit la route.

Vous demandiez « au moins `costToShare` » sur les deux derniers. Nous rendons aussi la précision :
la fiche créneau peut ainsi dire « Location du terrain » sans relire le programme, et la charge est
nulle.

---

## 3. Les règles

### 3.1 Création

| Envoyé | Enregistré |
|---|---|
| `costToShare` absent | `false`, précision `null` |
| `costToShare: false` + `costNote` | `false`, **précision ignorée** |
| `costToShare: true` + `costNote: "Location du terrain"` | `true`, `"Location du terrain"` |
| `costToShare: true` sans `costNote`, ou `costNote: ""` | `true`, précision `null` |

### 3.2 Modification (`PUT` comme `PATCH`)

La case est appliquée d'abord. La précision est ensuite jugée **sur la case après application**.

| Envoyé | Effet |
|---|---|
| ni `costToShare` ni `costNote` | **rien ne change** |
| `costToShare: false` | case décochée, **précision effacée** |
| `costNote: "…"` alors que la case est cochée | précision remplacée |
| `costNote: ""` | précision retirée, la case reste cochée |
| `costNote: "…"` alors que la case est décochée | **ignorée** |
| `costToShare: true` sur une case décochée | case cochée, **précision `null`** |

Trois conséquences pour vos formulaires :

- **Tant que `programCostToShare` est éteint, vos modifications ne touchent pas aux frais.** Si
  votre sérialiseur envoie `null` pour ces clés, ils sont conservés. **Vous n'avez pas à basculer
  le jour du déploiement.**
- **Décocher puis recocher ne restaure pas la précision.** Si votre formulaire garde le texte à
  l'écran pendant qu'on joue avec l'interrupteur, **envoyez `costToShare: true` et `costNote`
  dans la même requête** : le texte sera enregistré.
- **« Retirer la précision » s'envoie en chaîne vide**, pas en `null`.

### 3.3 Validation et nettoyage

- **Plus de 80 caractères : `400 VALIDATION_ERROR`**, et rien n'est modifié.
- La précision est **débarrassée de tout balisage HTML et des espaces en bord**, comme le titre et
  la description. Une précision qui ne contient que des espaces vaut `null`.
- **Dupliquer un programme** recopie la case et la précision.

---

## 4. Ce que nous ne faisons pas, exprès

- **Aucun tri ni filtre** sur ces champs, sur aucune route. **Aucun index non plus** : un index
  serait la première pierre de l'un ou de l'autre.
- **Aucun montant**, sous aucune forme.
- **Le serveur ne dit jamais « gratuit ».** `false` signifie « rien n'a été annoncé », et le schéma
  OpenAPI des trois DTO le dit en toutes lettres. Votre test qui interdit « Gratuit » par déduction
  reste la bonne garde.
- **Les pages web publiques** (lien de partage d'un programme ou d'un créneau) **n'affichent pas
  les frais**. Vous ne l'avez pas demandé. Dites-le si la puce doit aussi y figurer.

---

## 5. Vérification

**Côté serveur** — `ProgramCostToShareIntegrationTest`, 6 tests, qui reprennent votre scénario :

- programme « Tennis » avec « Location du terrain » : relu par la réponse de création, la fiche
  (`GET /programs/{id}`), `/slots/feed`, et `/search` en résultat `program` **et** `slot` ;
- programme sans frais : `false` et `null` sur ces quatre lectures ;
- précision envoyée sans la case : ignorée ;
- modifications : `PATCH` sans les clés → conservés ; `PUT` d'une nouvelle précision ; `""` →
  retirée ; décocher → précision effacée ; précision sur case décochée → ignorée ; recocher →
  précision `null` ;
- 81 caractères → `400`, la précision en place ne bouge pas ;
- duplication → les deux champs recopiés.

**Une limite du test, à connaître** : l'environnement de test n'a pas de modèle d'embeddings. Les
résultats `program` de `/search` y passent donc par le moteur plein texte. Le moteur sémantique
applique la même règle (même garde sur `costToShare`), mais **aucun test ne l'exerce**. Votre
relevé sur le compte de test le couvrira, puisque la production a son modèle.

**Suite entière** : **1601 tests, 199 classes, 0 échec** (14 min 42).

**Côté vous**, votre protocole du §4 s'applique tel quel. **Ajoutez une étape** : modifier le
programme « Tennis » **sans** toucher aux frais, avec le drapeau encore éteint, puis vérifier que
« Location du terrain » est toujours là.
