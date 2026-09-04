# Les créneaux n'ont pas de route par rectangle, et la carte le paie sur un pays

**Date :** 2026-09-04

> **Un défaut signalé par l'utilisateur, reproduit, et mesuré chez vous.**
> Dézoomé sur toute l'Allemagne, l'onglet **Activités** montre tout — 102
> programmes, `truncated: false`. L'onglet **Créneaux**, à la même échelle et
> sur le même geste, ne montre presque rien.
>
> **La cause n'est pas un bug, c'est une asymétrie de contrat.** `/map/bounds`
> prend un **rectangle** ; `/slots/feed` prend un **disque**, et son
> `radiusMeters` est borné à 50 km. Sur une vue à l'échelle d'un pays, la
> recherche de créneaux n'interroge donc qu'un disque de cinquante kilomètres
> autour du centre de l'écran — §1.
>
> **Nous demandons une route de créneaux par rectangle**, sur le modèle exact de
> `/map/bounds`, et nous expliquons pourquoi les trois contournements possibles
> côté app sont pires que le défaut — §2 et §3.
>
> **Ce que nous livrons en attendant** n'est pas une correction, c'est l'arrêt
> d'un mensonge : la carte annonçait « aucun créneau à venir dans cette zone » à
> propos d'une zone qu'elle n'avait pas cherchée — §4.

---

## 1. Ce que nous avons mesuré

Compte de test, production, le 04/09.

**Les activités couvrent le pays :**

```
GET /map/bounds?north=55.1&south=47.2&east=15.1&west=5.8&limit=500
→ programmes: 102 · activités: 28 · personnes: 26
  truncated: false · totalInBounds: 157
```

**Les créneaux ne le peuvent pas :**

```
GET /slots/feed?lat=51.1&lng=10.4&radiusMeters=400000
→ 400 VALIDATION_ERROR
  « radiusMeters : doit être inférieur ou égal à 50000 »
```

C'est bien le contrat : `SlotFeedRequest.radiusMeters`, `minimum: 500`,
`maximum: 50000`. Notre dépôt écrête donc avant d'envoyer — sans quoi ce `400`,
avalé, viderait l'onglet sans un mot. Il envoie 50 km là où l'écran en couvre
450.

**Et le centre d'un pays est le pire endroit où chercher :**

```
GET /slots/feed?lat=51.1&lng=10.4&radiusMeters=50000   (centre géographique)
→ 0 créneau
```

alors qu'il y en a **vingt-deux** répartis dans le pays, relevés disque par
disque :

| Ville | Créneaux dans 50 km |
|---|---|
| Cologne | 7 |
| Düsseldorf | 6 |
| Gelsenkirchen | 5 |
| Francfort | 4 |
| Munich | 3 |
| Hambourg | 3 |
| Leipzig | 3 |
| Berlin | 0 |
| Stuttgart | 0 |

Le geste de l'utilisateur — dézoomer sur un pays, puis « Rechercher dans cette
zone » — est donc précisément celui qui ne peut pas aboutir aujourd'hui.

---

## 2. La demande : les créneaux dans un rectangle

Deux formes possibles, et **la première nous convient mieux** :

**a. Ajouter une couche `slots` à `MapMarkersResponse`.** La route existe, la
carte l'appelle déjà pour l'onglet Activités, elle sait déjà tronquer et
compter (`truncated`, `totalInBounds`). Une couche de plus, et les deux onglets
partagent enfin la même géométrie.

```
GET /api/map/bounds?north=&south=&east=&west=&limit=&offset=
→ { users, activities, programs, slots, truncated, totalInBounds }
```

**b. Ou un `GET /api/slots/bounds`** aux mêmes paramètres que `MapBoundsRequest`,
rendant des `SlotFeedItemDto`. Si mêler les créneaux à une réponse dont la
sémantique est « marqueurs de lieux » vous gêne, cette forme-là nous va tout
autant.

**Ce dont nous avons besoin dans les deux cas :**

