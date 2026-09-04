# Réponse — les créneaux dans un rectangle

**Date :** 2026-09-04 · Réponse à `PROMPT_BACKEND_2026-09-04.md`

> **Livré.** `GET /api/slots/bounds` existe, avec les quatre bornes et la
> pagination de `/map/bounds`, et les filtres de `/slots/feed`. Votre bandeau
> peut disparaître.
>
> **Votre diagnostic est juste sur toute la ligne**, y compris sur le point que
> vous ne demandiez pas : le plafond de `/slots/feed` ne bouge pas. Répondre à
> une question rectangulaire par un disque n'était pas un réglage trop bas,
> c'était la mauvaise géométrie — §1.
>
> **Nous avons pris la forme (b), pas la (a) que vous préfériez.** Une couche
> `slots` sur `/map/bounds` ferait payer à chaque onglet le calcul de l'autre, et
> ferait parler des créneaux à un `truncated` que votre onglet Activités lit
> déjà. L'argument complet est en §2 — si vous n'en êtes pas convaincus, la
> couche reste faisable et la question est rouverte.
>
> **Un écart va plus loin que votre demande, et il faut le lire :** un créneau
> dont la position n'est pas partagée **n'apparaît pas** dans cette réponse, ni
> dans son compte. Vous demandiez que la règle tienne des deux côtés ; elle ne
> pouvait pas se contenter de tenir. §4.

---

## 1. Ce que nous avons vérifié avant d'écrire

Votre relevé est exact, et le code le confirme ligne à ligne :

| Votre affirmation | Vérifiée dans |
|---|---|
| `radiusMeters` est borné à `[500, 50000]` | `SlotFeedRequest` — `@Min(500) @Max(50000)` |
| `/map/bounds` prend un rectangle, `/slots/feed` un disque | `MapBoundsRequest` contre `SlotFeedRequest` |
| Le fil sait déjà filtrer `from`/`to`/`createdSince`/`categoryIds` | `ScheduleRepository.OPEN_SLOTS_IN_RADIUS_BODY` |
| `/map/bounds` sait déjà tronquer et compter | `MapMarkersResponse.truncated` / `totalInBounds` |

Et votre conclusion l'est aussi. Le disque de `/slots/feed` n'est pas une borne
trop basse qu'il suffirait de relever : c'est une géométrie centrée, et son
`distanceMeters` en dépend. Un rayon de 400 km autour du centre de l'Allemagne
n'aurait d'ailleurs pas répondu à votre geste — il aurait balayé la moitié de la
France et de la Pologne pour couvrir un écran qui ne les montre pas.

**Le plafond de `/slots/feed` ne change donc pas**, comme vous le demandiez. La
route est inchangée, à l'octet près.

---

## 2. Pourquoi une route dédiée, et pas une couche sur `/map/bounds`

Vous nous laissiez le choix en préférant la couche. Nous prenons la route
séparée, pour trois raisons dont la deuxième est la plus lourde.

**a. Vos deux onglets sont deux appels.** Aujourd'hui l'onglet Activités appelle
`/map/bounds` et l'onglet Créneaux appelle `/slots/feed` : deux gestes, deux
requêtes. Fondus dans une seule réponse, chaque onglet paierait le calcul de
l'autre — l'agrégation des personnes et des activités pour qui ne veut que des
créneaux, la recherche de créneaux pour qui ne veut que des programmes. Sur la
route la plus visible de l'app, et avec le plancher que vous mesurez, ce n'est
pas un détail. La route dédiée, elle, est un remplacement d'une ligne chez vous :
mêmes bornes que `/map/bounds`, mêmes filtres que `/slots/feed`, même DTO.

**b. `truncated` et `totalInBounds` sont déjà lus.** Sur `/map/bounds` ils
agrègent les trois couches. Une quatrième les ferait parler aussi des créneaux —
et votre bandeau de troncature de l'onglet Activités, qui les lit aujourd'hui,
se mettrait à s'allumer pour des créneaux qu'il n'affiche pas. Ce serait un
changement de sens d'un champ existant, invisible à la compilation, sur un client
déjà déployé. Nous ne savons pas le faire sans vous casser quelque chose.

**c. Un rectangle de créneaux n'a pas la même population qu'un rectangle de
marqueurs.** Voir §4 : la règle de lieu y est plus stricte, et devoir dire « ce
champ-là de cette réponse-ci obéit à une autre règle que ses voisins » est
exactement le genre de contrat qu'on finit par mal lire.

