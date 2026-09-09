# Réponse — les trois colonnes sont là, et « la dernière affiche » ne veut pas dire ce qu'on croit

**Date :** 2026-09-09 ·
Répond à [`PROMPT_BACKEND_2026-09-09.md`](PROMPT_BACKEND_2026-09-09.md) ·
Livraison précédente : [`REPONSE_BACKEND_2026-09-09.md`](REPONSE_BACKEND_2026-09-09.md)

> **B13 est livré, et c'est le seul qui vous bloquait.** `categoryName`,
> `cityLabel` et `hostId` sur `ConfirmedAttendanceDto`. Trois, pas quatre :
> `placeName` n'y est pas, et votre § 3 nous a évité de l'ajouter « au cas où ».
> Une requête pour quarante présences, repassée au harnais comme vous le
> demandiez. § 1.
>
> **B14 et B15 sont livrés aussi**, bien que non bloquants. Vous n'avez plus de
> `GET /users/{id}/affiches` par visage, et trois motifs de plus se dessinent
> pour autrui. § 2 et § 3.
>
> **Une chose que vous n'aviez pas demandée et qu'il faut que vous sachiez** :
> « la dernière affiche » de `/affiches/updates` veut dire **la dernière que ce
> lecteur a le droit de voir**, et pas la dernière tout court. Le décor qui
> distingue les deux est écrit et testé. § 2.2.
>
> **Une ligne que nous avons franchie sciemment** : `cityLabel` sur `AfficheDto`
> rend une ville à des tiers, et la javadoc de ce DTO promettait jusqu'ici de ne
> rien porter du lieu. Nous l'avons franchie parce que votre § 5.2 la justifie,
> et nous avons posé le garde-fou qui tient le **lieu** dehors. § 3.
>
> **Vous pouvez rallumer `affichePublication` et `afficheAnneau`.** § 5.
>
> **`AFFICHE_READY`** : rien à ajouter, le retrait est déjà au calendrier
> convenu. § 6.

---

## 1. B13 — les trois colonnes, sur la chaîne que la route parcourait déjà

```
GET /api/attendances/mine
→ [{ scheduleId, slotStartedAt, activityId, activityName, categoryColorRamp,
     categoryName, cityLabel, hostId }]
```

| Champ | Source | Nul quand |
|---|---|---|
| `categoryName` | `categories.name` | jamais — la colonne est `NOT NULL` |
| `cityLabel` | `schedules.city` | la ville n'est pas renseignée |
| `hostId` | l'auteur du programme | jamais |

`hostId` est l'**auteur du programme dont relève le créneau** — c'est déjà la
définition de l'hôte partout ailleurs dans le dépôt, y compris pour le mot
d'hôte et la garde de publication des cartes-souvenirs. Vous y verrez votre
propre identifiant sur les séances que vous avez organisées : on est l'hôte de
ce qu'on organise, et présent comme les autres. C'est exactement le signal dont
`premiereFoisHote` a besoin, et il n'est lisible nulle part ailleurs dans cette
liste — une séance qu'on organise et une séance où l'on va y sont deux lignes
identiques, à l'hôte près.

### Le coût, mesuré comme vous le demandiez

Votre estimation était juste, et nous l'avons quand même repassée au harnais de
comptage — sur quarante entrées, comme pour B7 :

```
GET /api/attendances/mine : 1 requête(s), 40 présence(s)
```

**Une requête, huit colonnes.** La catégorie était déjà jointe pour la rampe, la
ville vit sur le créneau qui est la première jointure de la chaîne. Seul l'hôte
est une jointure neuve — une jointure de plus dans la *même* requête, jamais un
aller-retour de plus. Le garde-fou est un nombre exact et non une borne : une
requête de plus devrait être relue, pas tolérée.

### `placeName` n'y est pas

Vous l'avez retiré de votre demande avant que nous ayons à en discuter, et la
raison que vous donnez est la bonne — c'est le seul motif dont le lieu **est** le
sujet. Nous l'écrivons ici parce que c'est le genre de champ qu'on rajoute six
mois plus tard sans se souvenir qu'il avait été écarté : la javadoc du DTO porte
maintenant la phrase « demandé puis retiré, et pourquoi », à l'endroit exact où
quelqu'un serait tenté de l'ajouter.

### Ce que le test éprouve, au-delà de la forme

Le défaut que vous avez mesuré est reproduit tel quel : **deux activités
distinctes sous une même catégorie**, ce qui est la forme exacte de vos trois
affiches fausses. Les deux entrées portent des `activityName` différents et le
même `categoryName` — donc la première pratique reste vraie, et la première
catégorie devient **réfutable**. C'est ce qu'il vous manquait, et c'est
maintenant gardé par un test qui échouerait si la colonne disparaissait.

Trois autres cas sont couverts : la séance qu'on organise soi-même se reconnaît
à `hostId`, une ville non renseignée reste **nulle** — elle n'est jamais devinée
à partir des coordonnées, alors que le créneau en porte —, et `categoryName` ne
vaut jamais `categoryColorRamp`.

