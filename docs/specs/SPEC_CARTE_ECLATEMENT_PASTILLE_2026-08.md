# Spec — éclater une pastille d'agrégat au lieu de la faire zoomer

> Le tap sur une pastille resserre la vue de deux paliers et n'explique rien.
> L'utilisateur tape, la carte bouge, la pastille est toujours là — parfois
> avec un nombre différent. Rien ne dit que le mouvement était la réponse à
> son geste, ni ce qu'il faut faire ensuite.
>
> Cette spec remplace ce tap par une **résolution** : un seul tap ouvre le
> groupe, pose ses membres à leurs vraies coordonnées, et laisse une trace de
> ce qui vient d'être ouvert. Elle ne demande rien au backend.

---

## 1. Ce qui existe, et pourquoi ça ne suffit pas

### 1.1 Le tap actuel

`_onClusterTap` (`map_page.dart`) fait deux choses :

```
_requestZoom >= kMapActivitiesMaxZoom → ouvre ClusterMembersSheet
sinon                                 → _moveTo(centre, zoom + 2)
```

Le zoom +2 n'est pas un choix esthétique, c'est une correction. Le recadrage
sur les bornes du groupe **a été la voie principale et a été retiré** : des
bornes déjà à l'échelle de l'écran donnent un `newLatLngBounds` qui laisse le
zoom où il est ; or l'agrégation est décidée par le serveur *au palier
demandé*, et c'est `_maybeRefetchActivitiesForZoom` — déclenché par un
changement de palier — qui fait réapparaître les membres. Zoom inchangé, même
agrégat. Mesuré en production : trois taps, aucun changement.

Toute solution qui fait dépendre la résolution de la caméra retombe dans ce
piège. **C'est le point que cette spec évite.**

### 1.2 L'éventail des créneaux existe déjà

Le mode Créneaux déploie les pins qui se recouvrent à l'écran
(`_fanSlotPositions`) et relie chaque pin déplacée à sa vraie coordonnée par
un trait de sa propre teinte. La règle est déjà écrite dans
`fanOutSearchPinPositions` :

> L'appelant relie chaque pin déployée à sa vraie coordonnée par un trait,
> pour que le décalage se lise comme **un artifice d'affichage et non comme
> une position réelle**.

Les primitives sont génériques et réutilisables telles quelles :
`groupByScreenProximity`, `fanOutSearchPinPositions` (anneaux concentriques),
`mapMetersPerPixel`, `kPinOverlapPx`.

### 1.3 Ce qu'une pastille ne sait pas

`MapActivityCluster` porte `count`, un barycentre, ses `bounds`, l'icône de la
catégorie dominante, et `activityIds` — **des identifiants, pas des
coordonnées**. Déployer en éventail autour du barycentre serait exactement le
mensonge que la pastille existe pour éviter : elle est un disque *parce
qu'elle ne désigne aucun lieu précis*, et le trait de l'éventail affirme
« celle-ci vient d'ici », ce qui serait faux pour chacun de ses membres.

---

## 2. Le levier

`MapRepository.fetchMapActivities` est indépendante de la caméra — centre,
rayon, palier, chacun libre — et sa documentation dit :

> `zoom` déclenche l'agrégation : fourni, le serveur regroupe les marqueurs
> proches dans `clusters` […]. **Absent, `clusters` est vide** et la réponse
> est celle d'avant.

La maille de ~1 km au palier maximal n'est donc pas un mur : c'est la
conséquence de *demander* une agrégation. Au tap, on interroge le serveur sur
le petit disque de la pastille **sans paramètre `zoom`** — et tous ses membres
reviennent en marqueurs individuels, avec leurs vraies coordonnées, sans
bouger la caméra et sans se soucier de la maille.

La signature accepte déjà un `cancelToken`.

---

## 3. Le comportement voulu

### 3.1 Un tap, une résolution

| | Avant | Après |
|---|---|---|
| 1ᵉʳ tap | zoom +2, pastille toujours là | le groupe s'ouvre, ses membres apparaissent |
| Résolution impossible | 2ᵉ, 3ᵉ tap sans effet, puis liste au palier max | liste, immédiatement |
| Retour | aucun | taper la trace referme |

La bascule vers la liste **ne dépend plus du zoom de la caméra** mais du
résultat de la résolution. Un tap n'est jamais sans effet.

### 3.2 Vraies positions d'abord, éventail seulement où il faut

Chaque membre est posé à sa `lat`/`lng` réelle. On passe ensuite
`groupByScreenProximity` sur le résultat et on ne déploie que les sous-tas qui
se recouvrent vraiment au zoom courant. **Les nœuds et arêtes n'apparaissent
donc que là où l'affichage a dû mentir, nulle part ailleurs.**

C'est la différence de fond avec un éclatement décoratif : un groupe étalé sur
trois quartiers ne produit aucun trait — ses membres sont simplement posés
chez eux.

### 3.3 Rendre le geste lisible

- **La pastille ouverte disparaît.** Sinon la carte montre sept membres *et*
  un disque « 7 » : un double compte.
- **Le groupe laisse une trace** : un cercle couvrant ses bornes, teinté de sa
  couleur, à faible opacité. Sans elle, une fois les membres dispersés, plus
  rien ne dit ce qui vient d'être ouvert ni d'où il venait.
