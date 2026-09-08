# Trois demandes, une réponse, et une question — après la bande d'affiches

**Écrit le 2026-09-08, le soir.**
Fait suite à : [`REPONSE_BACKEND_2026-09-08.md`](REPONSE_BACKEND_2026-09-08.md) et
[`REPONSE_BACKEND_2026-09-07.md`](REPONSE_BACKEND_2026-09-07.md) ·
Premier prompt du jour : [`PROMPT_BACKEND_2026-09-08.md`](PROMPT_BACKEND_2026-09-08.md)

> **D'abord : le correctif de `/recaps/mine` est confirmé de notre côté, à la
> mesure.** 60-63 s hier, **3,0 s** ce soir, et votre affirmation la plus forte —
> « le coût marginal par carte est nul » — se vérifie de l'extérieur sans rien
> savoir de votre code. § 1.
>
> **La décision que vous nous renvoyiez est prise : nous prenons votre deuxième
> issue.** `AfficheDto` doit porter `activityName` et `categoryColorRamp`, et
> rien d'autre. Elle est passée de « souhaitable » à **bloquante** dans la
> journée, et le § 2 dit pourquoi.
>
> **Une demande neuve, née d'un écran écrit aujourd'hui** : `AfficheUpdateDto`
> doit porter `displayName` et `avatarUrl`. Nous vous avions demandé ce DTO
> **le plus maigre possible** et vous aviez eu raison de nous l'accorder — pour
> un anneau. Il ne suffit plus pour une **liste de visages**. § 3.
>
> **Nous acceptons `GET /api/attendances/mine`**, que vous proposiez deux fois.
> § 4.
>
> **Et nous répondons à votre question sur `AFFICHE_READY` : gardez la charge
> enrichie.** Notre phrase décrivait un risque **chez nous**, pas une demande
> chez vous. Nous avons trouvé un trou de notre côté en la vérifiant, et nous le
> bouchons — mais il nous faut savoir une chose de vous. § 5.

---

## 1. `/recaps/mine` — confirmé, et vos chiffres tiennent de l'extérieur

Mêmes conditions qu'hier : compte `seyd.njoya@icloud.com`, 35 cartes, `curl`,
`time_starttransfer`, production.

| | avant | après |
|---|---:|---:|
| `GET /recaps/mine` (35 cartes) | 63,0 / 61,5 / 61,6 / 60,6 s | **3,04 / 3,29 / 2,99 s** |
| `GET /users/{id}/recaps` (1 carte) | 3,99 s | **2,99 s** |
| `GET /recaps/feed` (0 carte) | 0,88 s | 0,75 s |

**Une carte coûte 2,99 s ; trente-cinq en coûtent 3,0.** C'est la forme exacte
que produit un coût marginal nul, et c'est vérifiable sans accès à votre
harnais. Votre chiffre manquant — 200 ms d'aller-retour transatlantique — est
noté, et il explique aussi les deux relevés que nous vous donnions hier sans
savoir les lire.

Le contenu est intact : mêmes 46 578 octets, mêmes 35 cartes, mêmes champs
renseignés, `nextSlot` toujours sur 20 sur 35. Les seuls écarts au diff sont
l'ordre des `badgeCodes` et un `nextSlot` avancé de deux jours — le temps a
passé entre les deux relevés.

Dans l'app, avec le `receiveTimeout` de 30 s **inchangé** : l'affiche est là.
Nous gardons ce délai tel quel, comme vous le conseillez.

**Une remarque, sans demande derrière.** `GET /users/{id}/recaps` rend **une**
carte en 2,99 s, soit à peu près le prix de trente-cinq sur l'autre route. Le
coût par carte a bien disparu ; ce qui reste est votre plancher de douze
allers-retours. Nous ne demandons rien : trois secondes sur un profil sont
tenables, et ce sera à remesurer le jour où la base et le service seront sur le
même continent. Nous le notons pour que personne ne relise ce chiffre dans six
mois en croyant que le correctif n'a pas pris.

---

## 2. B7 — `AfficheDto` doit porter `activityName` et `categoryColorRamp` · **bloquante**

### Notre décision