---

## 2. B14 — `/affiches/updates` porte les champs d'affichage

```
→ [{ userId, latestPublishedAt, displayName, avatarUrl,
     motif, slotStartedAt, activityName, categoryColorRamp }]
```

Votre `GET /users/{id}/affiches` par visage n'a plus lieu d'être. Toujours **une
requête**, mesurée sur deux auteurs de quarante affiches chacun.

### 2.1 Pourquoi ce lot a coûté plus cher que les deux autres

Les deux autres projettent une colonne de plus. Celui-ci a changé la **question**
posée à la base.

L'ancienne requête agrégeait : `GROUP BY user_id` et `MAX(published_at)`. Une
agrégation rend une **valeur**, et une date suffisait à baguer un avatar. Une
bande d'affiches demande une **ligne** — le motif, l'activité, la séance — et il
n'existe aucune façon honnête de la tirer d'un `GROUP BY` : un `MAX(motif)`
aurait rendu le motif alphabétiquement dernier, d'une affiche que la date ne
désigne pas. Le visage aurait annoncé le motif d'une publication et la date d'une
autre, et rien ne l'aurait signalé.

C'est donc un `DISTINCT ON (user_id)` ordonné par `published_at DESC` qui garde
une ligne entière par personne. L'index qui sert ce tri existait déjà : **aucune
migration** n'accompagne ce changement.

Un détail qui n'en est pas un : le tri se termine par l'identifiant de
l'affiche. Deux publications dans la même milliseconde — republier en ouvrant
l'audience sur deux séances — laisseraient sinon Postgres libre de choisir, et le
visage changerait de motif d'un appel à l'autre sans que rien n'ait été publié.

### 2.2 « La dernière » veut dire « la dernière que ce lecteur a le droit de voir »

**C'est le point que vous n'aviez pas demandé, et celui sur lequel nous voulons
être explicites.**

Le cas : quelqu'un publie une affiche ouverte à tous il y a quinze jours, puis
une affiche **réservée à ses abonnés** hier. Un tiers non abonné ouvre son fil.

Ce qu'il reçoit : cette personne **figure** dans la bande — elle a bien publié
quelque chose pour lui — avec le motif et la séance de l'**ancienne**, la
publique. Le motif de l'affiche réservée ne sort pas.

Ce n'est pas un réglage, c'est l'ordre des opérations : le filtre d'audience est
dans le `WHERE`, donc il s'applique **avant** la réduction à une ligne par
personne. L'affiche réservée n'entre jamais dans l'ensemble que le `DISTINCT ON`
départage. L'écriture inverse — réduire d'abord, filtrer ensuite — aurait fait
**disparaître la personne** de la bande, ce qui est un autre défaut et non une
protection.

Nous le disons parce que cela change ce que vous dessinez : `latestPublishedAt`
peut être plus ancien que la dernière publication réelle de cette personne, et
c'est correct. Le test qui garde ce décor porte ce nom-là.

### 2.3 Ce que cela n'ouvre pas

Ces quatre champs sont ceux d'`AfficheDto` qu'un tap sur ce visage vous rendrait
à l'instant d'après, sous exactement le même filtre. La bande n'ouvre pas une
porte, elle évite d'y frapper deux fois. Le test qui vérifie qu'un tiers sans
droit ne reçoit ni l'anneau ni le nom a désormais son jumeau pour les champs
d'affichage — c'est là qu'un élargissement de DTO fuit, quand la garde n'a été
écrite que pour les champs d'hier.

---

## 3. B15 — `categoryName` et `cityLabel` sur `AfficheDto`

Vos trois motifs sur quatre sont réglés. `nouvelleAmbiance` reste hors de portée,
et vous aviez déjà dit pourquoi vous ne le demandiez pas.

Zéro requête de plus : la chaîne est ramenée par le même `LEFT JOIN FETCH` qui
sert déjà `activityName`, et la ville vit sur le créneau. Le comptage est
inchangé — une requête pour quarante affiches chez soi, quatre pour la galerie
d'un tiers, dont trois pour résoudre l'audience une fois et non par affiche.

### La ligne que nous avons franchie, et le garde-fou qui la remplace

Il faut le dire clairement : **la javadoc d'`AfficheDto` promettait jusqu'ici
« ni le titre du programme, ni le nom du lieu, ni la moindre photo », et elle le
promettait au présent.** `cityLabel` rend une ville à des tiers. Ce n'est pas
`placeName`, mais ce n'est pas rien non plus.

Nous l'avons fait, pour trois raisons, et nous les écrivons pour que la décision
soit relisible :

1. **c'est votre § 5.2 qui la justifie** — une affiche qu'on ne peut pas dessiner
   n'entre pas dans la bande, et un visage qu'on ne peut pas ouvrir promet ce qui
   n'existe pas ;
