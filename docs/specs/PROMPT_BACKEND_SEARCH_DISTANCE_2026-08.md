# Demande backend — `/search` situe un programme chez son organisateur, pas à son lieu

> Un résultat de type `program` porte les coordonnées **du compte qui l'a créé**,
> jamais celles du lieu où il se tient. La distance affichée à l'utilisateur en
> découle, et elle est fausse d'autant.
>
> Le calcul de distance, lui, est juste : il mesure fidèlement l'écart entre le
> point qu'on envoie et le point que vous renvoyez. C'est ce second point qui
> n'est pas le bon. Rien à corriger côté client — nous affichons ce que vous
> dites.
>
> Reproduit en production le 2026-08-15 avec un compte réel.

---

## 1. Le symptôme, tel qu'il se voit

Un utilisateur physiquement à Herne (51,5742 / 7,0273) cherche « vibe coding ».
Les quatre résultats s'affichent **tous à 448 km**. Ce sont ses propres
programmes, et leurs séances se tiennent à quelques kilomètres de lui.

## 2. Ce qui se passe

`POST /search` avec `{"query":"vibe coding","lat":51.5742,"lng":7.0273,"radiusMeters":2000000}` :

| titre | `lat` / `lng` rendus | `distanceMeters` |
|---|---|---|
| Programmation | 52.52 / 13.405 | 448 483 |
| intro vb | 52.52 / 13.405 | 448 483 |
| Frontend | 52.52 / 13.405 | 448 483 |
| Anime | 52.52 / 13.405 | 448 483 |

Quatre programmes distincts, une seule coordonnée. Et `52.52 / 13.405` est
exactement la position du profil de l'organisateur — vérifiée sur
`GET /users/me` du même compte :

```
lat = 52.52
lng = 13.405
```

Le même appel avec `lat/lng` = Berlin rend `distanceMeters: 0.0` pour les
quatre. **Votre calcul de distance est donc correct** ; c'est la coordonnée du
résultat qui ne décrit pas le programme.

## 3. La preuve que ce n'est pas le lieu du programme

Le programme « Anime » (`a1821526-7bc8-427f-910f-8f32f7cb1e82`), tel que
`GET /programs/{id}` le décrit :

```
créneau : Apollo Cinemas Multiplex, Willy-Brandt-Allee, Gelsenkirchen-Ost
          lat 51.5513825   lng 7.0758985
```

`/search` en dit `52.52 / 13.405`. Écart : 448 km.

## 4. Le cas qui exclut toute autre explication

Recherche « yoga », mêmes conditions. Trois programmes de la même organisatrice,
trois villes différentes dans les titres, **une seule coordonnée** :

| titre | `lat` / `lng` | organisateur |
|---|---|---|
| Pilates & Barre Intensivkurs **Bremen** | 53.0793 / 8.8017 | Sarah Richter |
| Barre Fitness **Bremen** Online | 53.0793 / 8.8017 | Sarah Richter |
| Pilates Pop-up **München** | 53.0793 / 8.8017 | Sarah Richter |

Un cours à Munich annoncé à Brême. Et le motif se répète d'un organisateur à
l'autre — Camille Bertrand : Paris ; Mia Wolf : Dresde. **La coordonnée suit le
compte, pas le programme.**

## 5. Ce qui marche déjà, et qu'il suffit d'imiter

Les résultats de type `slot` sont **justes**. Même requête, même origine :

```
slot | Kickboxen Fortgeschrittene Düsseldorf | 51.235 / 6.805  | 40,7 km
slot | Basketball 3x3 Street Turnier Dortmund | 51.51 / 7.458  | 30,6 km
```

Ils portent la coordonnée de leur créneau, et la distance tombe juste. Le défaut
ne concerne donc que la branche `program` de la construction des résultats.

## 6. Ce que nous demandons

Qu'un résultat `program` porte la coordonnée **du lieu de sa séance**, comme le
fait déjà un résultat `slot`.

Le choix de la séance nous importe peu tant qu'il est explicite, mais celui qui
a du sens pour une distance est **la plus proche du point interrogé** — c'est ce
que l'utilisateur lit comme « à quelle distance de moi ». À défaut, la prochaine
occurrence conviendrait.

Deux cas à trancher de votre côté, et à documenter :

- **Programme sans aucune séance géolocalisée** — nous préférons `lat`/`lng` à
  `null` plutôt qu'un repli : nous savons ne rien afficher, nous ne savons pas
  deviner qu'un chiffre est faux. Aujourd'hui la coordonnée de l'organisateur
  tient ce rôle de repli silencieux, et c'est précisément ce qui rend le défaut
  invisible depuis vos logs.
- **Programme en ligne** (`isOnline: true`) — une distance n'a pas de sens. Là
  encore, `null` plutôt qu'un point arbitraire.

## 7. Remarque annexe, sans lien avec la demande

La position de profil du compte de test vaut `52.52 / 13.405`, c'est-à-dire le
centre de Berlin au dix-millième près. Cela ressemble à une valeur par défaut
posée à l'inscription plutôt qu'à une position réelle. Sans incidence sur ce qui
précède — le défaut demeurerait avec une position exacte — mais si c'est bien un
défaut, il vaut la peine d'être regardé : il fait de tous les comptes de Berlin
des voisins parfaits.

## 8. Comment rejouer

```bash
TOKEN=$(curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"…","password":"…"}' | jq -r .accessToken)

curl -s -X POST "$API/search" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"yoga","lat":51.5742,"lng":7.0273,"radiusMeters":2000000}' \
  | jq '.results[] | select(.resultType=="program")
        | {title, lat, lng, organizerName, distanceMeters}'
```

Le motif se lit en une ligne : regrouper la sortie par `organizerName` donne une
coordonnée unique par organisateur, quel que soit le nombre de villes.
