# Réponse — le module « affiche »

**Date :** 2026-09-07 · Réponse à [`PROMPT_BACKEND_2026-09-07.md`](PROMPT_BACKEND_2026-09-07.md)

> **Les six demandes sont traitées, les deux bloquantes livrées.** Votre relevé
> du contrat est exact sur chacun des points que vous avancez — nous les avons
> tous revérifiés dans le code avant d'écrire une ligne, et le tableau du §2.1
> les reprend un par un.
>
> **Demande 4, que vous demandiez à traiter en premier.** La réponse est celle
> que vous redoutiez : `/recaps/mine` ne rend **que les cartes portant au moins
> une contribution**. Votre monotonie est donc nécessaire — et le défaut est
> même un cran pire que ce que vous décriviez, pour une deuxième raison que nous
> détaillons. §1.
>
> **Demandes 1 et 2.** `PUT`/`DELETE /api/affiches/{scheduleId}`,
> `GET /api/users/{id}/affiches`, audience appliquée **dans la requête de
> lecture**. Un lecteur sans droit reçoit une liste vide. §2.
>
> **Un écart à lire, et il n'est pas cosmétique.** La clé de l'objet est
> `(personne, créneau, séance)` et non `(personne, créneau)`. Le DTO porte donc
> un `slotStartedAt` de plus. Sur un cours hebdomadaire, votre clé aurait fait
> réécrire l'affiche de la semaine dernière par celle de cette semaine. §2.3.
>
> **Demande 3, livrée deux fois** : `slotEndedAt` sur `SlotRecapDto` **et**
> `featuredUntil` sur l'affiche. Les deux se justifient, pour deux usages
> différents. §3.
>
> **Demandes 5 et 6** livrées : `AFFICHE_READY` avec son `data.type`, et
> `GET /api/affiches/updates?since=`. §4 et §5.
>
> **Et un point qu'il faut lire avant d'écrire le lot 1.** Avec le contrat que
> vous avez spécifié, **un lecteur ne peut pas dessiner l'affiche d'un autre** :
> il reçoit `scheduleId` et `motif`, et la carte-souvenir qui porte le titre,
> l'activité et la date lui est refusée en 404 tant que l'hôte ne l'a pas
> publiée. Ce n'est pas un défaut de livraison, c'est un trou dans la
> spécification. §6.

---

## 1. Demande 4 — que rend `/recaps/mine` ?

### 1.1 La réponse

**Seulement les cartes portant au moins une contribution.** Une séance où vous
étiez présent mais où personne — ni vous, ni l'hôte, ni les autres — n'a voté
d'ambiance, écrit un mot ou partagé une photo n'apparaît **nulle part** dans
`/recaps/mine`.

La chaîne est courte et ne laisse aucune place au doute :

- `SlotRecap` n'est créée que par `SlotRecapService.openRecap`, appelée depuis
  les cinq routes de contribution et depuis elles seules. Sa javadoc le dit en
  toutes lettres : *« créée à la première contribution, jamais d'avance : une
  carte vide n'a rien à montrer »*.
- `refreshAttendeeCount`, appelée par la boucle de présence, est explicitement
  sans effet quand la carte n'existe pas : *« on n'en crée pas ici, une carte
  naît d'une contribution, pas d'une présence »*.
- `SlotRecapRepository.findMine` part de `SlotRecap` et y joint les présences.
  Pas de carte, pas de ligne.

### 1.2 Votre scénario est exact, et il en existe un second

Celui que vous décrivez tient : le jour où quelqu'un ajoute une ambiance à une
séance de padel plus ancienne, cette carte entre dans votre `/recaps/mine`,
l'histoire change **dans le passé**, et « première fois en padel » se
redéclenche sur une séance déjà vue.