2. **la ville n'est pas le lieu** — un nom de salle répété sur une série
   d'affiches publiques dessine un emploi du temps, et c'est précisément pourquoi
   le motif dont le lieu est le sujet est sorti de votre sélection. Une ville dit
   où l'on vit, ce que dit déjà toute la surface publique de l'application ;
3. **la carte-souvenir la rend depuis toujours** à des gens qui n'étaient pas là,
   sous le même nom et depuis la même colonne.

Le garde-fou : un test lit le **corps JSON brut** de la galerie et vérifie que ni
`placeName`, ni le nom de la salle, ni l'adresse publique n'y figurent. C'est la
seule façon de prouver qu'un champ n'est **pas** là, et la seule qui tienne si
quelqu'un l'ajoute demain sans y penser. La phrase de la javadoc a été réécrite
pour rester vraie : elle dit maintenant ce qui est rendu, ce qui ne l'est pas, et
pourquoi la ville est du bon côté.

---

## 4. Ce que nous n'avons pas eu besoin de vous demander

Rien. Votre § 3 nommait les champs, leur type, et ce que vous n'en feriez pas —
`hostId` comparé et jamais affiché, `categoryColorRamp` qui ne remplace pas
`categoryName`. Ces deux phrases sont maintenant dans notre code, à l'endroit où
quelqu'un serait tenté de faire l'inverse.

---

## 5. Vous pouvez rallumer

`affichePublication` et `afficheAnneau` n'attendent plus rien de nous. Les cinq
motifs que vous ne saviez pas arbitrer le sont :

| Motif | Ce qui l'arbitre maintenant |
|---|---|
| `premiereCategorie` | `categoryName` |
| `traversee` | `categoryName` |
| `premiereVille` | `cityLabel` |
| `premiereFoisHote` | `hostId` |
| `retourAuLieu` | — reste hors sélection, et nous n'avons rien livré pour lui |

Le repli du § 4 de votre document — quinze affiches ramenées à dix — n'a pas lieu
d'être. Si vous préférez le passer quand même le temps de valider sur votre
compte de test, cela ne nous coûte rien : ces colonnes ne s'éteignent pas.

---

## 6. `AFFICHE_READY`

Rien à ajouter. Votre § 6.1 tranche dans le sens que nous avions proposé —
retrait, pas coupure définitive — et la raison de fond que vous donnez est
meilleure que la nôtre : une règle sur votre interface, posée dans un second
endroit distant que personne chez vous ne relit, finit par diverger. Le retrait
reste au calendrier convenu, et le 15 octobre reste la borne. Nous attendons
votre numéro de build ; d'ici là la coupure protège les personnes qui ont l'app
aujourd'hui, et c'est ce pour quoi elle a été posée.

---

## 7. Vérification

Le contrat a changé de SHA — relevez-le, comme vous le faites maintenant à chaque
fois. Les trois DTO portent les champs annoncés et rien de plus.

**Tests** : la suite entière est verte — **1 268 tests, 155 classes, 0 échec**,
en 9 min 24 d'un seul bloc. Nous ne concluons plus un lot sur les seules classes
qu'il touche : deux régressions passées n'avaient été vues que par des classes
sans rapport apparent avec le chantier. Les classes du lot :

| Classe | Ce qu'elle garde |
|---|---|
| `AttendanceMineIntegrationTest` | les trois colonnes, dont deux activités sous une même catégorie |
| `AfficheIntegrationTest` | la chaîne comparée à la carte-souvenir, le lieu tenu dehors, la dernière affiche **visible** |
| `AfficheServiceTest` | les quatre champs d'affichage, et les deux horodatages qui ne disent pas la même chose |
| `AfficheQueryCountIntegrationTest` | une requête par route, sur quarante |

Le harnais imprime son relevé même quand il passe — c'est lui qu'on relit :

```
GET /api/attendances/mine : 1 requête(s), 40 présence(s)
GET /api/affiches/updates : 1 requête(s)   ← deux auteurs de 40 affiches, réduits à une ligne chacun
GET /api/users/{id}/affiches — chez soi     : 1 affiche : 1 requête | 40 affiches : 1 requête
GET /api/users/{id}/affiches — vu par un tiers : 1 affiche : 4 requêtes | 40 affiches : 4 requêtes
```

---

## Récapitulatif

| # | Demande | État |
|---|---|---|
| **B13** | `categoryName`, `cityLabel`, `hostId` sur `ConfirmedAttendanceDto` | **livré** — 1 requête, 40 entrées |
| **B14** | Les champs d'affichage de la dernière affiche sur `/affiches/updates` | **livré** — 1 requête, et « dernière » = dernière visible (§ 2.2) |
| **B15** | `categoryName`, `cityLabel` sur `AfficheDto` | **livré** — 0 requête de plus |
| — | `placeName` | **non livré, et volontairement** — vous l'aviez retiré |
| — | `AFFICHE_READY` | retrait au calendrier convenu, inchangé |
