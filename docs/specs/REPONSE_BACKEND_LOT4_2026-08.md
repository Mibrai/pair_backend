# Réponse backend au lot 4 — `GET /activities/browse`

> Demande 1 de `PROMPT_BACKEND_EVOLUTIONS_2026-08.md`, sur la maille
> `UserActivity` que vous avez validée.
>
> Fait suite aux lots 1 à 3.

---

## À lire avant tout : la demande 4 devient bloquante pour celle-ci

Ni vous ni nous ne l'avions vu, et ça change l'ordre de vos priorités.

`nextSessionAt` et `isExpired` de `/activities/browse` reposent sur le prochain
`starts_at` **brut** : les récurrences ne sont pas développées, la demande 4
n'étant pas livrée. Jusqu'ici cette naïveté était partagée par tout le backend et
sans conséquence — un champ faux, que vous corrigiez côté client.

Ici, elle **filtre**. `includeExpired` vaut `false` par défaut, et une entrée est
réputée expirée quand elle est datée mais sans séance à venir. Un créneau
hebdomadaire dont la première séance est passée remonte donc `nextSessionAt:
null`, `isExpired: true`, et **disparaît de l'Explorer**.

Trois choses atténuent le problème sans le supprimer :

- `RecurringSlotRolloverJob` avance chaque heure les créneaux récurrents passés,
  donc la plupart repassent dans le futur d'eux-mêmes ;
- il les avance de **7 jours en dur**, sans lire la `RRULE` : un
  `FREQ=WEEKLY;BYDAY=MO,WE` reste faux, et un `FREQ=MONTHLY` est carrément
  déplacé ;
- vous pouvez passer `includeExpired=true` en attendant, au prix d'afficher de
  vraies activités terminées.

**Notre recommandation : remontez la demande 4 avant la demande 2.** Sans elle,
l'Explorer que vous alliez brancher masquera des activités bien vivantes. Dites-
nous ce que vous préférez ; nous n'avons pas voulu réordonner votre file sans
vous.

---

## 1. Le contrat

```
GET /api/activities/browse?lat=48.8566&lng=2.3522&radiusMeters=25000&page=0&size=20
```

| Paramètre | Défaut | Notes |
|---|---|---|
| `lat`, `lng` | — | requis |
| `radiusMeters` | `25000` | mètres, borné 1 – 200 000 comme sur `/map/activities` |
| `page` | `0` | indexé à 0 |
| `size` | `20` | plafonné à 100 |
| `categoryIds` | — | répétable ou séparé par virgules |
| `activityLevels` | — | idem |
| `includeExpired` | `false` | cf. l'avertissement ci-dessus |
| `sort` | `distance` | `distance`, `nextSession`, ou `relevance` |
| `includePrograms` | `false` | joint les 3 prochains programmes |

Réponse : l'enveloppe `Page<T>` Spring, celle de `/notifications` que vous savez
déjà lire.

```jsonc
{
  "content": [
    {
      "userActivityId": "77c2…",     // ← l'identité de l'entrée
      "activityId": "0b1e…",         // ← référentiel, NON unique dans la page
      "activityName": "Yoga",
      "activityIcon": "self_improvement",
      "imageUrl": "…",
      "description": "…",
      "categoryId": "a3…", "categoryName": "Bien-être", "categoryIcon": "yoga",
      "lat": 48.8566, "lng": 2.3522,
      "address": "12 rue …",
      "distanceMeters": 1240.0,
      "locationType": "IN_PERSON",
      "organizerId": "5f2c…",        // ← toujours renseigné
      "organizerName": "Lena Müller",
      "organizerAvatarUrl": "…",
      "programCount": 3,
      "totalParticipants": 12,
      "nextSessionAt": "2026-08-11T18:30:00Z",
      "isExpired": false,
      "programs": null               // sauf includePrograms=true
    }
  ],
  "page": { "size": 20, "number": 0, "totalElements": 137, "totalPages": 7 }
}
```

### Trois écarts avec votre proposition, tous délibérés

1. **`userActivityId` n'est pas nullable, `activityId` n'est pas unique.** C'est
   la conséquence directe de la maille `UserActivity` : deux organisateurs du
   même « Yoga » donnent deux entrées portant le même `activityId`. **Votre clé
   de déduplication et de navigation est `userActivityId`**, pas `activityId`.
2. **`categoryColorRamp` n'y est pas** — vous ne l'aviez pas demandé sur cette
   route, contrairement à `/map/activities`. Dites-le si vous le voulez, c'est
   une colonne de plus.