- **La trace est tapable** et referme le groupe. Sans retour, l'état de la
  carte devient mystérieux dans l'autre sens.
- **Un accusé de réception** : une notice « N activités à cet endroit »
  (`mapClusterMembersTitle`, déjà traduite).
- **La caméra ne bouge que si nécessaire** — recadrage animé si les bornes
  débordent de la vue, immobile sinon. L'objection du §1.1 tombe, puisque la
  résolution ne dépend plus du zoom.

### 3.4 Tas denses

Au-delà de `kClusterExplodeMaxPins` membres, on pose les N plus proches du
barycentre et **une pastille de reste** portant le compte des autres, qui
ouvre la liste. Jamais de troncature muette.

Compter les **marqueurs**, jamais `activityIds` : le modèle avertit que la
liste peut être plus courte que `count` — une activité tenue en deux lieux
compte deux fois d'un côté, une fois de l'autre.

---

## 4. Le piège à ne pas rater

La requête ciblée est un **rayon**, pas le groupe. Elle peut ramener une
voisine que le serveur n'avait pas mise dans cette pastille — exactement le
défaut documenté du repli `/activities/browse` dans `cluster_members_sheet.dart`.

**Filtrage obligatoire de la réponse**, dans cet ordre :

1. sur les `bounds` du cluster, qui sont l'étendue *réelle des membres* et non
   la cellule de grille (avec une tolérance : le serveur peut arrondir) ;
2. sur `activityIds` **quand la liste est non vide**, en tolérant qu'elle soit
   un sous-ensemble — un membre dont l'identifiant n'y figure pas alors que la
   liste existe n'appartient pas au groupe.

Bornes inutilisables (`hasUsableBounds == false` : membres exactement
co-localisés) → on ne filtre que sur `activityIds`, et à défaut sur un rayon
serré.

---

## 5. Découpage

### 5.1 Domaine pur — `map/domain/cluster_explosion.dart`

Testable sans caméra ni plateforme, comme `marker_filtering` et
`activity_expiry` :

- `clusterExplosionRadiusMeters(cluster)` — rayon à interroger, déduit des
  bornes plus une marge ; repli sur `kClusterMembersRadiusMeters` (1 200 m,
  déjà défini et documenté pour ce cas).
- `membersOfCluster(candidates, cluster)` — le filtrage du §4.
- `capExplodedMembers(members, center, max)` — les N plus proches du
  barycentre, et le reste compté.

### 5.2 Présentation — `map_page.dart`

État ajouté :

```
MapActivityCluster? _openedCluster;      // le groupe ouvert
List<MapActivityDto> _openedMembers;     // ses membres résolus
Map<PolylineId, Polyline> _openedFanLinks;
Map<MarkerId, LatLng> _openedPinPositions;
double? _openedFanZoom;                  // échelle du dernier déploiement
CancelToken? _openedResolveToken;
Map<String, List<MapActivityDto>> _clusterMembersCache;
```

Invariants, tous hérités des règles existantes de la page :

- les membres sont **redérivés dans `_buildActivityMarkers`**, comme
  `_clusters` l'est déjà — cette fonction purge tout, y écrire une fois ne
  tiendrait pas au premier rebuild ;
- ils passent par `_withinDisplayedHorizon` et `activityMatches` comme tout
  le reste : aucun marqueur n'échappe à l'horizon ;
- l'éventail ne se recalcule qu'au changement d'échelle, garde `_openedFanZoom`
  sur le modèle de `_slotFanZoom` ;
- purge du groupe ouvert au changement de mode, de filtre, sur « Rechercher
  dans cette zone », et quand la caméra quitte les bornes.

### 5.3 Optimisations

- **Cache de session** par identité de pastille (barycentre arrondi + count) :
  re-taper est instantané, et revenir après un dézoom ne redemande rien.
- **Annulation** du jeton si une autre pastille est touchée pendant la
  résolution.
- Le cache d'icônes mémoïse déjà par (catégorie, teinte, compte, sélection) :
  les pins éclatées sont quasi gratuites à dessiner.
- **Bilan réseau positif** : une petite requête bornée remplace les trois ou
  quatre rechargements plein écran que provoque aujourd'hui l'escalier de zoom.

Écarté volontairement : pré-résoudre les petites pastilles au rendu. Ce serait
dépenser des requêtes sur des groupes que l'utilisateur ne touchera peut-être
jamais.

---

## 6. Ce qui reste dû au backend

Le correctif est entièrement client, mais il contourne une cause qui demeure :
la maille d'agrégation vaut encore ~1 km au palier 20. Deux demandes, par
ordre de valeur :

1. **Que la maille suive réellement le zoom aux paliers hauts** (~50 m à 20).
   La résolution par zoom redeviendrait naturelle et la requête ciblée ne
   serait plus qu'un raccourci.
2. À défaut, **que le cluster porte les coordonnées de ses membres** (ou un
   `GET /map/clusters/{clé}/members`), ce qui supprimerait l'aller-retour.

Tant que ni l'une ni l'autre n'est livrée, la requête sans `zoom` sur un rayon
serré est le seul moyen d'obtenir des positions de membres — et elle est
suffisante.
