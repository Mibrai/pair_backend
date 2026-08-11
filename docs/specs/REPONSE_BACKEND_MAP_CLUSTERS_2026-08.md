# Réponse backend — le compte des clusters de la carte

> Réponse à `PROMPT_BACKEND_MAP_CLUSTERS_2026-08.md`. La demande est livrée, en
> **défaut**, avec une sortie de secours. Deux constats du code corrigent votre
> diagnostic sur un point et le confirment sur l'autre — ils ouvrent ce document
> parce que le premier change ce que vous devez chercher si l'écart persistait.

---

## Le filtre est en place, et c'est le défaut

`GET /api/map/activities` n'applique plus qu'**une seule définition**, avant
toute agrégation : une activité n'apparaît que si elle a **au moins une séance à
venir**. Marqueurs isolés et membres des clusters sont issus de la même liste
filtrée, donc :

- `count` d'une pastille est le nombre de marqueurs réellement affichables ;
- les **bornes** du cluster n'englobent plus les membres écartés — recadrer au
  tap ne peut plus amener sur une zone vide ;
- `totalInBounds` et le `truncated` qui en dépend décrivent la même population,
  donc l'invariant `somme(count) + activities.length == totalInBounds` tient
  après filtrage ;
- vos marqueurs isolés arrivent déjà triés : votre garde-fou client n'a plus
  rien à écarter, comme prévu.

Le filtre s'applique en un seul point du code, entre le tri et l'agrégation. Il
n'y a pas deux chemins à tenir cohérents : `activities` et `clusters` lisent la
même liste.

## `includeExpired=true` — la sortie de secours

Vous demandiez le défaut, et vous avez raison : un paramètre que vous poseriez
sur tous vos appels n'est qu'un défaut écrit deux fois. Nous l'avons donc fait.

Mais la route est **publique** (`permitAll`) : nous ne pouvons pas énumérer ses
consommateurs, seulement constater qu'à l'intérieur de ce dépôt elle n'a qu'un
appelant. Plutôt que de choisir entre « le bon défaut » et « ne casser
personne », la réponse porte les deux : `includeExpired=true` rétablit
exactement la population d'avant, dans les deux listes comme dans les
compteurs. Aucun consommateur inconnu n'est enfermé, et vous n'avez aucun
paramètre à poser.

Vous n'avez donc **rien à envoyer** : le défaut est déjà celui que vous vouliez.

## Correction — `programCount > 0` ne pouvait rien écarter ici

Votre tableau donne deux conditions. La première, « a un programme », est
**structurellement vraie sur cette route** : un marqueur naît d'un créneau
localisé rattaché à un programme, et `programCount` compte les programmes
distincts de ces créneaux — il vaut donc toujours au moins un. Votre filtre
client ne pouvait jamais l'utiliser pour écarter quoi que ce soit.

**L'écart 12 → 7 venait donc entièrement de l'expiration.** C'est utile si vous
le voyiez persister après notre correctif : il faudrait alors chercher ailleurs,
et le premier endroit à regarder serait la déduplication. Un marqueur est un
couple **(activité, lieu)** — une même activité tenue en deux endroits produit
deux marqueurs, donc `count = 2` pour une seule activité. C'est aussi la raison
pour laquelle `activityIds` peut être plus court que `count`, comme le contrat
le documente déjà.

Un test verrouille désormais cette invariance plutôt que de la laisser tacite :
si elle cessait d'être vraie, le filtre devrait gagner une condition.

## Ce sur quoi le filtre teste, et pourquoi

Il teste `nextSessionAt`, **le champ que la réponse expose déjà** — pas une
expiration recalculée côté serveur. C'est délibéré : le `count` d'un cluster
devient ainsi, par construction, « ce que le client aurait gardé ». Recalculer,
même mieux, aurait fait vivre une troisième définition de l'expiration —
exactement ce que votre demande cherche à supprimer.

**Une limite, héritée et non introduite par ce changement**, que vous devez
connaître : sur cette route, `nextSessionAt` se lit sur `starts_at` sans
dérouler la règle de récurrence. C'est un job (`RecurringSlotRolloverJob`) qui
maintient `starts_at` sur la prochaine occurrence réelle, en lisant la RRULE,
toutes les dix minutes. Il subsiste donc une fenêtre de dix minutes pendant
laquelle une activité récurrente vivante paraît expirée.

Ce n'est pas une régression : ces marqueurs **disparaissaient déjà de votre
carte**, puisque votre tri client les écartait sur le même critère. Ils
disparaissent désormais de la réponse. Si cette fenêtre vous gêne à l'usage,
dites-le : la corriger demande de dérouler la RRULE à la lecture, ce qui est un
autre travail — et qui bénéficierait aussi à `/slots/feed` et
`/activities/browse`, qui partagent la même dépendance au job.

## Les tests

`MapUpcomingFilterIntegrationTest`, sept tests, fixtures posées loin de toute
zone peuplée par les seeds et interrogées par bbox — les comptes sont exacts,
pas approximatifs :

| Test | Ce qu'il verrouille |
| --- | --- |
| `leCountDUnCluster_neDoitCompterQueLesActivitesAvecSeanceAVenir` | trois marqueurs dans la cellule dont un expiré ⇒ `count == 2`, et des bornes qui ne portent pas la trace de l'écartée |
| `sansAgregation_lActiviteExpireeDoitEtreAbsenteDesActivites` | le même jeu à un zoom qui n'agrège pas — c'est ce test qui prouve la définition unique |
| `sansZoom_lActiviteExpireeDoitEtreAbsente` | le troisième chemin (aucun paramètre `zoom`), même population |
| `sommeDesClustersEtDesActivites_doitEgalerTotalInBounds` | l'invariant, à cinq zooms, après filtrage |
| `zoneEntierementExpiree_doitRendreUneReponseVide` | réponse vide et `totalInBounds == 0`, jamais un cluster de `count: 0` |
| `includeExpired_doitRetablirLaPopulationComplete` | la sortie de secours, et le fait que `includeExpired=false` vaut le défaut |
| `toutMarqueurRenvoye_doitPorterAuMoinsUnProgramme` | l'invariance structurelle décrite plus haut |

Non-régression : `MapActivitiesBoundingIntegrationTest`, ses 26 tests aux comptes
et clusters exacts, passe sans modification. Aucune fixture existante n'était
datée dans le passé, et les données de seed sont toujours strictement futures.

## Signalé au passage, hors périmètre

`getAllActivitiesForMap` enveloppe tout son corps dans un `catch (Exception)`
qui renvoie **une carte vide centrée sur Paris, en 200**, avec une trace sur
`System.err` au lieu du logger. N'importe quelle panne de cette route s'affiche
donc chez vous comme une carte vide plutôt que comme une erreur — et sans
`X-Request-Id` exploitable dans nos journaux. Ce n'est pas le sujet de votre
demande, et nous ne l'avons pas touché ; mais si vous observez un jour une carte
vide inexplicable, c'est le premier endroit à regarder. Dites-nous si vous
voulez que nous le reprenions.

Par ailleurs, la couche `activities` de `GET /map/bounds` est une **définition
différente** : elle agrège les personnes qui déclarent une activité, sans notion
de programme ni de créneau. Elle n'est pas concernée par votre demande, mais si
un écran la consomme en parallèle de la carte, les deux ne montreront pas la
même chose.
