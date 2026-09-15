# `/search/popular` publie les recherches brutes des autres — des prénoms compris

**Date :** 2026-09-15
**Module :** `recherche` (nouveau)
**Audit :** signalement de la vérification d'audit mobile du 14/09/2026 (jamais écrit jusqu'ici)

> **Ce que fait l'app** : elle affiche la section « Recherches populaires » de l'Explorer à partir de
> `GET /api/search/popular`, et sait déjà la masquer quand la liste est vide.
>
> **Ce qui vous appartient** : ce que la route publie.

---

## 1. Relevé du 15/09/2026 (code serveur et contrat `/v3/api-docs`, production `0c3fb50`)

- `SearchLogRepository.findPopularSearches` regroupe `SearchLog.rawQuery` de **tous** les utilisateurs
  sur 30 jours, **sans seuil ni filtre**.
- `GET /api/search/popular` le rend à tout compte connecté : `PopularSearchDto {query, searchCount}`.
- Une personne qui cherche « Lena Mueller » trois fois fait apparaître ce nom **chez tout le monde**,
  avec un compteur.
- `SemanticSearchService.search` journalise aussi la requête brute avec l'identifiant de l'appelant
  (`log.info`).

## 2. La demande

1. Ne publier qu'une requête cherchée par **au moins N personnes distinctes** (N ≥ 5).
2. **Exclure** toute requête qui correspond à un `displayName` ou à un prénom connu — ou, plus simple,
   ne publier que des noms d'**activités et de catégories du catalogue**.
3. Ne plus rendre `searchCount` (doctrine : jamais un compte là où il n'est pas nécessaire).
4. Retirer la requête brute des journaux de niveau `info`.

Si vous préférez **retirer la route**, dites-le : l'app masque déjà la section vide.

## 3. Comment nous vérifierons

Relevé du contrat (plus de `searchCount`), puis `GET /api/search/popular` avec le compte de test :
aucun nom de personne, seulement des termes du catalogue.
