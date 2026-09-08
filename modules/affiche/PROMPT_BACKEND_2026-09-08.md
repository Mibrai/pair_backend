# `GET /recaps/mine` répond en soixante secondes — et éteint le module affiche en entier

**Écrit le 2026-09-08.**
Fait suite à : [`SUITE_CLIENT_2026-09-07.md`](SUITE_CLIENT_2026-09-07.md) ·
Plan : [`PLAN_IMPLEMENTATION_2026-09-07.md`](PLAN_IMPLEMENTATION_2026-09-07.md)

> **Une seule demande, et elle est bloquante.** `GET /recaps/mine` rend `200` en **60 à
> 63 secondes** pour 35 cartes. Le client abandonne à 30 s (`receiveTimeout`), donc la liste
> n'arrive **jamais** — et avec elle tombent l'affiche du fil, la galerie « Mes affiches » et
> l'écran « Mes moments », qui en dérivent tous.
>
> Ce n'est pas une régression du module affiche : il n'ajoute aucun appel, il **lit** cette
> route. Vérifié en montant le délai client à 120 s le temps d'un essai — l'affiche apparaît, et
> tout le reste avec elle.
>
> Ce que nous demandons : **ramener le coût par carte à ce qu'il est ailleurs.** Pas de
> pagination — § 4 dit pourquoi elle ne réglerait rien ici.

---

## 1. Les relevés

Compte `seyd.njoya@icloud.com` (`00000000-0000-0000-0000-000000000001`), 35 cartes.
Mesures au `curl`, `time_starttransfer`, 2026-09-08, environnement de production
(`pairbackend-production-35fe.up.railway.app`). Jeton obtenu par `POST /auth/login` juste avant.

| Route | Cartes rendues | Temps jusqu'au premier octet |
|---|---:|---:|
| `GET /users/me` | — | **0,92 s** |
| `GET /recaps/feed?lat=…&lng=…&radiusMeters=50000` | **0** | **0,88 s** |
| `GET /users/{id}/recaps` | **1** | **3,99 s** |
| `GET /recaps/mine` | **35** | **63,0 s** |
| `GET /recaps/mine` (2ᵉ appel) | 35 | **61,5 s** |
| `GET /recaps/mine` (3ᵉ appel) | 35 | **61,6 s** |
| `GET /recaps/mine` (4ᵉ appel, jeton neuf) | 35 | **60,6 s** |

Quatre appels, quatre fois le même ordre de grandeur : ce n'est ni un démarrage à froid, ni un
incident ponctuel, et rien ne semble mis en cache d'un appel à l'autre.

**Ce que ces chiffres disent, et c'est le point utile pour vous :** une réponse à **zéro carte**
coûte 0,88 s — c'est-à-dire le plancher connu de toute requête authentifiée, celui relevé le
22/08 après la correction du N+1 des programmes. Le coût est donc **par carte**, pas dans la
route :

- 1 carte → ~3,1 s au-dessus du plancher ;
- 35 cartes → ~59,7 s au-dessus du plancher, soit **~1,7 s par carte**.

Un `SlotRecapDto` coûte des **secondes** à construire. C'est cela que nous vous signalons.

---

## 2. Ce que ça casse chez nous, et pourquoi ça ne se voit pas

Le client abandonne à 30 s (`receiveTimeout`, `lib/core/network/api_client.dart:21`) — une valeur
qui n'a jamais été atteinte par aucune autre route. L'appel se termine donc toujours ainsi :

```
DioException  type=receiveTimeout  status=null   →  NetworkException
myRecapsProvider : AsyncError
```

Or **tout le module affiche dérive de cette seule liste** — c'est une propriété revendiquée du
module, écrite au § « zéro requête neuve » du plan : composer une affiche ne coûte pas un octet
de plus que « Mes moments ». La contrepartie est qu'il hérite du sort de `/recaps/mine` :