- les **mêmes filtres serveur** que `/slots/feed` — `from`, `to`,
  `createdSince`, `categoryIds`. Ce sont ceux que la carte porte déjà, et les
  perdre nous ferait filtrer à l'arrivée ce que nous savons aujourd'hui écarter
  au départ ;
- `distanceMeters` **facultatif** : sans centre, il n'a pas de sens, et nous ne
  l'affichons pas sur les pins ;
- une **borne de résultats** explicite, `limit` avec un plafond que vous
  choisissez, et le `truncated` qui va avec. Nous préférons une carte qui dit
  « il y en a plus » à une carte qui en cache en silence — c'est exactement ce
  que fait déjà `/map/bounds`, et notre bandeau de troncature est écrit ;
- la **même confidentialité de lieu** qu'au fil : un créneau dont la position
  n'est pas partagée ne doit pas apparaître. Nous l'écartons déjà à l'arrivée
  (`hasPreciseLocation`), et nous continuerons — mais la règle doit tenir des
  deux côtés.

**Ce que nous ne demandons pas :** relever le plafond de `/slots/feed`. Le fil a
sa géométrie — « autour de moi, à telle distance » —, elle est juste pour ce
qu'il fait, et `distanceMeters` en dépend. Ce n'est pas la même question que
« ce que montre un écran ».

---

## 3. Pourquoi nous ne contournons pas côté app

Trois chemins existent, et aucun ne tient :

**a. Découper le rectangle en disques de 50 km.** Couvrir l'Allemagne en
demanderait une quarantaine. À ~750 ms de plancher sur toute requête
authentifiée, c'est une demi-minute de réseau pour un geste qui doit répondre
tout de suite — et quarante fois le coût serveur pour la même information.

**b. Chercher au centre et laisser l'utilisateur balayer.** C'est le
comportement actuel, et c'est précisément ce qui a été signalé.

**c. Écrêter en silence, comme aujourd'hui, sans rien dire.** C'est le pire :
l'écran affirme alors quelque chose de faux. Nous le retirons — §4.

---

## 4. Ce que nous livrons aujourd'hui, et qui ne dépend pas de vous

La carte annonçait **« Aucun créneau à venir dans cette zone »**. Sur une vue à
l'échelle d'un pays, cette phrase est fausse : la zone n'a pas été cherchée. Le
bandeau dit désormais ce qui a réellement été fait —

> « Créneaux cherchés dans un rayon de 50 km autour du centre, pas dans toute la
> zone affichée. »

— et il se dit **que la carte soit vide ou non**. Une seule pin sur un écran qui
montre l'Allemagne laisse croire qu'il n'y en a qu'une : c'est le même mensonge,
en moins visible. Le bouton « élargir la zone » disparaît avec la phrase, parce
que la borne est la vôtre et non celle de notre curseur — un contrôle qui
n'élargit rien est pire que pas de contrôle.

Trois tests le tiennent (`test/map_slots_radius_cap_test.dart`), dont un
contre-test à l'échelle d'une ville : une réserve affichée là où la recherche
fait exactement ce qu'elle annonce apprendrait à ne plus la lire.

Le jour où la route arrive, ce bandeau disparaît de lui-même — il est
conditionné à l'écart entre la zone demandée et la zone interrogée, pas à un
drapeau.

---

## 5. Récapitulatif

| # | Demande | Nature |
|---|---|---|
| 1 | Les créneaux dans un rectangle — couche `slots` sur `/map/bounds`, **ou** `GET /slots/bounds` | demande |
| 2 | Les filtres `from` / `to` / `createdSince` / `categoryIds` sur cette route | demande |
| 3 | `limit` + `truncated`, comme `/map/bounds` | demande |
| 4 | La confidentialité de lieu tenue côté serveur aussi | confirmation |
| 5 | `distanceMeters` facultatif hors contexte géolocalisé | confirmation |
| — | Le plafond de `/slots/feed` | **rien à changer** — sa géométrie est juste pour ce qu'il fait |