Cela dit : la couche `slots` sur `/map/bounds` reste faisable, et si votre carte
ne fait en réalité qu'un seul appel pour les deux onglets, l'argument (a) tombe
et nous la ferons. Dites-le-nous.

---

## 3. Le contrat

```
GET /api/slots/bounds
    ?north=&south=&east=&west=          (obligatoires)
    &from=&to=&createdSince=
    &categoryIds=&categoryId=&activityId=
    &languages=&accessibilityTags=
    &limit=&offset=
→ 200 { slots: SlotFeedItemDto[], truncated: bool, totalInBounds: int }
```

**Les bornes** portent les noms et les règles de `MapBoundsRequest` — au sens
propre : les trois routes à bbox (`/map/activities`, `/map/bounds`,
`/slots/bounds`) partagent désormais un seul code de validation
(`shared/GeoBounds`), là où il était recopié. Un rectangle inversé, à cheval sur
l'antiméridien ou hors du globe rend `400 MAP_BOUNDS_INVALID`, avec le même
message qu'ailleurs.

**Les filtres** sont ceux du fil, et un peu plus. Vous demandiez `from`, `to`,
`createdSince`, `categoryIds` : ils sont là. `activityId`, `languages` et
`accessibilityTags` le sont aussi — non par générosité, mais parce que les deux
routes partagent maintenant le même corps de requête SQL et le même code de
normalisation des filtres (`SlotFilters`). Les retirer aurait demandé du travail
pour créer une divergence.

**`from`/`to` valent par défaut « maintenant » et « dans sept jours »**, comme
sur le fil. Un écran de carte qui veut un horizon plus large doit le demander ;
il ne l'obtient pas en dézoomant. Nous préférons vous le dire que vous laisser le
découvrir.

**`limit`** vaut 100 par défaut, **plafond 200**, et au-delà c'est un `400`, pas
un écrêtage. Tout ce lot est né d'une borne rabotée sans le dire : nous n'allons
pas en réintroduire une un étage plus bas. Le plafond n'est pas arbitraire —
chaque **organisateur distinct** rendu coûte le chargement de son profil public
(profil, compteur d'abonnés, lien d'abonnement, badges), et c'est lui, pas le
nombre de créneaux, qui gouverne le temps de réponse. Si 200 vous serre un jour,
la réponse ne sera pas de relever le chiffre mais de charger ces profils en lot ;
c'est un chantier identifié, pas fait ici.

**`truncated` et `totalInBounds`** ont la sémantique de `/map/bounds`, avec une
précision en mieux : **`totalInBounds` est exact.** Il vient d'un `COUNT` portant
sur exactement le même `WHERE` que la page rendue — mêmes filtres, même règle de
lieu, même prédicat de blocage, même exclusion. Un total qui compterait ce que la
page n'a pas le droit de montrer serait une fuite à lui seul : annoncer « il y en
a trois ici » situe déjà trois créneaux dans le rectangle. `truncated` vaut aussi
vrai quand `offset` dépasse le total — une page vide sur une zone qui ne l'est
pas ne doit pas se lire comme une zone vide.

**`distanceMeters` est nul**, comme vous le prévoyiez. Sans centre il n'y a rien
à mesurer, et le mesurer depuis le centre du rectangle rendrait un nombre que
personne n'a demandé et qui changerait à chaque geste de zoom.

**Les créneaux sont classés par date de début**, puis par identifiant. Le fil,
lui, pondère par vos disponibilités déclarées : cela n'a pas de sens ici, où
l'ordre n'est pas montré — une carte n'a pas de premier pin. Il ne décide que
d'une chose, ce qui survit à `limit`, et « les plus proches dans le temps » est
la seule réponse défendable devant quelqu'un qui n'en verra pas la moitié.

---

## 4. Deux écarts qui vont plus loin que la demande

### 4.1 Un créneau à position masquée n'apparaît pas — ni dans le compte

Vous écriviez : « nous l'écartons déjà à l'arrivée (`hasPreciseLocation`), et
nous continuerons — mais la règle doit tenir des deux côtés ». Elle ne pouvait
pas se contenter de tenir des deux côtés : côté serveur, elle devait devenir plus
stricte que celle du fil.

Dans `/slots/feed`, un créneau dont le lieu n'est pas partagé remonte **sans
coordonnées** : il est trouvable, il n'est pas situé. C'est correct, et cela ne
change pas.

Sur un rectangle, ça ne l'est plus : la question posée **est** géographique.
Répondre « ce créneau est dans ce rectangle », même sans `lat`/`lng`, le situe
dans le rectangle — et zoomé assez près, le rectangle *est* l'adresse. Votre
filtre à l'arrivée n'y pouvait rien : il aurait retiré le pin après que le
serveur eut déjà répondu à la question.

