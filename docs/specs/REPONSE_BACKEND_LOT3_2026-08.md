# Réponse backend au lot 3 — `truncated` / `totalInBounds` sur `/map/bounds`

> Point 3 de votre tableau « Ce que nous attendons ensuite »
> (`REPONSE_CLIENT_EVOLUTIONS_2026-08.md`), la symétrie que nous avions annoncée.
>
> Fait suite à `REPONSE_BACKEND_LOT1_2026-08.md` et
> `REPONSE_BACKEND_LOT2_2026-08.md`.

---

## 1. Ce qui est livré

`GET /api/map/bounds` renvoie deux champs de plus, additifs :

```jsonc
{
  "users":      [ /* … */ ],
  "activities": [ /* … */ ],
  "programs":   [ /* … */ ],
  "truncated": true,
  "totalInBounds": 812
}
```

`truncated` vaut `true` dès qu'une des trois couches a écarté des marqueurs
présents dans la zone. La validation des bornes rejoint celle de
`/map/activities` — mêmes règles, **mêmes codes** (`MAP_BOUNDS_INVALID`,
`MAP_LIMIT_OUT_OF_RANGE`), pour que vous n'ayez pas deux comportements à
apprendre.

### Une réserve, à connaître avant de brancher

**`totalInBounds` n'a pas ici la même garantie que sur `/map/activities`.**

Sur `/map/activities`, c'est un total exact et l'identité de somme tient. Sur
`/map/bounds`, il est exact pour les couches `users` et `programs`, mais la
couche `activities` est *dérivée* : une `Activity` n'a pas de coordonnées, elle
n'existe sur la carte qu'à travers les personnes qui la déclarent. Elle est donc
agrégée sous un plafond interne de 1 000 personnes.

Conséquence : quand la zone contient plus de 1 000 personnes visibles,
`truncated` passe à `true` et `totalInBounds` devient un **minorant**. Ne
l'utilisez jamais comme un total exact sans regarder `truncated` d'abord. C'est
documenté dans l'OpenAPI, sur le champ lui-même.

---

## 2. Trois défauts trouvés en faisant ce lot

Aucun n'était dans vos demandes. Tous les trois affectaient la route que vous
alliez brancher.

### 2.1 La bbox n'était pas une bbox

`/map/bounds` ne filtrait pas sur le rectangle demandé. Il en déduisait un
**rayon** :

```java
int radiusMeters = (int) (Math.max(latDiff, lngDiff) * 111320.0 / 2.0);
radiusMeters = Math.min(radiusMeters, 100000);   // plafond à 100 km
```

puis interrogeait un disque, puis refiltrait le rectangle en Java.

Deux pertes silencieuses :

- **les coins.** Un disque inscrit dans un rectangle n'en couvre pas les angles.
  Des personnes et des programmes pourtant à l'intérieur des bornes demandées
  n'étaient jamais renvoyés — et rien ne le signalait, puisque le filtre Java qui
  suivait ne pouvait qu'enlever, jamais rajouter ;
- **le plafond de 100 km.** Sur une zone plus large, le disque cessait
  complètement de couvrir la bbox.

C'est maintenant un vrai filtre bbox en SQL (`&&` sur `ST_MakeEnvelope`,
indexable par le GiST). Le filtre Java a disparu.

**Ce que ça change pour vous** : la route renvoie *plus* de marqueurs qu'avant à
zone égale. Aucun marqueur hors des bornes n'apparaît — l'ancien filtre Java
garantissait déjà ça — mais ceux qu'il manquait apparaissent enfin. Si vous aviez
calibré quoi que ce soit sur les volumes observés, c'est le moment de le revoir.

### 2.2 La couche `programs` chargeait toute la table

```java
List<Schedule> allSchedules = scheduleRepository.findAll();
```

Exactement la même maladie que `/map/activities` avant le lot précédent : un scan
complet des créneaux à chaque appel, suivi d'un filtre en Java. Remplacé par la
même requête bornée que celle du bornage de `/map/activities`.

### 2.3 La pagination des programmes n'était pas déterministe

`skip(offset).limit(limit)` était appliqué à une liste **sans ordre défini**
(`findAll()` n'en garantit aucun). Deux appels consécutifs pouvaient donc rendre
des pages qui se recouvrent, ou en manquer une partie.

La liste est désormais triée avant découpe. Deux tests le verrouillent : deux
appels identiques rendent la même page, et deux pages successives ne partagent
aucun élément.

---

## 3. Un défaut que nous n'avons **pas** corrigé, et pourquoi

**`activityLevels` et `categoryIds` sont appliqués après le `limit`, pas avant.**

Concrètement : `?limit=100&activityLevels=BEGINNER` ne renvoie pas les 100
premiers marqueurs de niveau débutant, mais « parmi les 100 marqueurs les plus
proches du centre, ceux qui sont débutants ». Vous pouvez donc recevoir 12
marqueurs sans que `truncated` soit vrai.

Nous ne l'avons pas corrigé dans ce lot parce que :

- ça suppose de porter ces filtres en SQL, ce qui est un autre chantier que
  « ajouter deux champs additifs » ;
- **vous n'envoyez aucun de ces paramètres aujourd'hui**, donc personne n'est
  affecté.

Dites-nous si vous comptez les utiliser et nous le traiterons pour de bon. En
attendant, l'anomalie est connue et écrite ici plutôt que laissée à découvrir.

---

## 4. Vérification

Six tests d'intégration ajoutés, portant la classe de bornage carte à 26 :

- zone entièrement rendue → `truncated: false`, `totalInBounds` cohérent ;
- `limit=1` sur trois programmes → un seul rendu, `truncated: true` ;
- deux pages successives sans recouvrement ;
- deux appels identiques rendant la même page ;
- bornes inversées → 400 `MAP_BOUNDS_INVALID` ;
- `limit=0` → 400 `MAP_LIMIT_OUT_OF_RANGE`.

Suite complète : **226 → 232 tests**, 11 échecs + 2 erreurs identiques avant et
après, tous préexistants.

---

## 5. Suite

| | Attendu | État |
|---|---|---|
| 0 | SHA + horodatage, maille d'arrondi | ✅ lot 1 |
| 1 | `programCount` + `scheduleCount` | ✅ lot 1 |
| 2 | **Demande 3** — `Accept-Language` | ✅ lot 2 |
| 3 | `truncated` / `totalInBounds` sur `/map/bounds` | ✅ ce document |
| 4 | `truncated` sur `/programs` | ❌ impossible additivement, cf. lot 1 §3 |
| 5 | **Demande 1** — `/activities/browse` | à faire, suivant |
| 6 | **Demande 2** — pagination `/search` | à faire |
| 7 | **Demande 4** — RRULE | à faire |

Il ne reste que les trois gros. `/activities/browse` vient ensuite, sur la maille
`UserActivity` que vous avez validée.