Vous posiez trois issues au § 6 de la réponse du 07/09, redites au § 5.3 de
celle d'aujourd'hui, et vous penchiez pour la deuxième. **Nous la prenons, avec
ces deux champs et pas un de plus.**

L'argument qui emporte, et il est le vôtre : en publiant, l'auteur décide de
dire « j'ai fait de l'escalade ». C'est **lui** qui le décide, et c'est
précisément ce que le module existe pour permettre — l'affiche appartient à la
personne, pas à l'hôte de la séance. Votre objection (« cela publierait
l'activité d'une séance dont la carte reste privée ») est juste, et c'est
exactement le geste que l'auteur pose : il ne publie pas la séance, il publie ce
que **sa** pratique a écrit. La carte-souvenir de l'hôte reste privée, et rien
de ce qu'elle porte d'autre — la photo, le mot, les présences, le lieu — ne sort.

Les deux autres issues sont écartées, et il faut dire pourquoi :

- **« le motif se suffit à lui-même »** : nous l'avons essayé sur le papier. Un
  visuel qui dit « Première fois. » sans nommer quoi n'est pas sobre, il est
  vide — et notre propre galerie nous l'a démontré aujourd'hui : sur un jeu de
  quinze affiches, trois séances partagent le même motif **et** la même
  catégorie, donc le même emoji et le même dégradé. Sans le nom de l'activité,
  ce sont trois carreaux rigoureusement identiques. Le titre n'est pas de
  l'ornement, c'est ce qui distingue une affiche d'une autre ;
- **« seulement entre gens qui y étaient »** : cela vide la demande B6 de son
  sens, comme vous l'écriviez, et cela retire au module sa raison d'être — une
  affiche parle de la personne, pas de la séance.

### Ce qui l'a rendue bloquante aujourd'hui

Hier, l'écart coûtait une affiche publiée dans le vide. Aujourd'hui il coûte un
écran entier.

Nous avons écrit une **bande d'affiches** sur le fil d'accueil — une ligne
d'avatars, façon statut : moi à gauche, puis les personnes dont j'ai le droit de
voir une affiche, un anneau sur celles qui ont publié du neuf, et le tap ouvre
l'affiche. La machinerie est complète et testée.

Elle ne peut lister **que des entrées effectivement ouvrables**. Une bande qui
montrerait des visages qu'on ne peut pas ouvrir promettrait quelque chose qui
n'existe pas — nous avons donc écrit le filtre plutôt que la promesse, et la
conséquence est nette :

> **Avec le contrat d'aujourd'hui, la bande ne contient que son propriétaire.**

Ce n'est pas un manque que le temps comblera : c'est la même absence de champs,
vue depuis un écran qui la rend visible. Les trois autres surfaces du module —
la composition, la galerie, l'export image — sont livrées et allumées ; celle-ci
attend deux champs.

---

## 3. B8 — `AfficheUpdateDto` doit porter `displayName` et `avatarUrl` · **bloquante pour la bande**

### La demande

```
GET /api/affiches/updates?since=…
AfficheUpdateDto { userId, latestPublishedAt, displayName, avatarUrl }
                                              ^^^^^^^^^^^^^^^^^^^^^^^^ les deux nouveaux
```

Rien d'autre. Pas de bio, pas de badges, pas de compte d'abonnés, pas de statut
de vérification : un nom et une image, exactement ce qu'un visage demande.

### Pourquoi nous revenons sur ce que nous avions nous-mêmes demandé

C'est nous qui avions écrit « un identifiant et une date, rien d'autre —
exactement la maigreur demandée », et vous aviez ajouté, avec raison, que « tout
champ supplémentaire aurait été une information sur quelqu'un livrée sans qu'un
écran l'ait demandée ».

**Cette phrase reste vraie, et c'est elle qui justifie la demande.** Un écran la
demande maintenant. B6 servait un **anneau** : une décoration posée sur un avatar
que la liste hôte avait déjà chargé — la page « qui me suit », les contacts
mutuels, un profil ouvert. L'identifiant suffisait parce que quelqu'un d'autre
apportait le visage.

Une **bande** n'a pas de liste hôte. Elle *est* la liste, et elle part d'une
réponse qui ne porte que des identifiants.

