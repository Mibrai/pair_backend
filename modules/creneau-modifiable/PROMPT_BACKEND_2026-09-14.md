# La ville d'un créneau : écrite à la création, jamais relue par la fiche

**Date :** 2026-09-14
**Module :** `creneau-modifiable`
**Audit :** P-MU-28, livraison P2c (décision D8, option B)

> **Ce que l'app a déjà fait, sans vous** : une ligne « Voir la météo à {ville} » sur la fiche d'un
> créneau qui commence dans moins de cinq jours. Elle ouvre une recherche web avec **la ville
> seule — jamais l'adresse**. Elle est écrite, testée, et **invisible** : la fiche ne reçoit aucune
> ville.
>
> **Ce qui vous appartient** : rendre la ville du créneau là où la fiche la lit.

---

## 1. Relevé du 14/09/2026, 00 h 08 (`/v3/api-docs`)

| Schéma | Ville |
|---|---|
| `CreateScheduleRequest`, `UpdateScheduleRequest`, `QuickSlotRequest` | `city` — **acceptée à l'écriture** |
| `SearchResultDto` | `city` |
| `PublicSlotView` | `city` |
| `AfficheDto` | `cityLabel` |
| **`SlotFeedItemDto`** (`/slots/{id}`, `/slots/feed`, `/slots/mine`) | **aucune** |
| `ScheduleDto`, `ProgramDto` | aucune |

La colonne `schedules.city` existe donc, et elle est déjà servie par la page publique et par
l'affiche. Mais la fiche d'un créneau, dans l'app, lit `SlotFeedItemDto`, qui ne la porte pas.

Le module affiche l'avait déjà mesuré le 09/09 : la ville (`cityLabel` sur les présences) n'était
renseignée que sur **3 séances sur 42** du compte de test — et les formulaires de l'app n'envoient
pas `city` aujourd'hui.

## 2. La demande

1. **Exposer `city` dans `SlotFeedItemDto`**, nullable, la même valeur que `PublicSlotView.city`.
2. **Dire comment elle est remplie** : saisie seulement, ou déduite par vous de l'adresse ou des
   coordonnées ? Si vous la déduisez, dites-le : l'app n'en déduit rien et n'enverra pas une adresse
   à sa place.
3. Garder la règle d'aujourd'hui : **jamais un lieu et une récurrence ensemble** dans un contenu
   publié — la ville seule n'est pas un lieu, mais une ville et un jour de semaine récurrent sur une
   page publique pourraient le devenir. Dites-nous si `PublicSlotView` en tient compte.

## 3. Côté app, ensuite

La fiche prend la ville par un seul point (`SlotDetailPage.weatherCityOf`, aujourd'hui une fonction
qui rend `null`) : la bascule tient en une ligne, après relevé du contrat **et** lecture réelle d'un
créneau qui porte une ville. Il faudra aussi que les formulaires de création envoient `city` — une
fiche distincte.