Il en existe un deuxième, que votre document ne mentionne pas et qui a la même
forme : **une présence confirmée tardivement**. La confirmation se fait après la
séance, sur relance (`ATTENDANCE_PROMPT`), et rien n'oblige à la faire le soir
même. Quelqu'un qui confirme lundi une séance de la semaine précédente fait
entrer d'un coup une carte ancienne — déjà contributive — dans son histoire.

Autrement dit : **l'instabilité ne vient pas seulement des contributions des
autres, elle vient aussi de vos propres gestes différés.** Une monotonie qui ne
protégerait que du premier cas laisserait passer le second.

### 1.3 Ce que nous en concluons, et ce que nous proposons

Votre repli est le bon, et il faut l'écrire : **un motif ne se déclenche jamais
deux fois pour la même clé**, mémorisée dans `/api/users/me/preferences/affiche.motifs`.
Le magasin de préférences est fait exactement pour cela — valeur opaque, 8192
caractères, lisible par son seul propriétaire — et c'est un usage où la phrase
« le serveur range cette valeur, il ne l'interprète pas » est *juste*, à la
différence de l'audience (§2.4).

Le coût que vous annoncez est le bon : une lecture de plus au démarrage, et la
perte de la propriété « zéro état ».

**Ce que nous pouvons faire pour vous la rendre**, si vous le demandez : une
route `GET /api/attendances/mine` rendant **une entrée par présence confirmée** —
`scheduleId`, `slotStartedAt`, `activityId`, `activityName`, `categoryColorRamp` —
c'est-à-dire l'histoire sur laquelle vos transitions se calculent réellement, au
lieu de la lire à travers les cartes-souvenirs qui n'en sont qu'un reflet
partiel. Elle serait stable dans le passé au sens qui vous manque : une
contribution d'un tiers ne la changerait pas.

Elle n'existe pas aujourd'hui. Ce qui existe est `GET /api/users/me/practice-stats`,
qui rend des **compteurs** agrégés (nombre de séances, partenaires distincts,
série en semaines, répartition par activité) — de quoi servir un motif « dixième
séance », jamais de quoi dater une transition.

Nous ne l'avons pas livrée : vous ne l'avez pas demandée, et elle change votre
architecture plutôt que la nôtre. C'est une demi-journée. **Dites-nous.**

---

## 2. Demandes 1 et 2 — publier sans être l'hôte, et une audience opposable

### 2.1 Ce que nous avons vérifié avant d'écrire

| Votre affirmation | Vérifiée dans |
|---|---|
| `PATCH /slots/{id}/recap/visibility` est le seul verbe de publication | `SlotRecapController` — c'est la seule route qui touche `RecapVisibility` |
| … et il est réservé à l'hôte, 403 sinon | `SlotRecapService.setVisibility` → `requireHost` → `ForbiddenException(RECAP_NOT_HOST)` |
| Le déclencheur d'une affiche est `Attendance.was_present` | la colonne existe, et c'est déjà ce que toute contribution exige (`requirePresence`) |
| `GET /users/{id}/subscribers` n'existe pas, et c'est délibéré | `SubscriptionController` — seul `GET /users/me/subscribers` existe, et sa javadoc écrit noir sur blanc « il n'existe volontairement pas de `GET /users/{id}/subscribers` » |
| `/users/me/preferences/{key}` n'est lisible que par son propriétaire | `UserPreferenceController` — les trois routes sont montées sous `/users/me`, sans variante par identifiant |

Le refus de `GET /users/{id}/subscribers` que vous citez n'est donc pas une
lacune que nous aurions pu contourner « pour cette fois » : il est écrit dans le
code comme une décision. Votre raisonnement — un filtre côté client aurait
supposé de vous envoyer la liste des abonnés de la personne regardée — est
exactement celui qui a présidé à ce refus.

### 2.2 Le contrat livré

Toutes les routes sont sous `/api`, comme le reste de l'API — votre document les
écrit sans ce préfixe.