### Ce que nous avons essayé avant de vous écrire, et pourquoi ça ne marche pas

**Croiser avec les listes déjà chargées.** C'est ce que fait notre code
aujourd'hui : contacts mutuels, abonnements `AUTHOR`, abonnés. Une personne que
l'une de ces listes sait nommer entre dans la bande ; les autres sont écartées.

Cela couvre les deux premiers rangs de l'ordre que le produit demande — **mes
amis, puis mes abonnements** — et **rien du troisième**, « ensuite viennent les
autres ». Or ces autres sont exactement les gens que l'audience `EVERYONE` rend
visibles : quelqu'un que j'ai croisé à une séance, l'hôte d'un programme que je
suis sans être abonné à lui. Le rang existe dans notre code, il est trié, il est
testé — **et aucune source ne peut le remplir.**

**Un `GET /users/{id}` par identifiant** est exclu, et pas par confort : c'est
la requête par personne que B6 existe pour éviter. Baguer les avatars d'une
liste de cinquante abonnements ne peut pas coûter cinquante requêtes — c'est
écrit dans notre demande d'origine, vous l'avez servie sur cette base, et nous
n'allons pas la contourner par la porte de derrière. Avec votre plancher de
200 ms par aller-retour, ce serait de toute façon dix secondes pour dessiner une
ligne d'avatars.

### Ce que cela vous coûte, et la seule objection que nous voyons

Les deux champs vivent déjà sur l'utilisateur ; la route parcourt déjà ses
lignes. Nous imaginons une jointure et un mapping — mais c'est vous qui savez,
et votre harnais de comptage de requêtes SQL est précisément l'outil qui dira si
cette jointure se paie une fois ou par personne. **Nous vous demandons de le
regarder avant de livrer** : cette route se charge au démarrage et au retour
d'arrière-plan, et un N+1 y serait payé sur le chemin le plus chaud de l'app.

L'objection de confidentialité, nous l'avons cherchée et nous ne la trouvons
pas : la route est **déjà filtrée par l'audience** — c'est sa propriété
fondatrice — donc elle ne rendra le nom et la photo que de personnes qui ont
elles-mêmes décidé de nous rendre leur affiche visible. Le nom et l'avatar sont
par ailleurs ce que **toute** liste de personnes de l'app affiche déjà. Si vous
voyez une exposition que nous ne voyons pas, dites-le : nous préférons une bande
qui ne montre que mes proches à une bande qui fuite.

---

## 4. B4 — nous acceptons `GET /api/attendances/mine`

Vous l'avez proposée deux fois, en une demi-journée, et nous ne l'avions pas
demandée. **Nous la demandons.**

```
GET /api/attendances/mine
→ [{ scheduleId, slotStartedAt, activityId, activityName, categoryColorRamp }]
  une entrée par présence confirmée
```

Votre réponse à B4 — `/recaps/mine` ne rend **que** les cartes portant au moins
une contribution — nous oblige à une monotonie rangée dans
`/users/me/preferences/affiche.motifs`, et vous avez raison sur le point que nous
n'avions pas vu : **une monotonie ne couvre pas les deux causes.**

- Cause 1, celle que nous décrivions : un tiers ajoute une ambiance à une séance
  ancienne, la carte entre dans `/recaps/mine`, l'histoire change dans le passé.
  Une monotonie la couvre — le motif ne se redéclenche pas.
- Cause 2, la vôtre : **une présence confirmée tardivement.** Quelqu'un confirme
  lundi une séance de la semaine précédente, une carte ancienne et déjà
  contributive entre d'un coup. Une monotonie ne la couvre **pas** : le motif
  n'avait jamais été servi, il est donc légitime — et il sort des mois après les
  faits, sur une séance dont personne ne se souvient. C'est exactement « une
  affiche servie à froid » que notre `afficheProvider` refuse déjà par ailleurs.

`/attendances/mine` règle les deux à la racine, parce qu'elle rend **l'histoire
sur laquelle nos transitions se calculent réellement**, au lieu de la lire à
travers des cartes qui n'en sont qu'un reflet partiel. Une contribution d'un
tiers ne la change pas. Et elle nous rend la propriété « zéro état » du module,
que la monotonie nous faisait perdre.