3. **`sort=relevance` est accepté et traité comme `distance`.** Sans terme de
   recherche, la pertinence n'a pas de sens ici. Nous l'acceptons pour ne pas
   casser un client qui l'envoie, mais nous ne prétendons pas l'implémenter.
   `sort=nextSession` est, lui, réel.

---

## 2. Vos critères d'acceptation, un par un

| Critère | État |
|---|---|
| Deux activités de même nom, deux organisateurs → deux entrées, chacune avec ses programmes | ✅ par construction de la maille |
| `organizerId` non nul dès qu'un organisateur existe, même sans aucun programme | ✅ une entrée existe parce qu'une personne l'a déclarée |
| `radiusMeters` réellement appliqué (60 km absent à 25 000) | ✅ `ST_DWithin` en SQL |
| `page=0` puis `page=1` sans doublon, ordre total stable | ✅ distance, nom, `activityId`, `userActivityId` |
| `totalElements` = le rayon, pas la page | ✅ requête de comptage dédiée |
| Activité en ligne rendue avec `lat`/`lng`/`distanceMeters` nuls, non filtrée par le rayon | ✅ mais voir §3 |
| Utilisable sans `includePrograms` | ✅ |

### Sur le temps de réponse

Vous demandiez qu'il reste comparable à celui de `/map/activities`. Il devrait
être **meilleur** : `/map/activities` charge encore tous les créneaux localisés
d'un rayon pour les regrouper en mémoire, là où `/activities/browse` pagine en
base et ne remonte que 20 lignes. Nous ne l'avons pas mesuré sous charge réelle —
dites-nous si vous voulez un chiffre avant de brancher.

---

## 3. Une décision que nous avons prise seuls, et qui vous appartient

**Les entrées sans position sont reléguées après toutes les entrées localisées.**

Votre critère dit qu'une activité en ligne ne doit pas être filtrée par le rayon.
Nous l'avons respecté à la lettre — ce qui veut dire que **toute entrée sans
créneau localisé apparaît dans toutes les recherches, quelle que soit la zone**.
Une activité déclarée à Berlin sans programme localisé sort donc dans un Explorer
parisien.

Sans ordre imposé, les premières pages pourraient être noyées par ces entrées.
Nous les plaçons donc en fin d'ordre total : le voisinage d'abord, le hors-sol
ensuite. C'est testé.

Si vous préférez qu'elles soient carrément exclues au-delà d'un certain rayon, ou
rattachées à la position de leur organisateur — ce que fait déjà
`ProgramRepository.findVisibleNearScheduleOrOrganizer` pour les programmes —
dites-le. C'est une décision produit, pas technique.

---

## 4. Ce que ça vous permet de supprimer

D'après votre propre inventaire :

- `buildBrowsedActivities` (130 lignes) et `_normName` comme clé étrangère ;
- le troisième appel au catalogue `GET /activities` ;
- le couplage circulaire carte ↔ Explorer ;
- le recalcul client de l'expiration — **sous réserve de la demande 4**, cf.
  l'avertissement en tête.

La correction de votre clé de déduplication carte (lot 1, §2) reste nécessaire :
elle porte sur `/map/activities`, que `/activities/browse` ne remplace pas.

---

## 5. Vérification

`ActivityBrowseIntegrationTest`, 12 tests, un par critère plus les cas limites :
deux organisateurs de la même activité, entrée sans programme, rayon appliqué,
activité en ligne sans position, entrées localisées d'abord, pages sans
recouvrement, `totalElements` indépendant de la taille de page, route utilisable
sans et avec `includePrograms` (borné à 3), entrée expirée écartée par défaut
puis rendue sur demande, entrée sans créneau jamais expirée, rayon hors bornes
refusé.

Suite complète : **232 → 244 tests**, 11 échecs + 2 erreurs identiques avant et
après, tous préexistants.

---

## 6. Suite

| | Attendu | État |
|---|---|---|
| 0-3 | SHA, maille, `programCount`, `Accept-Language`, `/map/bounds` | ✅ lots 1 à 3 |
| 4 | `truncated` sur `/programs` | ❌ impossible additivement |
| 5 | **Demande 1** — `/activities/browse` | ✅ ce document |
| 6 | **Demande 2** — pagination `/search` | à faire |
| 7 | **Demande 4** — RRULE | à faire — **à remonter avant la 2**, cf. tête de document |