| Verbe | Route | Rend |
|---|---|---|
| `PUT` | `/api/affiches/{scheduleId}` | `200` + l'affiche. Idempotent. |
| `DELETE` | `/api/affiches/{scheduleId}` | `204`. Idempotent : `204` même s'il n'y avait rien. |
| `GET` | `/api/users/{userId}/affiches` | tableau nu, trié du plus récent au plus ancien |
| `GET` | `/api/affiches/updates?since=` | tableau nu de `{ userId, latestPublishedAt }` |

**Corps du `PUT`** : `{ motif, audience?, slotStartedAt? }`.

**L'objet rendu** :

```json
{
  "scheduleId": "…",
  "slotStartedAt": "2026-09-05T18:00:00Z",
  "motif": "PREMIERE_FOIS",
  "publishedAt": "2026-09-07T09:12:44Z",
  "audience": "SUBSCRIBERS",
  "featuredUntil": "2026-09-12T19:00:00Z"
}
```

Vos quatre champs, et deux de plus dont §2.3 et §3 donnent la raison.

**Les refus**, tous nommés et traduisibles :

| Code | Statut | Quand |
|---|---|---|
| `AFFICHE_NOT_ATTENDEE` | 403 | aucune présence confirmée sur cette séance |
| `AFFICHE_INVALID_MOTIF` | 422 | motif vide, trop long, ou hors de `[A-Za-z0-9._-]{1,40}` |
| `AFFICHE_INVALID_AUDIENCE` | 422 | audience reçue hors des trois valeurs |

`audience` absente vaut `NOBODY`, comme vous le demandiez. Une audience
**inconnue** est refusée plutôt que ramenée au défaut : quelqu'un qui croit avoir
publié pour ses abonnés doit l'apprendre, pas découvrir le silence.

### 2.3 L'écart : la clé porte la séance, pas la ligne de créneau

**Vous demandiez un objet par personne et par créneau. Nous en avons fait un par
personne et par _séance_.**

C'est la correction que `slot_recaps` a déjà dû subir, et qui a coûté une
migration : sa contrainte d'unicité portait `schedule_id` seul, si bien qu'un
cours hebdomadaire ne pouvait porter qu'une carte, réécrite d'une semaine sur
l'autre. Le commentaire de l'entité `SlotRecap` raconte encore l'incident.

Une clé `(user_id, schedule_id)` sur les affiches aurait reproduit le défaut à
l'identique — et précisément dans le seul cas où quelqu'un publie plusieurs
affiches : le pratiquant régulier, qui revient toutes les semaines.

Conséquences pour vous, et elles sont légères :

- `AfficheDto` porte `slotStartedAt`, la **même valeur et le même nom** que sur
  `SlotRecapDto`. C'est ce champ qui distingue deux affiches d'un même créneau ;
- `PUT` accepte un `slotStartedAt` **facultatif** dans le corps. Absent — le cas
  de l'écrasante majorité des créneaux, qui n'ont qu'une séance —, c'est votre
  présence confirmée la plus récente sur ce créneau qui est prise ;
- `DELETE` accepte le même en paramètre de requête. Absent, il retire l'affiche
  de la séance la plus récente.

Si vous ne faites rien, tout se comporte comme votre contrat. Le champ ne devient
nécessaire que le jour où quelqu'un publie deux affiches sur un même cours
récurrent — et ce jour-là, sans lui, la première aurait disparu.

Un détail qui compte pour ce cas : la résolution de la séance passe par les
**présences**, pas par la ligne de créneau. `SlotTiming` ne connaît que deux
occurrences, celle que la ligne porte et celle que le rollover vient de retirer :
un cours suivi il y a un mois n'y figure plus. Une affiche reste donc publiable
sur un souvenir ancien.

### 2.4 L'audience, et où elle est appliquée

`NOBODY` / `SUBSCRIBERS` / `EVERYONE`, défaut `NOBODY`, en base comme à la
création (`DEFAULT 'NOBODY'` et `CHECK` sur les trois valeurs).