Le filtre est donc **dans le `WHERE`**, et il reproduit exactement
`SlotAddressVisibility.resolve` : jamais de lieu `ONLINE` ni sans coordonnées, et
sinon `PUBLIC`, ou adresse exacte assumée, ou une **participation `CONFIRMED` de
celui qui regarde** — cette dernière branche comptant, sans quoi on cacherait à
quelqu'un un créneau dont il connaît déjà l'adresse et où il est attendu.

Deux conséquences pour vous :

- **tout élément rendu par cette route porte des `lat`/`lng` non nuls.** Aucune
  autre lecture de `SlotFeedItemDto` ne vous le garantit ; celle-ci oui. Votre
  filtre `hasPreciseLocation` devient une ceinture, gardez-la ;
- `totalInBounds` ne compte pas ces créneaux non plus.

### 4.2 Vos propres créneaux ne remontent pas

Le fil les écarte déjà. Les laisser apparaître ici ouvrirait, au tap sur le pin,
une feuille d'inscription à son propre créneau. La règle est donc la même — mais
appliquée en SQL et non après coup, pour que le compte et la page restent
d'accord.

Si votre carte veut au contraire montrer à l'organisateur ses propres séances,
c'est une couche à part et nous la ferons : « ce que j'organise » et « ce que je
peux rejoindre » ne sont pas la même liste, et les mélanger silencieusement dans
la seconde serait le mauvais choix par défaut.

---

## 5. Vérification

`SlotBoundsIntegrationTest` — **12 tests, verts**, contre le schéma réel.

Le premier reproduit votre mesure : deux créneaux à 450 km l'un de l'autre,
Cologne et Munich. Il vérifie **d'abord** qu'aucun des deux n'est visible au fil
depuis le centre du pays au rayon maximum — sans ce contre-test, il passerait
aussi bien si nous n'avions fait qu'élargir le disque — puis que le rectangle
allemand les rend tous les deux.

Les autres tiennent, chacun, une phrase de ce document : un créneau hors du
rectangle ne remonte pas ; un lieu privé non partagé est absent de la carte
**mais toujours présent dans le fil, sans coordonnées** (l'asymétrie du §4.1,
verrouillée dans les deux sens) ; le même créneau réapparaît pour un participant
confirmé ; tout élément rendu porte des coordonnées ; `distanceMeters` est nul ;
`limit=1` sur deux créneaux dit `truncated: true` et `totalInBounds: 2` ;
`limit=500` est un `400` ; un rectangle inversé est un `400 MAP_BOUNDS_INVALID` ;
ses propres créneaux ne remontent pas, **et le compte est d'accord avec la
page** ; le filtre de catégorie porte ; un créneau à trente jours est hors de la
fenêtre par défaut et revient avec `to`.

La suite complète a été relancée : aucune régression sur les routes existantes,
y compris `/map/bounds` et `/map/activities`, dont la validation de bbox a été
déplacée dans `shared/GeoBounds`.

---

## 6. Récapitulatif

| # | Votre demande | Réponse |
|---|---|---|
| 1 | Les créneaux dans un rectangle | **Livré**, forme (b) : `GET /api/slots/bounds`. Le choix est argumenté en §2 et reste discutable |
| 2 | `from` / `to` / `createdSince` / `categoryIds` | **Livré**, plus `activityId`, `languages`, `accessibilityTags` |
| 3 | `limit` + `truncated` | **Livré.** Défaut 100, plafond 200, `400` au-delà et non un écrêtage. `totalInBounds` exact |
| 4 | La confidentialité de lieu côté serveur | **Livré, et plus strict que demandé** : le créneau est absent de la réponse *et* du compte — §4.1 |
| 5 | `distanceMeters` facultatif | **Confirmé** : nul sur cette route |
| — | Le plafond de `/slots/feed` | **Inchangé**, comme vous le demandiez |
| — | *(non demandé)* Vos propres créneaux | Écartés, comme dans le fil — §4.2 |

**Ce qu'il vous reste à faire :** remplacer l'appel à `/slots/feed` de l'onglet
Créneaux par `/slots/bounds` avec les bornes de l'écran. Votre bandeau
« cherchés dans un rayon de 50 km » s'éteindra alors de lui-même, puisque vous
l'avez conditionné à l'écart entre la zone demandée et la zone interrogée — et
il n'y aura plus d'écart. Le bandeau de troncature, lui, a de quoi s'allumer :
`truncated`.
