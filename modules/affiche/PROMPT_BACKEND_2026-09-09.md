# Trois colonnes sur `/attendances/mine` — sans quoi le module continue de mentir

**Écrit le 2026-09-09.**
Fait suite à : [`REPONSE_BACKEND_2026-09-09.md`](REPONSE_BACKEND_2026-09-09.md) ·
Notre réponse précédente : [`SUITE_CLIENT_2026-09-08.md`](SUITE_CLIENT_2026-09-08.md)

> **Votre déploiement est vérifié**, et pas sur parole : le contrat a changé de
> SHA, les trois routes sont rejouées. § 1.
>
> **`/attendances/mine` a fait exactement ce que vous en attendiez — et elle a
> révélé mieux que ce que nous cherchions.** Les trois affiches fausses que nous
> vous annoncions ne sont pas corrigées : elles ont **changé de phrase**. La
> sélection réfute la « première fois », descend d'un rang, et retombe sur un
> motif que votre route ne sait pas arbitrer. § 2.
>
> **La demande : trois colonnes.** `categoryName`, `cityLabel`, et
> l'identifiant de l'hôte, sur `ConfirmedAttendanceDto`. Pas quatre : nous avons
> vérifié que `placeName` ne nous sert pas. § 3.
>
> **Ce que nous ferons si vous ne pouvez pas** est écrit noir sur blanc, pour que
> vous connaissiez le prix de l'attente : nous retirerons de la sélection les
> cinq motifs invérifiables, et la galerie du compte de test passera de **quinze
> à dix affiches**. § 4.
>
> **Deux demandes secondaires**, nées de l'écriture de la bande. § 5.
>
> **Vos deux questions en attente** : retrait, pas coupure définitive ; et le
> numéro de build n'existe pas encore. § 6.

---

## 1. Votre déploiement, vérifié

Le contrat a bougé — c'est la première chose que nous regardons, et hier soir il
ne bougeait pas :

```
/v3/api-docs   avant : 5e8238adbdec38fe…      après : 0aa9fa021170cf19…
```

Relevé, puis rejoué avec le compte de test :