Ce n'est **pas bloquant** : la monotonie est écrivable, et le module tourne sans.
C'est une demande de meilleure architecture, la vôtre, et nous la prenons.

**Une seule précision demandée** : l'entrée doit-elle exister pour une présence
confirmée dont la séance n'a **aucune** carte-souvenir ? Nous lisons votre
proposition comme un oui — c'est tout son intérêt — mais c'est la phrase qui
décide, et nous avons appris aujourd'hui à demander la phrase.

---

## 5. `AFFICHE_READY` — gardez la charge enrichie, et une question

### Votre question, et la réponse

Vous demandiez si « aucun enrichissement `programTitle` / `placeName` /
`sessionAt` » décrivait ce que **nous** ne faisons pas, ou ce que nous vous
demandions de **ne pas envoyer**.

**C'était le premier. Gardez votre charge telle quelle**, `title` et `body`
compris — et vous avez raison sur le fond : un `title` réel se compose mieux
avec le titre du programme qu'avec « Nouvelle notification ».

Notre formateur Dart **refuse explicitement de composer ce type** et rend la
main au texte du serveur (`lib/core/push/push_text.dart:141`) :

```dart
if (message.notificationType == NotificationType.afficheReady) return null;
```

Trente lignes de commentaire au-dessus disent pourquoi : la découverte est le
produit, et la variante « programme » poserait l'adresse en seconde ligne d'une
bannière d'écran verrouillé — le schéma de vie que le module veille interdit.

### Ce que votre question nous a fait trouver chez nous

En la vérifiant, nous avons découvert que **notre extension de notification iOS
n'a pas cette garde**. `MeetdoPushTextFormatter.format` ne teste que
`programTitle != nil` : avec votre charge, elle composerait la variante
« programme » — titre du programme, date d'une séance passée, adresse en seconde
ligne. Exactement ce que la garde Dart empêche, sur l'autre moitié du chemin.

**C'est notre défaut, pas le vôtre**, et nous le corrigeons. Nous le vous
signalons pour une raison précise : il n'est pas encore visible.

### La question

**Envoyez-vous `mutable-content: 1` sur `AFFICHE_READY` ?**

C'est ce qui décide si l'extension s'exécute, donc si ce défaut est déjà en vol
ou seulement en embuscade. Nos extensions sont livrées mais **inertes tant que
le serveur ne l'envoie pas** — ce qui était l'état au dernier relevé, et qui a
pu changer avec le déploiement d'`AFFICHE_READY`.

Si oui, dites-le nous : nous corrigerons avant que le type ne soit envoyé en
production plutôt qu'après. Si non, nous corrigerons quand même, et vous nous
préviendrez le jour où vous l'activerez.

---

## 6. Où en est le client, pour situer ces demandes

Livré et **allumé** aujourd'hui : la composition (`afficheCompose`), la galerie
(`afficheGalerie`), et l'export image (`afficheImage`, allumé ce soir après avoir
tenu ses deux conditions). L'écran « Mes moments » a désormais deux onglets, et
la galerie y est une mosaïque compacte.

Écrit, testé, et **éteint faute des champs demandés ici** : la publication
(`affichePublication`), l'anneau (`afficheAnneau`), et la bande d'affiches du
fil — dont le tri, l'annuaire et les gestes sont complets, et dont les tests
prouvent un ordre que personne ne peut voir aujourd'hui.

Suite complète : **3 558 tests verts.**

---

## Récapitulatif

| # | Demande | Statut | Bloque quoi |
|---|---|---|---|
| **B7** | `activityName` + `categoryColorRamp` sur `AfficheDto` | décision prise, votre issue n° 2 | `affichePublication` **et** la bande |
| **B8** | `displayName` + `avatarUrl` sur `AfficheUpdateDto` | demande neuve | le rang « les autres » de la bande |
| **B4** | `GET /api/attendances/mine` | acceptée, non bloquante | rien — rend « zéro état » |
| — | `mutable-content` sur `AFFICHE_READY` ? | question, une phrase | l'urgence d'un correctif chez nous |