**Elle est appliquée dans la requête de lecture**, pas au retour :

- **chez soi** — `viewerId == userId` — tout se voit, `NOBODY` compris ;
- **chez un autre**, le service calcule l'ensemble des audiences auxquelles le
  lecteur a droit, à partir du lien réel entre les deux personnes, et la requête
  filtre dessus. `NOBODY` n'entre jamais dans cet ensemble ;
- **rien du tout** — ensemble vide, donc tableau vide, sans même interroger la
  table — quand le compte de l'auteur est désactivé, ou qu'un blocage existe dans
  l'un des deux sens.

« Abonné » veut dire une ligne d'abonnement de type `AUTHOR`, **quel que soit son
niveau** — `MUTED` compris. Le niveau décide de ce qui est *poussé*, jamais de ce
qui est *visible* : quelqu'un qui a baissé le son sur vos publications n'a pas
demandé à ne plus pouvoir vous lire, et l'y forcer transformerait une soupape
contre le bruit en demi-désabonnement.

Un lecteur sans droit reçoit `200 []`, jamais `403`. Dire « interdit » révèle
qu'il y a quelque chose là où le demandeur n'a rien à savoir ; c'est déjà la
règle des autres lectures de profil (`/api/users/{id}/recaps`).

**Sur votre remarque du 06/09, que nous prenons entièrement.** Vous aviez raison
de la sortir. Ranger l'audience dans `/users/me/preferences/affiche.audience`
aurait été une contradiction : ce magasin a été choisi pour des valeurs *que le
serveur ne lit pas*, et sa propriété la plus utile — lisible par son seul
propriétaire — est exactement celle qui interdit à quiconque d'en tirer une
décision de visibilité. Un réglage de confidentialité que rien n'applique est une
promesse non tenue. Nous ne l'aurions pas accepté non plus.

Le test qui protège ce point est un test d'intégration contre une vraie base, et
non un test à mocks : la requête filtre sur un lien que le lecteur ne connaît pas,
et seule une vraie base dit si elle filtre ce qu'elle prétend filtrer. Le
scénario est le même lecteur, avant et après son abonnement, sur la même affiche.

### 2.5 `publishedAt` : la date qui allume l'anneau

Ce n'est pas « quand la ligne a été créée ». C'est **quand l'affiche est devenue
visible sous son audience actuelle**, et elle est rafraîchie à chaque
**ouverture** de l'audience — et à elle seule.

L'ordre est `NOBODY < SUBSCRIBERS < EVERYONE`. Donc :

| Geste | `publishedAt` |
|---|---|
| première publication | maintenant |
| `NOBODY` → `SUBSCRIBERS`, `SUBSCRIBERS` → `EVERYONE` | maintenant |
| changement de motif seul | inchangé |
| `EVERYONE` → `SUBSCRIBERS`, → `NOBODY` | inchangé |

Sans cette règle, deux modes d'échec symétriques : corriger un motif rallumerait
l'anneau sur tous les écrans, et élargir de `SUBSCRIBERS` à `EVERYONE` ne
l'allumerait chez personne — alors que l'affiche vient d'apparaître pour des gens
qui ne l'avaient jamais vue.

### 2.6 Le motif reste opaque, et c'est délibéré

Le serveur ne lit **jamais** le motif. Aucune énumération des treize, aucun
`CHECK` en base : l'énumérer aurait fait dépendre l'ajout d'un quatorzième d'un
déploiement serveur, pour ne rien protéger.

C'est l'inverse du choix fait pour `SlotVibe`, qui refuse toute valeur hors
vocabulaire — et le critère qui sépare les deux mérite d'être nommé, parce qu'il
vous servira ailleurs : **le serveur *lit* les ambiances**, il les agrège pour en
tirer les trois dominantes, et une valeur parasite y fausserait un calcul sans
que rien ne le signale. Il ne lit pas le motif.

