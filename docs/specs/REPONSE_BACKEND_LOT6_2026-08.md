# Réponse backend au lot 6 — pagination de `POST /search`

> Demande 2 de `PROMPT_BACKEND_EVOLUTIONS_2026-08.md`, avec l'arbitrage (a) que
> vous avez validé : plafonds relevés, fusion, découpe.
>
> **Dernier lot.** Les six demandes sont livrées.

---

## 1. Le contrat

Entrée — deux champs de plus sur le corps de `POST /api/search` :

```jsonc
{
  "query": "yoga", "lat": 48.8566, "lng": 2.3522, "radiusMeters": 5000,
  "page": 0,          // indexé à 0, défaut 0
  "pageSize": 20      // défaut 20, plafonné à 100
}
```

Nous retenons bien **`pageSize`** en lowerCamelCase, comme convenu. Vous
sérialisez `page_size` aujourd'hui (`search_models.dart:490`) : c'est à aligner.

Sortie — cinq champs additifs sur `type: "results"` :

```jsonc
{
  "type": "results",
  "results": [ /* … inchangé … */ ],
  "totalCount": 137,
  "page": 0,
  "pageSize": 20,
  "hasMore": true,
  "countsByType": { "user": 3, "program": 12, "slot": 7 }
}
```

`countsByType` porte **toujours les trois clés**, à zéro le cas échéant : un
client qui affiche des onglets ne doit pas avoir à distinguer « zéro » de
« absent ». Il compte la requête entière, pas la page — sinon « Personnes (3) »
afficherait 3 puis 0.

**Sur `clarification` et `empty`, les cinq champs valent `null`**, pas zéro. Cela
distingue « cette réponse n'est pas paginée » de « zéro résultat sur cette
page ». Vos DTO doivent les traiter comme nullables.

---

## 2. Le point du contrat que nous n'avons pas pu tenir tel qu'écrit

Votre critère dit : « l'ordre par **`relevanceScore` décroissant** est stable
entre les pages ». Nous ne trions pas par `relevanceScore`, et voici pourquoi.

Ce champ n'a pas la même signification selon la ligne :

| Source | `relevanceScore` |
|---|---|
| requête plein texte principale | `ts_rank(...)` — une vraie valeur |
| une autre requête plein texte | `0.0` en dur |
| une troisième | `1.0` en dur |
| chemin sémantique (embeddings) | `0f` en dur |
| résultats de type `user` | `0f` |

Trier là-dessus classerait par **quel chemin de code a produit la ligne**, pas
par pertinence : le bloc `1.0` en tête, puis les vrais `ts_rank`, puis tout le
reste à égalité. Ce serait moins bon que l'ordre actuel, qui est délibéré :

1. **les créneaux d'abord**, triés par date — un créneau dans deux heures vaut
   mieux qu'un programme sans date ;
2. puis les **matchs taxonomiques** (précision cross-lingue) ;
3. puis le **rappel** sémantique ou plein texte.

Nous avons donc gardé cet ordre et traité votre vrai besoin — pas de doublon, pas
d'oubli entre deux pages — autrement : **`p.id` a été ajouté en dernier critère
des trois `ORDER BY` SQL**. Sans lui, deux programmes de même rang et même
distance pouvaient sortir dans un ordre différent d'un appel à l'autre, et les
pages auraient bougé sous vous. C'est ce qui manquait réellement.

Si vous voulez un vrai tri par pertinence, il faut d'abord rendre
`relevanceScore` comparable entre les sources — c'est un chantier de scoring, pas
de pagination. Dites-le et nous le chiffrerons.

---

## 3. Vos critères, un par un

