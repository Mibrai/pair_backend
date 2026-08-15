# Réponse backend — la maille d'agrégation suit désormais le zoom

> Réponse au §6 de `SPEC_CARTE_ECLATEMENT_PASTILLE_2026-08.md`. Votre demande
> n°1 est **livrée**. Votre demande n°2 perd l'essentiel de son objet, et sa
> moitié utile était déjà servie.
>
> Un mot avant tout : **livrez votre correctif client quand même.** La maille
> corrigée ne rend pas l'éclatement inutile, pour trois raisons développées plus
> bas — dont une que votre spec n'anticipe pas et qui touche votre §4.

---

## Votre diagnostic était juste, et le défaut était pire

Vous écrivez « la maille d'agrégation vaut encore ~1 km au palier 20 ». C'est
exact. Mais ce n'était pas un réglage trop grossier en un point : c'était une
**pente fausse sur toute la plage**.

La table divisait la maille par deux **tous les deux paliers**, quand la
résolution de la carte l'est à **chaque** palier. La cellule doublait donc de
taille apparente à chaque niveau gagné. Mesuré en projection Web Mercator à la
latitude de Paris, le côté d'une cellule passait de 11 px au palier 1 à
**11 332 px au palier 20** — vingt-huit largeurs d'écran téléphone.

Conséquence que vous ne pouviez pas voir depuis vos symptômes : **les paliers
intermédiaires étaient plus grossiers encore que le palier 20 en valeur
relative**, et le palier 16 l'était même en valeur absolue — 2,2 km de maille,
soit deux fois la cellule du palier 18. Votre escalier de zoom `+2` traversait
donc une zone où monter d'un cran pouvait ne rien défaire du tout. Cela explique
vos « trois taps, aucun changement » mieux que le seul plafond à 1 km.

## Ce qui change

| zoom | maille avant | maille après |
| --- | --- | --- |
| 13 | 11 km | 5,6 km |
| 14 | 5,6 km | 2,8 km |
| 16 | 2,2 km | 696 m |
| 18 | 1,1 km | 174 m |
| 20 | 1,1 km | **43 m** |

Les 43 m tombent sur le « ~50 m à 20 » que vous demandiez. Ce n'est pas une
coïncidence : votre ordre de grandeur est exactement ce qu'on obtient en gardant
le comportement des paliers moyens et en rétablissant la pente. Votre chiffre
était bon.

Les paliers ≤ 12 sont **inchangés à l'octet près**. La même dérive les rend au
contraire trop *fins* (11 px de cellule au palier 1 : quasiment aucune
agrégation sur une carte monde), mais corriger cela change un comportement
visible qu'aucune demande ne réclame. Le défaut est réel et reste ouvert —
dites-nous s'il vous gêne.

Le correctif profite aux deux routes qui agrègent : `GET /map/activities?zoom=`
et `GET /map/clusters`.

## Pourquoi livrer votre correctif quand même

**1. C'est une grille fixe, pas un regroupement par proximité.** Le serveur
découpe le plan en cellules et groupe ce qui tombe dans la même. Deux marqueurs
distants de dix mètres, de part et d'autre d'une frontière, ne sont **pas**
groupés ; deux marqueurs séparés par presque la diagonale d'une cellule le sont.
Affiner la maille resserre cet écart, il ne le supprime pas.

Cela touche directement votre §4. Votre requête ciblée ramènera ce voisin à dix
mètres, et votre filtrage sur `bounds` + `activityIds` le **rejettera** —
correctement au regard de votre spec, mais l'utilisateur verra le groupe s'ouvrir
en sept pins avec une huitième, collée, restée pastille séparée. Le filtrage fait
ce qu'on lui demande ; c'est la grille en amont qui ne coïncide pas avec ce que
l'œil regroupe. Votre §3.3 (« la pastille ouverte disparaît », la trace) rend la
scène lisible malgré tout, mais autant savoir que ce cas existe avant de le
recevoir en bug.

**2. Deux activités plus proches que 43 m restent agrégées.** C'est voulu, et
c'est irréductible pour toute agrégation qui aggrège quelque chose. Un tap doit
donc toujours pouvoir ouvrir un groupe.

**3. Les clients déployés.** Votre correctif ne dépend de rien côté serveur ;
celui-ci met du temps à atteindre tout le parc. Vous ne voulez pas que la
résolution d'une pastille dépende de la version du backend en face.

En revanche, votre §3.1 devient nettement moins souvent sollicité : au palier 20,
une pastille devient rare. C'est le bénéfice.

## Votre demande n°2, en deux moitiés

Vous demandiez « que le cluster porte les coordonnées de ses membres (ou un
`GET /map/clusters/{clé}/members`) ».

- **Les identifiants sont déjà là.** `MapCluster.activityIds` est livré depuis le
  lot carte précédent, dédoublonné, dans l'ordre d'apparition. C'est ce qui rend
  votre §4 possible : sans cette liste, le second filtre n'aurait rien à quoi se
  raccrocher quand les bornes sont inutilisables.