| | contrat | HTTP réel |
|---|---|---|
| `GET /api/attendances/mine` | présent | `200` en **1,20 s**, **42 entrées** |
| `AfficheDto` | `+ activityName, categoryColorRamp` | `200 []` (personne n'a publié) |
| `AfficheUpdateDto` | `+ displayName, avatarUrl` | `200 []` (idem) |

`ConfirmedAttendanceDto` porte exactement `scheduleId`, `slotStartedAt`,
`activityId`, `activityName`, `categoryColorRamp`. `slotStartedAt` recoupe bien
`SlotRecapDto.slotStartedAt` : la paire `(scheduleId, slotStartedAt)` identifie
la même séance des deux côtés, ce qui était la condition de tout le reste.

**Une remarque d'hier soir qui a son importance aujourd'hui** : nous avions
constaté que le contrat était resté rigoureusement identique alors que vous
annonciez trois lots livrés, et nous n'avions rien allumé. Nous ne l'écrivons
pas pour marquer un point — « livré » et « déployé » sont deux mots que nos deux
côtés emploieront encore. Nous relèverons le contrat à chaque fois, et nous vous
dirons ce que nous voyons.

---

## 2. Ce que votre route a révélé — le défaut n'est pas corrigé, il a descendu d'un rang

### 2.1 Le croisement

```
/recaps/mine        35 cartes
/attendances/mine   42 présences
                    7 présences sans carte · 0 carte sans présence
```

Les sept invisibles : Laufen 15/07, Bouldern 18/07, Laufen 23/07, Cinema 26/07,
Cinema 30/07, Programmation 01/08, Programmation 14/08. Votre réponse à B4 est
donc exacte au sens le plus littéral, et le chiffre lui donne son poids : **une
séance vécue sur six n'existait pas** pour ce module.

### 2.2 Le résultat, et il n'est pas celui que nous annoncions

Nous avons branché l'histoire complète, puis rejoué la galerie sur les données
réelles — les 42 présences croisées aux 35 cartes, pas une fixture :

```
DISPARUES                                 APPARUES
17/08 Laufen → premierePratique     →     17/08 Laufen → premiereCategorie
15/08 Cinema → premierePratique     →     15/08 Cinema → premiereCategorie
15/08 Cinema → premierePratique     →     15/08 Cinema → premiereCategorie
```

Quinze affiches avant, quinze après. Les trois « première fois » sont bien
réfutées — puis la sélection descend d'un rang et retombe sur `premiereCategorie`,
que votre route **ne peut pas arbitrer** faute de porter un nom de catégorie.

Or les séances cachées portent ces catégories-là :

```
« première catégorie : Laufsport » (17/08)  →  FAUSSE, déjà Laufsport le 15/07
« première catégorie : Cinema »    (15/08)  →  FAUSSE, déjà Cinema le 26/07
« première catégorie : Cinema »    (15/08)  →  FAUSSE, déjà Cinema le 26/07
```

**Le module dit toujours trois choses fausses sur l'histoire de cette personne.
Il les dit autrement.**

Ce n'est le défaut de personne : c'est la forme d'un module qui juge une
transition. Boucher un trou fait apparaître le suivant, et il n'y a pas d'ordre
d'arrivée qui sauve — la sélection est une liste de priorités, elle descend
jusqu'à ce que quelque chose accroche.

### 2.3 L'état exact des quinze affiches

| Motif | Combien | Arbitré ? |
|---|---:|---|
| `premierePratique` | 5 | ✅ `activityName` |
| `premiereSaison` | 1 | ✅ activité + date |
| `nouvelleAmbiance` | 3 | ✅ **exact par construction** — voir ci-dessous |
| `premiereCategorie` | 3 | ❌ **les trois sont fausses** |
| `premiereVille` | 1 | ❌ invérifiable |
| `premiereFoisHote` | 1 | ❌ invérifiable |

**Dix sûres, cinq qui reposent sur un champ que vous ne servez pas.** Et la
distinction qui compte n'est pas « fausse » contre « vraie » : c'est que rien,
dans l'app, ne distingue une affiche vérifiée d'une affiche invérifiable.

**Ce que nous ne vous demandons pas, et pourquoi.** `nouvelleAmbiance`,
`ambianceQuiRevient` et `premierSouvenir` sont **exacts sans aucun champ de
plus**, et c'est votre propre phrase qui les sauve : *« une carte naît d'une
contribution, jamais d'une présence »*. Donc une séance sans carte n'a porté ni
ambiance ni photo — l'absence est **prouvée**, pas ignorée. Nous n'avons pas
besoin de `topVibes` ni de `photoUrls` sur cette route.

---

## 3. La demande — trois colonnes sur `ConfirmedAttendanceDto`

```
GET /api/attendances/mine
→ [{ scheduleId, slotStartedAt, activityId, activityName, categoryColorRamp,
     categoryName, cityLabel, hostId }]
                 ^^^^^^^^^^^^^^^^^^^^^^^^^^^^ les trois demandés
```

| Champ | Ferme | Aujourd'hui |
|---|---|---|
| `categoryName` | `premiereCategorie`, `traversee` | **3 affiches fausses** sur le compte de test |
| `cityLabel` | `premiereVille` | 1 affiche invérifiable |
| `hostId` | `premiereFoisHote` | 1 affiche invérifiable, et c'est le motif de rang 1 le plus rare |

### Trois, pas quatre — nous avons vérifié avant de demander

`placeName` compléterait `retourAuLieu`. **Nous ne le demandons pas** :
`retourAuLieu` est sorti de la sélection le 08/09 (`AfficheMotif.selectionnable`
à `false`), et la garde de publication le refuse de toute façon — c'est le seul
motif dont le lieu **est** le sujet, donc le seul qu'on ne peut pas publier sans
publier un schéma de vie. Un champ demandé « au cas où » est un champ qu'on lit
un jour sans savoir pourquoi.

### Une précision de nommage, pour éviter un aller-retour

**`categoryColorRamp` ne remplace pas `categoryName`**, et nous ne nous en
servirons pas comme tel. Deux catégories distinctes peuvent partager une rampe,
et « Première fois en `orange-red` » n'est pas une phrase. C'est écrit dans notre
code à l'endroit exact où quelqu'un serait tenté de s'en servir.

`hostId` suffit : nous comparons des identifiants, jamais des noms. Ni
`hostName`, ni bloc de profil.

### Le coût, tel que nous l'imaginons — et vous seuls savez

Les trois valeurs vivent sur la chaîne que vous parcourez déjà pour servir
`activityName` (`Schedule → Program → UserActivity → Activity → Category`), plus
`Schedule`/`Program` pour la ville et l'auteur. Nous nous attendons à zéro
requête de plus. **Passez-la quand même à votre harnais de comptage**, sur
quarante entrées comme vous l'avez fait pour B7 : c'est une route sans
pagination, qui rend l'histoire entière, et un coût par ligne y serait payé au
démarrage de chaque session.

---

## 4. Ce que nous ferons si vous ne pouvez pas — pour que le prix soit connu

Nous ne resterons pas avec un module qui affirme des choses invérifiables. À
défaut de ces trois colonnes, nous retirerons de la sélection les **cinq motifs
que nous ne savons pas arbitrer** : `premiereCategorie`, `traversee`,
`premiereVille`, `premiereFoisHote` — et `retourAuLieu`, déjà sorti.

Le mécanisme existe déjà et il a déjà servi : `AfficheMotif.selectionnable` est
passé à `false` sur trois motifs le 08/09, parce que la mesure l'exigeait.

**Ce que cela coûte, mesuré** : sur le compte de test, la galerie passe de
**quinze à dix affiches**. Un tiers de ce que ce module produit disparaît, et
avec lui le motif de rang 1 le plus rare — « la première fois que tu as posé un
créneau toi-même ».

Nous préférons dix affiches vraies à quinze dont cinq sont des hypothèses. Mais
nous préférons quinze vraies, et c'est vous qui les avez entre les mains.

---

## 5. Deux demandes secondaires, nées de l'écriture de la bande

### 5.1 `GET /affiches/updates` — porter les champs d'affichage · **non bloquante**

La bande d'affiches du fil est écrite. Telle qu'elle est, drapeaux levés, elle
paie **un `GET /users/{id}/affiches` par visage** : `AfficheUpdateDto` dit qui a
publié, il ne dit pas quoi.

C'est borné par le nombre de gens qui ont publié — pas par la taille des
abonnements, donc ce n'est pas le N+1 que B6 existait pour éviter. Mais à 200 ms
l'aller-retour, dix visages coûtent deux secondes sur le chemin le plus chaud de
l'app.

**Ce qui l'annulerait** : que chaque ligne de `/affiches/updates` porte les
champs d'affichage de la **dernière affiche visible** de cette personne — motif,
`slotStartedAt`, `activityName`, `categoryColorRamp`. Une requête au lieu de
N+1, et c'est le même document qui les livre déjà à côté.

Nous ne la posons pas comme bloquante : la bande fonctionne sans, et vous venez
d'élargir ce DTO à notre demande. Si le coût vous paraît mauvais, dites-le et
nous vivrons avec les N appels.

### 5.2 `AfficheDto` — quatre motifs qu'on ne sait pas dessiner pour autrui · **non bloquante**

Avec `activityName` et `categoryColorRamp`, **sept motifs sur treize** se
dessinent pour quelqu'un d'autre. Les six autres réclament une variable que le
DTO ne porte pas, et comme trois d'entre eux sont hors sélection, **les motifs
réellement perdus sont quatre** :

| Motif | Manque |
|---|---|
| `premiereCategorie`, `traversee` | un **nom** de catégorie |
| `premiereVille` | `cityLabel` |
| `nouvelleAmbiance` | une ambiance dominante |

Concrètement : l'affiche d'un tiers portant l'un de ces motifs **n'entre pas dans
la bande** — nous ne fabriquons aucune variable manquante, et un visage qu'on ne
peut pas ouvrir promet ce qui n'existe pas.

`categoryName` et `cityLabel` sur `AfficheDto` en règleraient trois sur quatre.
C'est la même paire qu'au § 3, sur un autre DTO — si vous traitez les deux
ensemble, dites-le nous : nous rebrancherons les deux surfaces d'un coup.

---

## 6. Vos deux questions

### 6.1 `AFFICHE_READY` : retrait, pas coupure définitive

Notre phrase était ambiguë et vous avez eu raison de la relever. Elle parlait de
la **bannière repliée** — pour elle, votre texte est ce que nous voulons, hier
comme demain, et notre formateur rend `nil` de toute façon.

La **vue déployée** est un autre objet. Après notre correctif, elle affiche notre
carte de marque réduite au titre du serveur : un état conçu, pas le rendu par
défaut du système. C'est un petit gain, et il est réel.

Et une raison de fond, qui pèse plus : une exception permanente chez vous
mettrait une règle sur **notre** interface dans un second endroit, distant, que
personne chez nous ne relit. La garde vit maintenant dans nos deux extensions,
elle est testée, et c'est là qu'elle doit rester **seule**. Votre coupure est un
plâtre, et un bon plâtre s'enlève.

**Donc : retrait, au calendrier convenu.**

### 6.2 Le numéro de build : il n'existe pas encore

Le correctif est écrit et vérifié — les deux extensions, un prédicat partagé,
**30 tests Swift verts** dont 7 neufs. Il n'est **pas** en production : aucune
version ne l'embarque encore.

Nous vous enverrons le numéro. D'ici là, votre coupure est ce qui protège les
personnes qui ont l'app aujourd'hui, et le 15 octobre reste la borne.

---

## 7. Où en est le client

Allumés : la composition, la galerie, l'export image, les deux onglets de « Mes
moments », la bande du fil.

**Éteints, et volontairement, malgré votre « Allumez »** : `affichePublication`
et `afficheAnneau`. Le code est écrit, branché sur vos quatre champs neufs, et
testé sur les deux branches. Ce qui nous retient n'est pas votre livraison, c'est
le § 2 : montrer une affiche douteuse en privé est gênant, laisser quelqu'un la
**publier** sur son profil lui fait signer une phrase fausse sur sa propre
pratique. Nous allumerons quand les trois colonnes seront là — ou quand nous
aurons appliqué le repli du § 4.

Suite Flutter : **3 619 tests verts.** Cible Swift : **30 verts.**

---

## Récapitulatif

| # | Demande | Bloque |
|---|---|---|
| **B13** | `categoryName`, `cityLabel`, `hostId` sur `ConfirmedAttendanceDto` | `affichePublication` — trois affiches fausses aujourd'hui |
| **B14** | Les champs d'affichage de la dernière affiche sur `/affiches/updates` | rien — évite N+1 requêtes sur la bande |
| **B15** | `categoryName`, `cityLabel` sur `AfficheDto` | rien — rend 3 motifs de plus dessinables pour autrui |
| — | `AFFICHE_READY` | **retrait** de la coupure, au calendrier convenu |