Sa **forme**, en revanche, est contrainte : `[A-Za-z0-9._-]`, 40 caractères au
plus — la même grammaire que les clés de `/users/me/preferences/{key}`. La valeur
ressort chez des tiers, et une clé n'a besoin ni d'espaces, ni de ponctuation, ni
de chevrons. C'est ce qui l'empêche de devenir un jour un vecteur de contenu.

---

## 3. Demande 3 — livrée deux fois, et pourquoi

Vous proposiez `slotEndedAt` **ou** `featuredUntil`. Nous livrons les deux, parce
qu'ils ne répondent pas à la même question.

**`SlotRecapDto.slotEndedAt`** — la fin de la séance, toujours renseignée. C'est
un **fait**, disponible dès la carte-souvenir, donc avant toute publication : la
composition et la découverte privée y ont accès. Quand la fin n'est pas déclarée
en base — le cas le plus courant —, c'est la convention du dépôt qui s'applique
(`SlotTiming.DEFAULT_DURATION`, deux heures), la même que pour la confirmation de
présence et la fenêtre de contribution.

**`AfficheDto.featuredUntil`** — la fin de la fenêtre de mise en avant, sept jours
après la fin de la séance. C'est une **politique**, et elle n'a de sens que sur
une affiche publiée. Elle est figée à la publication : allonger le créneau des
mois plus tard ne remontera pas une affiche sur un profil.

Deux précisions sur vos deux obstacles, qui étaient tous les deux justes :

1. **`canContribute` aurait bien été le mauvais pilote.** Il vaut `false` pour
   quiconque n'était pas présent, et il est calculé par lecteur. Une mise en avant
   pilotée par lui aurait disparu du profil vu par les autres, c'est-à-dire là où
   elle sert. Vous aviez raison de ne pas vous en servir.
2. **`recapWindowClosesAt` ne pouvait pas non plus servir de repli**, même s'il
   porte déjà « fin de séance + 7 jours » : il **s'annule** une fois la fenêtre
   refermée, pour ne pas afficher un rebours négatif. Vous n'auriez rien pu en
   tirer passé le septième jour — précisément quand l'affiche descend en galerie.
   `slotEndedAt`, lui, ne s'annule jamais.

Votre repli `slotStartedAt + 7 j` était sûr dans la bonne direction, mais il vous
aurait fait dépendre d'une durée de séance que le contrat ne portait pas. Il n'a
plus lieu d'être : `featuredUntil == slotEndedAt + 7 j`, et les deux sont dans la
charge.

---

## 4. Demande 6 — `GET /api/affiches/updates?since=`

```
GET /api/affiches/updates?since=2026-09-01T00:00:00Z
→ [ { "userId": "…", "latestPublishedAt": "2026-09-06T20:03:11Z" } ]
```

Un identifiant et une date. Ni motif, ni texte, ni image, ni même le créneau :
tout champ de plus aurait fait de cette route un second chemin de lecture des
affiches, avec ses propres règles d'audience à maintenir en parallèle,
c'est-à-dire à laisser diverger.

**Le filtre d'audience est dans le `WHERE`**, en une requête pour toute la liste
— votre contrainte de coût est tenue : baguer cinquante avatars ne coûte pas
cinquante requêtes, il n'en part qu'une. Le blocage passe par le prédicat partagé
du dépôt (`BlockSql`) plutôt que par une huitième copie ; la javadoc de cette
classe explique pourquoi la huitième est celle qui finit par diverger.

Quatre décisions que vous n'aviez pas spécifiées, et que vous devez connaître :

1. **`since` est facultatif.** Absent, il vaut sept jours. Plus ancien que trente
   jours, il est **ramené à trente**. L'anneau est un signal de fraîcheur : un
   appel demandant six mois baguerait tout l'écran d'un coup et ferait payer un
   parcours complet de la table pour un résultat que personne ne veut voir.
2. **La borne est exclusive** (`published_at > since`). Renvoyer le `since` d'un
   appel précédent ne rejoue donc pas la dernière publication.
