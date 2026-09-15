# Réponse du 15/09 — `/search/popular` ne publie plus que des termes du catalogue

**Date :** 2026-09-15
**Module :** `recherche`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15.md`

> **Votre relevé est exact, et les quatre points sont livrés.** Nous gardons la route : elle publie
> désormais des noms d'**activités et de catégories du catalogue**, cherchés par **au moins cinq
> personnes distinctes**, sans compteur. La requête brute sort aussi des journaux `info`.

---

## 1. Ce que la route rend

`GET /api/search/popular?limit=` rend une liste de `{ "query": "…" }`, triée du terme le plus cherché
au moins cherché.

Une saisie n'est retenue que si elle remplit **deux conditions** :

1. **Elle est exactement le nom d'une activité ou d'une catégorie** du catalogue, une fois ramenée en
   minuscules, sans accents et sans espaces autour. « course à pied », « Course à pied » ou
   « course a pied » comptent tous pour l'activité « Course à pied ». « Lena Mueller » ne correspond
   à rien, et ne peut donc jamais sortir.
2. **Au moins cinq personnes distinctes** l'ont cherchée sur les 30 derniers jours. Une même personne
   qui cherche dix fois compte pour une.

**Le libellé rendu est celui du catalogue**, jamais la saisie telle qu'elle a été tapée.

Nous avons préféré cette règle à un filtre des prénoms : une liste de noms ne sera jamais complète,
et le premier nom qu'elle oublierait sortirait chez tout le monde. En ne publiant que ce que le
catalogue contient déjà, aucun nom de personne ne peut passer, par construction.

**Liste vide** quand rien n'atteint le seuil, ce qui sera fréquent au début. Vous masquez déjà la
section dans ce cas.

## 2. Le compteur

`searchCount` **quitte** `PopularSearchDto` et le contrat. Le décompte ne sert plus qu'à trier, dans la
requête, et ne sort pas du serveur.

## 3. Les journaux

Les quatre lignes de `SemanticSearchService` qui écrivaient la requête brute au niveau `info`, dont
une avec l'identifiant de l'appelant, passent en `debug` **et ne portent plus la requête** : seulement
sa longueur, ou le nombre de résultats. Le niveau `debug` n'est pas actif en production.

Les saisies restent enregistrées dans `search_logs`, pour l'historique « mes recherches récentes » de
chacun, que vous lisez déjà et qui n'est rendu qu'à son auteur. Rien de ce lot n'y touche.

## 4. Vérification

**Test** `RecherchesPopulairesIntegrationTest` :

- une activité cherchée par cinq personnes, saisie en minuscules avec des espaces, sort sous son
  libellé du catalogue ;
- la même règle à quatre personnes ne sort pas ;
- un nom de personne cherché par six personnes ne sort pas ;
- aucune entrée ne porte d'autre clé que `query`.

**Votre protocole du §3** : relevé du contrat sans `searchCount`, puis `GET /api/search/popular` avec le
compte de test. Vous ne verrez que des termes du catalogue, ou une liste vide.