| Critère | État |
|---|---|
| `page=0` puis `page=1` sans doublon | ✅ |
| Ordre stable entre les pages, tie-break déterministe sur l'id | ✅ mais pas par `relevanceScore`, cf. §2 |
| `totalCount` constant d'une page à l'autre | ✅ |
| `hasMore` faux sur la dernière page, y compris quand `totalCount` est un multiple exact de `pageSize` | ✅ testé sur ce cas précis |
| Requête sans `page`/`pageSize` : comportement d'avant, `totalCount` renseigné | ✅ mais voir §4 |
| `countsByType` somme à `totalCount` | ✅ |
| `clarification` et `empty` inchangées | ✅ champs de pagination à `null` |
| OpenAPI documente les nouveaux champs | ✅ |

---

## 4. Deux écarts à connaître

### 4.1 Une réponse pouvait dépasser 20 résultats ; elle n'en rend plus que 20

Aujourd'hui, quand les créneaux correspondants dépassent la limite globale, la
réponse en renvoie **plus de vingt** : le budget des programmes tombe à zéro,
mais les créneaux ne sont jamais tronqués. Avec la pagination, la page 0 en rend
`pageSize`.

C'est le but de la fonctionnalité, mais c'est un écart au « exactement comme
aujourd'hui » : un client qui affichait tous les créneaux d'un coup en verra
désormais vingt, plus `hasMore: true`.

### 4.2 `totalCount` est exact jusqu'à 200, puis un minorant

Le plafond de candidats du moteur passe de **20 à 200**. C'était 20, soit
exactement une page : au-delà de la première, il n'y avait rien à servir — c'est
la raison pour laquelle la pagination ne pouvait pas être un simple `LIMIT`.

`totalCount` est donc exact tant que la requête produit moins de 200 candidats,
et vaut 200 au-delà. C'est l'arbitrage (a) que vous avez retenu, avec sa limite
assumée. Si vous avez besoin d'un total exact au-delà, c'est l'option (b) —
une requête de comptage séparée, décorrélée du pipeline de pertinence.

---

## 5. Vérification

`SearchPaginationIntegrationTest`, 10 tests : deux pages sans recouvrement, ordre
stable entre deux appels identiques, `totalCount` constant, `hasMore` faux sur la
dernière page puis sur une page exactement pleine, comportement inchangé sans
paramètres, `countsByType` sommant à `totalCount` et identique d'une page à
l'autre, réponse vide non paginée, et page au-delà du total rendant une liste
vide sans erreur.

Suite complète : **260 → 270 tests**, 11 échecs + 2 erreurs identiques avant et
après, tous préexistants.

---

## 6. Les six demandes sont livrées

| Demande | Lot | État |
|---|---|---|
| 6 — `id` stable + `DELETE /search/recent/{id}` | 1 | ✅ |
| 3(c) — codes d'erreur stables | 1 | ✅ |
| 5 — bornage `/map/activities` (Option A) | 3 | ✅ |
| 5 — agrégation et bornes de cluster (Option B) | 3 | ✅ |
| 3(a,b) — `Accept-Language` | 2 | ✅ |
| 1 — `/activities/browse` | 4 | ✅ |
| 4 — récurrences RFC 5545 | 5 | ✅ |
| 2 — pagination `/search` | 6 | ✅ |

Hors demandes, corrigés en chemin : `programCount` qui comptait des créneaux, la
bbox de `/map/bounds` qui était un disque, deux scans de table complets, deux
paginations non déterministes, et une série close par `UNTIL` qui ressuscitait
indéfiniment.

**Reste ouvert, et vous appartient :**

1. `truncated` sur `GET /programs` — impossible additivement, la route rend un
   tableau nu (lot 1 §3). Notre position : `/activities/browse` la remplace.
2. `permitAll()` sur `GET /map/activities` — choix produit, pas correctif. Le
   test qui exige un 401 reste rouge tant qu'il n'est pas tranché.
3. `activityLevels` / `categoryIds` de `/map/bounds`, appliqués après le `limit`
   (lot 3 §3). Sans effet tant que vous ne les envoyez pas.
4. Les libellés de catégories et de niveaux, qui viennent de la base et non d'un
   fichier de messages (lot 2 §4).
5. Un tri par pertinence réel, qui suppose de rendre `relevanceScore` comparable
   entre les sources (§2).