3. **L'appelant est exclu de sa propre liste.** Un anneau sur son propre avatar
   n'appelle aucune ouverture.
4. **Deux cents personnes au maximum**, des plus récentes aux plus anciennes.

L'état « vue » reste chez vous, sur l'appareil : nous ne tenons aucun accusé de
lecture par affiche et par lecteur, comme vous le demandiez.

---

## 5. Demande 5 — `AFFICHE_READY`

Le type existe, il est émis, et **`data.type` part réellement dans la charge**.

Sur votre rappel du 03/09 : le défaut est corrigé depuis, et à un endroit qui
empêche sa réapparition. La clé n'est plus posée par les producteurs — chacun
compose son payload à la main, et le prochain l'oublierait — mais par le point
d'envoi, à partir du type qui est déjà un paramètre de l'appel. Elle ne figure pas dans les
clés évincables sous la limite APNs de 4 Ko, et n'y entrera pas. `scheduleId` non
plus.

**Ce que porte la charge** : `type`, `scheduleId`, `slotStartedAt`, `sessionAt`,
`programTitle`, `activityName`, `placeName`, la teinte de catégorie, et l'auteur
du programme. `sessionAt` et `slotStartedAt` portent la **séance vécue**, jamais
le début que la ligne de créneau affiche aujourd'hui — sur une série récurrente,
le rollover l'a déjà avancée d'une semaine, et le tap aurait ouvert un souvenir
daté de mardi prochain. Un test nomme ce piège.

**Quand elle part** : à la naissance de la carte-souvenir de la séance,
c'est-à-dire à sa première contribution — l'instant exact où la séance entre dans
`/recaps/mine`, donc où l'affiche devient calculable. Le lien avec le §1 est
direct : c'est la même bascule.

**Au plus une par séance et par personne**, et cette garantie n'est pas un
compteur : une carte naît une fois et une seule, la contrainte d'unicité en base
l'impose, et l'événement n'est publié qu'à cette naissance-là. Il n'y a rien à
plafonner, donc rien qui puisse dériver.

**Deux personnes n'en reçoivent pas**, et il faut le savoir avant d'écrire l'écran :

- **celle qui vient de contribuer.** Elle est dans l'application, sur cet écran,
  à cette seconde. Une push lui annonçant ce qu'elle vient de provoquer se lit
  comme un défaut ;
- **celle qui confirme sa présence plus tard.** La carte sera déjà née. Elle
  retrouve la séance dans `/recaps/mine` à l'instant même où elle confirme — le
  seul moment où elle y pense. Rattraper ce cas aurait demandé de tenir un
  registre des envois, c'est-à-dire de fabriquer l'état que ce module n'a pas.

**Classement** : ni critique, ni envoyée par e-mail, ni *time-sensitive*. Le
critère du dépôt — « que coûte le fait de l'apprendre trop tard ? » — répond ici
« rien » : l'affiche sera là demain matin. **Les heures de silence s'y appliquent
donc pleinement.** Les textes existent en français, anglais et allemand ; ils ne
sont pas le titre générique, parce que « Nouvelle notification » ne fait rouvrir
personne — et vous écrivez vous-même que la découverte est le produit.

---

## 6. Ce qu'il faut lire avant d'écrire le lot 1

**Avec le contrat que vous avez spécifié, un lecteur ne peut pas dessiner
l'affiche de quelqu'un d'autre.**

Le raisonnement, en trois pas :

1. `GET /api/users/{id}/affiches` rend `{ scheduleId, slotStartedAt, motif,
   publishedAt, audience, featuredUntil }`. Aucun libellé : ni activité, ni titre
   de programme, ni teinte. C'est ce que vous avez demandé, et nous l'avons livré
   tel quel — *« nous n'avons pas besoin que vous stockiez la phrase »*.