- **Les coordonnées ne le sont pas.** Les ajouter reste faisable, mais avec la
  maille corrigée un cluster au palier 20 devient rare, donc l'aller-retour que
  cela supprimerait le devient aussi. Nous ne le faisons pas d'office : le gain
  ne justifie pas d'élargir un contrat de carte que vous parsez déjà. Dites-le si
  vous le voulez malgré tout — c'est un petit travail.

L'endpoint dédié, lui, n'a plus d'objet : il demanderait une clé de cluster
stable, que le serveur n'expose pas et qui n'aurait pas de sens (une cellule
n'existe qu'au palier demandé).

## Deux pièges sur la requête du §2, avant que vous ne les rencontriez

Votre spec dit « on interroge le serveur sur le petit disque de la pastille sans
paramètre `zoom` ». C'est le bon levier, et il fonctionne. Deux détails du
contrat que votre spec ne mentionne pas :

**`radiusMeters` exige `userLat` et `userLng`.** Sans eux, la réponse est un
`400 MAP_RADIUS_REQUIRES_USER_LOCATION`, pas une carte vide. Le rayon accepté va
de 1 m à 200 000 m.

**Et ces deux paramètres ont un effet de bord.** Ils servent aussi à calculer
`distanceKm` sur chaque marqueur et à **ordonner la réponse** (distance
croissante). Si vous y passez le barycentre de la pastille — ce qui est le geste
naturel — les `distanceKm` que vous recevrez seront des distances *au centre du
groupe*, pas à l'utilisateur. Inoffensif si vous ne les lisez pas ; trompeur si
une carte-membre les affiche. Votre `capExplodedMembers` du §5.1, en revanche,
en profite : la réponse arrive déjà triée par proximité au barycentre, vous
n'avez qu'à tronquer.

Par ailleurs, votre §3.4 a raison de compter les **marqueurs** et jamais
`activityIds` : un marqueur est un couple *(activité, lieu)*, et les créneaux y
sont regroupés sur des coordonnées arrondies à trois décimales (~111 m). Une
activité tenue en deux endroits produit donc deux marqueurs. La règle d'arrondi
exacte est documentée sur `MapActivityMarkerDto` — `Math.round` arrondit les
demis vers +∞, là où Dart les arrondit à l'opposé de zéro. Si vous dédupliquez
côté client, c'est le seul endroit où les deux plateformes divergent.

## Vérification

`MapServiceGridSizeTest`, six tests, sans Spring ni base. Ils portent sur
**l'invariant, pas sur les valeurs** : piquer les nouvelles mailles une à une
reconstruirait le même piège en décalé — une table juste en un point et fausse
ailleurs, sans qu'aucun test ne s'en aperçoive.

| Test | Ce qu'il verrouille |
| --- | --- |
| `auDessusDeLAncre_laCelluleDoitGarderLaMemeTailleEcran` | la propriété produit : une cellule occupe toujours la même fraction de l'écran. C'est le test qui aurait échoué sur l'ancienne table |
| `auDessusDeLAncre_laMailleDoitEtreDiviseeParDeuxAChaquePalier` | la même chose sur la maille — un palier, une moitié |
| `lAncreDoitResterALaValeurHistorique` | l'échelle absolue, que la pente seule ne détermine pas |
| `auPalierMaximal_laMailleDoitEtreDeLOrdreDeLaCinquantaineDeMetres` | votre demande, en ordre de grandeur assumé plutôt qu'en valeur |
| `enDessousDeLAncre_lesValeursHistoriquesDoiventEtreIntactes` | que les paliers bas n'ont pas bougé |
| `unZoomHorsBornes_neDoitPasProduireDeMailleAberrante` | le plafond interne |

Non-régression : `MapActivitiesBoundingIntegrationTest`, `MapUpcomingFilterIntegrationTest`
et `MapActivitiesErrorPathIntegrationTest` passent **sans modification**. Aucune
fixture n'a eu besoin d'être déplacée : les deux jeux sensibles à la maille
(0,539° et 0,4° d'écart) restent du bon côté de leurs cellules aux paliers
testés. L'invariant `somme(count) + activities.length == totalInBounds` tient à
tous les paliers vérifiés.

Un échec subsiste dans `MapActivitiesIntegrationTest`
(`shouldRequireAuthentication`), **antérieur à ce changement** et sans rapport
avec la carte : vérifié en rejouant le test sur `master` sans le correctif.

## Ce qui reste ouvert

Trois points, aucun bloquant, tous documentés dans le code plutôt que tus :

1. **Les paliers bas sont trop fins** — même dérive, autre bout de la courbe.
   Non traité parce qu'aucune demande ne le réclame et que c'est un changement
   visible sur la carte monde.
2. **Grille fixe, pas proximité** — le point 1 de la section « pourquoi livrer
   quand même ». Le corriger demande un vrai clustering par proximité, donc un
   autre travail.
3. **La maille s'applique en degrés aux deux axes**, sans correction en
   `cos(lat)`. Une cellule est donc un rectangle qui s'aplatit vers le nord :
   0,01° vaut 1 113 m × 733 m à Paris, et l'écart croît avec la latitude. Sans
   effet sur vous — les `bounds` d'un cluster portent l'étendue réelle de ses
   membres, jamais la cellule — mais à savoir si vous comparez un jour nos
   comptes à un regroupement calculé côté client.
