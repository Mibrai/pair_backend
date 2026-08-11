# Prompt à coller dans le Claude Code du dépôt backend

> **Une seule demande, courte** : que `GET /map/activities` applique aux
> **clusters** le filtre qu'il applique déjà, de fait, aux marqueurs isolés.
> Aucune régression à réparer, aucun incident derrière — c'est un compte affiché
> qui ment, et il ne peut se corriger que chez vous.

---

## Le défaut, en une ligne

Sur la carte, une pastille annonce « **12** » ; on zoome, le groupe se défait, et
il ne reste que **7** pins. Rien n'a changé entre les deux : le client a
simplement pu appliquer, sur les marqueurs dépliés, un tri qu'il n'a aucun moyen
d'appliquer à l'agrégat.

## Ce que le client filtre, et pourquoi il ne peut pas filtrer les clusters

Règle produit : **la carte ne montre que les activités qui ont un programme avec
au moins une séance à venir.** Deux conditions, tenues aujourd'hui côté client
sur les seuls `activities` de la réponse :

| Condition | Champ lu | Sur `MapActivityMarkerDto` | Sur le cluster |
| --- | --- | --- | --- |
| a un programme | `programCount > 0` | ✅ présent | ❌ absent |
| a une séance à venir | `scheduleCount > 0 && nextSessionAt == null` ⇒ expirée | ✅ présents | ❌ absents |

Le cluster ne porte que `count`, `latitude`/`longitude`, les bornes, un
`categoryIcon` dominant et `activityIds`. **Aucun des trois champs nécessaires.**
Le client ne peut donc ni recompter, ni écarter : il affiche `count` tel quel.

Les deux listes étant disjointes (`somme(count) + activities.length ==
totalInBounds`), le résultat est un écran à deux régimes — les marqueurs isolés
sont triés, les groupes ne le sont pas. C'est la même carte, avec deux règles.

## Pourquoi les trois contournements clients sont mauvais

Nous les avons pesés avant d'écrire ce document :

1. **Masquer les pastilles.** Une carte dézoomée deviendrait vide là où vous
   agrégez, c'est-à-dire exactement là où il y a le plus à voir.
2. **Afficher la pastille sans son nombre.** On cesserait de mentir, on
   cesserait aussi d'informer — et le nombre est la seule chose qu'une pastille
   apporte.
3. **Déplier via `activityIds` et recompter.** Le contrat prévient que cette
   liste peut être **plus courte que `count`** (un marqueur est un couple
   (activité, lieu), une activité tenue en deux lieux compte deux fois dans
   `count` et une fois ici), qu'elle est vide sur un serveur antérieur au lot 7,
   et qu'elle ne porte pas les champs à tester. Il faudrait un `GET` de
   rattrapage par cluster pour obtenir `programCount` et `nextSessionAt` — soit
   des dizaines de requêtes pour reconstituer un tri que la base fait en une
   clause `WHERE`.

L'information n'existe qu'à l'endroit où l'agrégat se forme. C'est la seule
raison pour laquelle cette demande vous revient.

## Ta mission

**Applique le filtre *avant* d'agréger**, sur `GET /map/activities` :

1. Ne retenir, parmi les marqueurs candidats, que ceux dont l'activité a **au
   moins un programme** et **au moins une occurrence future** — la même
   définition que celle de la lecture `nextSessionAt` (lot 5, RFC 5545 : « expiré
   uniquement si l'entrée est datée et qu'aucune occurrence future n'existe »).
2. Former les clusters **sur ce sous-ensemble**. `count` devient alors le nombre
   de marqueurs réellement affichables, et la pastille dit la vérité.
3. Faire suivre les compteurs de la réponse : `totalInBounds` et le `truncated`
   qui en dépend doivent décrire la **même** population, sans quoi l'invariant
   `somme(count) + activities.length == totalInBounds` cesse de tenir et le
   bandeau « vous ne voyez pas tout » se déclenchera à tort.
4. Écarter symétriquement les marqueurs isolés : le client les filtre déjà, mais
   les laisser passer garderait deux définitions vivantes — et c'est la seconde
   qui finirait par diverger. Le tri client restera en place comme garde-fou de
   compatibilité, il ne doit simplement plus rien avoir à écarter.

## La question qui décide de la forme

**Ce filtre doit-il être le défaut, ou un paramètre ?**

Notre besoin est qu'il soit le **défaut** : aucun écran de l'app ne veut voir une
activité sans programme ou sans séance à venir, et un paramètre que nous
poserions systématiquement n'est qu'un défaut écrit deux fois.

Mais c'est un **changement de comportement observable** pour tout autre
consommateur de cette route. Si l'un d'eux dépend de la population actuelle,
dites-le : nous prendrons un paramètre explicite (`onlyUpcoming=true`, ou le nom
que vous retiendrez) et nous l'enverrons sur tous nos appels. Ce que nous ne
voulons pas, c'est deux régimes sur la même carte.

## Les tests qui manqueraient

- une zone contenant une activité **sans programme** et une activité **expirée**,
  toutes deux dans la même cellule d'agrégation : le `count` du cluster ne doit
  compter ni l'une ni l'autre ;
- le même jeu de données, interrogé à un zoom qui **n'agrège pas** : les deux
  activités doivent être absentes d'`activities` — c'est ce qui prouve que les
  deux chemins partagent une seule définition ;
- l'invariant `somme(count) + activities.length == totalInBounds` sur une réponse
  non tronquée, après filtrage ;
- une zone dont **toutes** les activités sont écartées : réponse vide et
  `totalInBounds == 0`, pas un cluster de `count: 0`.

## Ce que le client fait déjà, pour éviter les doublons de travail

- `programCount > 0` est appliqué à la réception (`map_page.dart`), et
  l'expiration est lue sur le marqueur lui-même (`domain/activity_expiry.dart`,
  qui n'implémente rien d'autre que votre contrat du lot 5).
- Les clusters sont affichés **tels quels**, faute de pouvoir en juger — le code
  le documente comme un choix par défaut assumé, pas comme un oubli.
- Le tap sur une pastille recadre de deux paliers de zoom pour la faire éclater :
  c'est ce qui rend l'écart visible à l'utilisateur, et donc ce qui a motivé ce
  document.