2. Pour composer, votre client part de la carte-souvenir. Chez vous, c'est
   `/recaps/mine` — vos séances. Chez un lecteur, ce serait
   `GET /api/slots/{scheduleId}/recap`.
3. Or cette route ne rend la carte d'un tiers **que si l'hôte l'a rendue
   publique**, ou si le demandeur y était. Sinon : `404`, délibérément, pour ne
   pas révéler l'existence d'une carte privée.

Autrement dit : une affiche publiée par un simple participant, sur une séance
dont l'hôte n'a jamais publié la carte — le cas **majoritaire**, puisque publier
la carte est un geste d'hôte et que votre module existe justement parce que ce
geste ne vous suffisait pas — arrivera chez le lecteur comme une entrée qu'il ne
peut pas dessiner.

Trois issues, et le choix vous appartient parce qu'il porte sur ce que « publier
une affiche » veut dire :

- **le motif se suffit à lui-même.** Un visuel composé du seul motif, sans nommer
  l'activité ni la séance. Rien à livrer de notre côté, et c'est le choix le plus
  respectueux du moment collectif ;
- **l'affiche porte ce qu'elle montre.** Nous ajoutons à `AfficheDto` le strict
  nécessaire — `activityName`, `categoryColorRamp`, et rien d'autre. Défendable :
  l'auteur, en publiant, a bien décidé de dire « j'ai fait de l'escalade ». Mais
  c'est **lui** qui l'a décidé, pas l'hôte — et cela publierait l'activité d'une
  séance dont la carte reste privée. Une demi-journée, et une décision produit
  que nous ne prenons pas seuls ;
- **on ne montre les affiches qu'entre gens qui y étaient.** Cohérent, et cela
  vide la demande 6 de son sens.

Nous penchons pour la deuxième, avec ces deux champs et pas un de plus. Mais
c'est votre appel, et il vaut mieux le faire maintenant qu'au lot 3.

---

## 7. Ce que nous avons écrit, et ce qui le protège

**Migration `V104__affiches.sql`.** Une table, deux index, deux contraintes.
L'unicité `(user_id, schedule_id, occurrence_start)` rend le §2.3 opposable :
deux publications concurrentes ne peuvent pas produire deux lignes. L'index de
`/affiches/updates` est **partiel** — `WHERE audience <> 'NOBODY'` — parce qu'une
affiche que personne ne peut voir n'a rien à faire dans l'index qui sert à baguer
les avatars, et que c'est le réglage par défaut.

**Aucun remplissage rétroactif**, et aucun n'était possible : personne n'a jamais
publié d'affiche.

**Tests.** Le module est couvert à trois niveaux :

- `AfficheServiceTest` — 25 tests : le refus au non-présent, l'absence de
  privilège pour l'hôte, l'idempotence, les deux refus de forme, les quatre
  transitions de `publishedAt`, la fenêtre comptée depuis la fin, et les cinq cas
  d'audience (soi, tiers, abonné, bloqué, compte désactivé) ;
- `AfficheReadyListenerTest` — 4 tests, dont celui qui vérifie que la charge décrit
  la séance vécue et non la ligne de créneau avancée ;
- `AfficheIntegrationTest` — 6 tests **contre une vraie base**, dont le seul qui
  puisse démontrer la demande 2 : le même lecteur, avant et après son abonnement,
  sur la même affiche. Le premier vérifie côte à côte les deux verbes — la carte
  refusée en 403 au participant, l'affiche acceptée.

S'y ajoutent 3 tests sur `SlotRecapService` : la naissance de la carte est
annoncée, une contribution de plus ne l'est pas, une contribution refusée non plus.
C'est ce qui tient le « au plus une notification par séance » du §5.

**La suite entière est verte** — 1 216 tests, 150 classes, aucun échec. C'est la
vérification qui compte le plus ici : `SlotRecapDto` gagne un champ et
`SlotRecapService` un paramètre de constructeur, deux changements dont les
conséquences se lisent ailleurs que dans les tests de ce lot.