| Ce qui tombe | Ce que l'utilisateur voit |
|---|---|
| `afficheProvider` (l'affiche du fil) | rien — le fil est celui d'avant |
| `galerieAffichesProvider` (« Mes affiches ») | rien — la section ne se montre pas |
| `afficheParCreneauProvider` (lien, notification) | l'écran se tait |
| `myMomentsProvider` (l'écran « Mes moments ») | l'écran d'erreur |

Les trois premiers rendent `null` ou une liste vide **sans un mot**, et c'est délibéré : une
affiche est un supplément, elle n'a pas à s'excuser d'une liste qui n'est pas arrivée. C'est ce
qui rend le défaut si difficile à voir de l'extérieur — l'app ne signale rien, elle est
simplement celle d'avant le module.

---

## 3. Ce que nous avons écarté avant de vous écrire

Nous ne vous adressons pas une hypothèse. Dans l'ordre :

1. **La sélection.** Rejouée hors app sur la réponse réelle de `/recaps/mine` (35 cartes
   récupérées au `curl`) : elle rend `premierePratique` pour la dernière séance — « Dessin manga
   & BD », 08/09 — et **15 affiches** pour la galerie. Composition et garde passent.
2. **Les drapeaux.** `afficheCompose` et `afficheGalerie` sont à `true` depuis le 08/09, les
   points d'insertion sont en place dans le fil et dans « Mes moments ».
3. **Les tests.** Les 242 tests du module sont verts.
4. **L'app réelle.** Instrumentée et lancée sur simulateur avec le compte de test : la trace
   montre `getMine → DioExceptionType.receiveTimeout`, puis `candidat=null`. En montant le seul
   `receiveTimeout` à 120 s, la même app affiche la carte « Ton affiche est prête » et compose
   « Première fois : Dessin manga & BD. »

Le module est intact. Ce qui manque, c'est la liste.

---

## 4. Ce que nous demandons — et ce que nous ne demandons pas

### Ce que nous demandons

**Supprimer le coût par carte.** Une cible qui nous suffit : `/recaps/mine` sous **3 secondes**
pour ces 35 cartes, c'est-à-dire l'ordre de grandeur des autres routes de liste.

Nous n'avons pas accès à votre code et ne prétendons pas nommer la cause. Ce que la réponse
elle-même laisse voir, et qui vaut peut-être la peine d'être regardé en premier :

- les 35 cartes n'ont que **3 hôtes distincts**, et chaque carte porte un `host` complet —
  `badgeCodes` (13 entrées), `activities`, `subscriberCount`, `subscribed`,
  `verificationStatus`, `reliabilitySignal`. Si ce bloc est résolu par carte plutôt que par
  hôte, il est payé 35 fois pour 3 réponses distinctes ;
- `nextSlot` est renseigné sur **20 cartes sur 35**, et porte `participantCount`,
  `maxParticipants` et `alreadyJoined` — soit, a priori, une recherche du prochain créneau du
  programme **et** un décompte de participants, par carte ;
- `topVibes`, `myVibes`, `visibleAttendees`, `canContribute` et `recapWindowClosesAt` semblent
  eux aussi se calculer carte par carte. `visibleAttendees` n'est non vide que sur **1** carte
  sur 35, ce qui coûte 34 requêtes pour rien.

Le rapprochement qui nous paraît le plus parlant : `/recaps/feed` **à zéro carte** répond en
0,88 s. Le travail n'est donc pas dans l'authentification ni dans la requête de tête.

### Ce que nous ne demandons **pas**, et pourquoi il faut le dire

**Pas de pagination.** C'est la réponse naturelle à « une liste trop longue », et ici elle ne
réglerait rien — elle casserait le module.

Une affiche n'est pas un état, c'est une **transition** : « première fois en escalade » ne veut
pas dire « je n'ai qu'une séance d'escalade », mais « **cette séance-ci** a fait passer mon
histoire de zéro à une ». Pour le dire, il faut **tout ce qui précède**. Sur une page de dix
cartes, l'app annoncerait une première fois qui n'en est pas une, et l'affiche se remettrait à
sortir à chaque séance — le seul défaut que ce module ne peut pas se permettre.

Le client devrait donc parcourir toutes les pages, et paierait exactement les mêmes soixante
secondes, en plusieurs fois.

**Pas non plus « montez votre délai ».** Nous pouvons le faire, mais cela échangerait une absence
contre une minute d'écran figé sur « Mes moments », à chaque ouverture, pour un écran que le
produit veut immédiat. Ce serait déplacer le défaut, pas le corriger.

---

## 5. Deux questions annexes, qui coûtent une phrase chacune

**a. Une mise en cache est-elle prévue sur cette route ?** Quatre appels successifs coûtent le
même prix. Si le calcul reste cher après optimisation, une réponse mémorisée par utilisateur et
invalidée sur contribution suffirait à notre usage : la liste ne change qu'après une séance ou
une ambiance ajoutée.

**b. `/users/{id}/recaps` est concerné aussi** — 3,99 s pour **une** carte, soit le pire coût
unitaire relevé. C'est la route qui dessinera l'affiche d'autrui le jour où la publication
s'allumera (demande B1 du 07/09). Ce qui corrige `/recaps/mine` devrait la corriger aussi.

---

## 6. Ce qui reste ouvert du 07/09, et n'a pas bougé

- **B4** — *(question, une phrase)* `/recaps/mine` rend-elle **une carte par présence
  confirmée**, ou **seulement les cartes portant au moins une contribution** ? La réponse décide
  d'une propriété du module : si l'histoire peut changer *dans le passé*, une affiche déjà vue
  peut revenir, et il nous faut alors une monotonie rangée côté client. Nous ne l'écrivons pas
  tant que la réponse n'est pas connue.
  *Ce que le relevé du jour ne permet pas de trancher, et c'est pourquoi la question tient :* les
  35 cartes rendues portent **toutes** au moins une contribution — une ambiance, une photo, un
  mot de l'hôte ou un participant nommé. C'est compatible avec les deux réponses. Nous préférons
  votre phrase à notre déduction.
- **B5** — le type de notification `AFFICHE_READY` reste absent du relevé. Non bloquant ; il est
  déclaré et routé côté client, avec les deux avertissements du prompt du 07/09 (un `title` et un
  `body` réels et non vides, aucun enrichissement `programTitle`/`placeName`/`sessionAt`).
- **L'écart du § 3 de `SUITE_CLIENT_2026-09-07.md`** — `AfficheDto` ne porte aucun champ
  d'affichage, donc une affiche publiée reste invisible chez autrui tant que l'hôte n'a pas rendu
  le souvenir public. À trancher avant d'allumer `affichePublication`.
