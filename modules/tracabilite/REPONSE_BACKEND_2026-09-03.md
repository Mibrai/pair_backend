# Réponse backend — l'arrivée à deux temps, et la veille sans contact

**Date :** 2026-09-03
**Répond à :** `modules/tracabilite/PROMPT_BACKEND_2026-09-03.md`

> **Oui aux deux, et le §1.4 est livré avec.** La bascule automatique désarme
> réellement l'objection que nous opposions au code de séance le 02/09 : nous
> l'avons donc écrite d'abord, et la validation par l'hôte ensuite. 15 minutes,
> comptées depuis `arrivalClaimedAt`, et l'échéance est rendue dans le
> `WatchDto` — vous affichez notre heure, jamais la vôtre.
>
> **Trois écarts délibérés sur la forme, tous pour la même raison de fond** : le
> code de retour est tiré à la remise et non à la validation, le `duressCode` se
> pose au même endroit, et le verbe de validation répond `202` même sur
> quelqu'un qui n'a rien armé. Détaillés aux §2.3, §2.4 et §3.
>
> **Et une collision que votre document ne portait pas, qui aurait cassé le §1
> en production** : la boucle aller aurait classé « perdu en chemin » quelqu'un
> qui venait de déclarer son arrivée. C'est le §2.5, et c'est ce que nous avons
> corrigé en premier.
>
> **Une seule chose ne va pas dans votre §4** : `guardianName` n'existe pas chez
> nous. Vous nous demandez de confirmer un point de contrat sur un champ que
> nous n'avons jamais servi — §5.2.

---

## 1. Ce qui existait déjà, et que vous n'aviez pas à demander

Trois de vos douze lignes portaient sur des choses en place. Nous le disons
avant le reste, pour que vous puissiez repriorer.

| Ce que vous demandez | État avant ce lot |
|---|---|
| `arrivalConfirmedAt` | **La colonne et le champ du DTO existaient déjà.** Le second temps n'avait qu'à les remplir depuis un autre geste |
| `participationId` sur les inscrits | **Déjà servi** par `GET /slots/{id}/participants`. Votre §1.3 visait juste |
| Un état terminal qui ne prétend rien (§2.2) | **Le patron existait** : `NOT_ARRIVED`, du 02/09, dont le raisonnement — « `ESCALATED` est balayé par la boucle » — est mot pour mot le vôtre |
| `alertDelivery: NONE` et « pas de lien public » | **Automatiques.** L'état de remise se calcule sur l'outbox, vide ici ; le jeton public n'est posé que par le point d'envoi des alertes, qui ne s'ouvre pas |
| Les réglages privés pour l'acquittement (§2.3) | **En place depuis le 02/09**, exactement comme vous le décrivez |

Une conséquence utile pour vous : **le §2.2 était déjà à moitié satisfait**. Une
veille sans contact qui n'arrive jamais tombait déjà en `NOT_ARRIVED` et ne
prévenait personne. Ce qui manquait n'était que la branche **retour** — échéance
passée après une arrivée validée —, et c'est là que le nouvel état s'est ajouté.

---

## 2. L'arrivée à deux temps — livrée

### 2.1 · Les trois verbes

```
POST /api/watches/{id}/arrival/claim                          → 202, corps vide
POST /api/schedules/{sid}/arrivals/{participationId}/confirm  → 202, corps vide
POST /api/watches/{id}/code/claim                             → 200 { returnCode }
```

`POST /watches/{id}/arrival` **n'a pas bougé** et ne bougera pas, pour la raison
que vous donnez : toutes les applications installées écrivent sa réponse au
Trousseau, et le lui retirer laisserait chaque téléphone non mis à jour sans
code, donc sans clôture, donc avec une alerte à l'échéance. Votre analyse était
juste et nous l'avons suivie sans réserve.

Les préconditions sont au contrat OpenAPI (votre §12) :

| Verbe | Refus | Code |
|---|---|---|
| `arrival/claim` | hors `ARMED`/`EN_ROUTE` | `409 WATCH_ARRIVAL_NOT_EXPECTED` |
| `arrival/claim` | déjà déclarée | `409 WATCH_ARRIVAL_ALREADY_CLAIMED` |
| `arrivals/{id}/confirm` | créneau qui n'est pas le sien | `404` — jamais 403 |
| `code/claim` | arrivée non validée | `409 WATCH_ARRIVAL_NOT_CONFIRMED` |
| `code/claim` | second appel | `409 WATCH_CODE_ALREADY_CLAIMED` |
| `code/claim` | pas le titulaire | `404` |

### 2.2 · Le champ, pas l'état — et deux champs de plus

`arrivalClaimedAt` est une colonne, comme vous le demandiez et pour votre
raison. Le `WatchDto` porte donc trois champs neufs :

```json
"arrivalClaimedAt":     "2026-09-03T19:42:11Z",
"arrivalConfirmedAt":   null,
"arrivalAutoConfirmAt": "2026-09-03T19:57:11Z"
```

`arrivalAutoConfirmAt` est nul quand il n'y a rien à attendre — pas de
déclaration, déjà validée, ou veille terminée. C'est la forme que vous préfériez
au §1.4, et vous aviez raison de la préférer : une constante de contrat vous
aurait fait additionner sur l'appareil, et deux horloges auraient fini par
diverger.

### 2.3 · Écart 1 — le code est tiré à la remise, pas à la validation

Vous écriviez : « à la validation, le code de retour est tiré ». Nous ne l'avons
pas fait, et c'est **votre argument** qui nous en a empêchés.

Si le code naît à la validation, son texte en clair doit attendre quelque part
entre la validation et le moment où la personne le lit. Or il n'existe nulle
part au repos — c'est ce qui rend vraie la phrase que toute l'application
répète. Le stocker, même quelques minutes, même chiffré, romprait la seule
propriété qui compte.

Le code est donc tiré **au moment où il est remis**. `confirm` pose
`arrivalConfirmedAt`, passe la veille `ON_SITE` et fait courir l'échéance de
retour ; `code/claim` tire, stocke l'empreinte, et rend le clair. Rien ne change
pour vous à l'usage : vous appelez `code/claim` au réveil de la notification et
à l'ouverture de l'écran de veille, comme vous l'aviez prévu.

**Une conséquence à connaître :** entre la validation et la remise, la veille est
`ON_SITE` sans code. La clôture y répond `WATCH_NO_CODE_TO_CLOSE`. C'est sans
danger — les rappels poussent la personne vers l'écran qui appelle `code/claim` —
mais si vous préférez que ce cas rende une erreur distincte, dites-le et nous
l'ajouterons.

### 2.4 · Écart 2 — `duressCode` se pose sur `code/claim`

Vous le placiez sur `arrival/claim`. Il n'y a rien à quoi l'attacher à ce
moment-là : l'empreinte du code de contrainte vit sur la même ligne que celle du
code de retour, sous le même sel et la même version de clé, et cette ligne naît
à la remise. L'accepter à la déclaration aurait voulu dire inventer un second
stockage pour un secret haché, avec son sel, sa version, et sa migration au
moment de la validation — doubler la surface de la chose la plus sensible du
module pour n'avancer que de quelques minutes.

Il se pose donc sur `code/claim`, avec les mêmes règles qu'aujourd'hui. La
symétrie y gagne d'ailleurs : le code de contrainte se choisit à l'instant même
où le code de retour arrive sur l'appareil.

### 2.5 · La collision — la boucle aller aurait tué les arrivées déclarées

**C'est le point que votre document ne portait pas, et il aurait cassé le §1 dès
le premier soir.**

Notre boucle aller balaie les veilles `ARMED`/`EN_ROUTE` et, à T+45 sans arrivée
validée, prononce « perdu en chemin » — un état **terminal**. Or vous demandiez,
à juste titre, que `claim` ne change pas l'état. Les deux règles mises bout à
bout donnaient ceci : quelqu'un qui déclare son arrivée à T+40 est classé perdu
en chemin cinq minutes plus tard, sa veille se referme pour de bon, et
`code/claim` lui répondra `409` pour toujours. Sa soirée n'est plus surveillée
par rien — l'inverse exact de ce que le §1.4 cherche à garantir.

Et, plus visible sinon plus grave : les relances « tu y es ? » continuaient de
partir à quelqu'un qui venait de dire qu'il y était.

**`arrivalClaimedAt` suspend donc la boucle aller** : plus de demandes, plus de
verdict de non-arrivée. Ce qui attend désormais une veille déclarée est sa
validation, et rien d'autre. La suspension est structurelle — un `return` en
tête de la boucle — plutôt qu'une condition ajoutée à chaque branche, pour
qu'elle ne puisse pas être contournée par la branche suivante qu'on écrira.

### 2.6 · Le §1.4 — la bascule automatique, et pourquoi elle a son propre balayage

15 minutes, comptées depuis `arrivalClaimedAt`. Votre raison de préférer cette
base à l'heure de début est la bonne, et nous l'avons reprise telle quelle : un
hôte peut modifier l'heure de début, il ne peut pas modifier le geste de la
personne.

**Inconditionnelle** : aucune condition sur l'hôte, sur le créneau, sur rien. La
seule horloge est celle du geste.

Un détail d'implémentation qui vaut d'être dit, parce qu'il a failli nous
échapper : la bascule ne pouvait **pas** se greffer sur le balayage des relances.
Celui-ci ne voit que les veilles dont la base aller est déjà passée — or elle
vaut le début de la séance. Quelqu'un qui déclare son arrivée dix minutes en
avance n'y serait jamais entré, et sa validation ne serait jamais tombée. Elle a
donc sa propre requête, sur les seuls deux horodatages. Un test le garde
(`arriveeDeclareeEnAvance_doitQuandMemeSeValider`).

### 2.7 · L'insigne, et les deux populations

`GET /slots/{id}/participants` porte désormais, sur chaque inscrit :

```json
"arrival": { "state": "NONE" | "CLAIMED" | "CONFIRMED",
             "claimedAt": null, "confirmedAt": null }
```

`NONE` est la valeur par défaut, et c'est toute la protection : un inscrit sans
veille et un inscrit qui a armé sans déclarer rendent le **même objet**, champ
pour champ. Le type ne peut pas les distinguer. Il ne porte rien d'autre — ni
retard, ni motif, ni durée —, il n'apparaît dans aucun DTO public, et la file
d'attente le rend toujours `NONE` (votre §3a, et §10).

**Une correction à votre §1.5.** Vous écriviez que les deux populations —
attendus et déclarés — sont « disjointes par construction ». Ce n'était vrai que
tant que l'état les séparait. Avec un champ et pas un état, quelqu'un qui déclare
reste `ARMED`, donc reste dans `pending-arrivals` : le même nom aurait porté les
deux gestes à la fois. Nous avons filtré `pending-arrivals` sur l'absence de
déclaration, ce qui rétablit la propriété que vous teniez pour acquise.

`seen-by-host` est inchangé et ne vaut **pas** déclaration (votre §11, confirmé) :
il ne touche que la base des relances.

---

## 3. Écart 3, et la recommandation que nous vous demandons de prendre

Votre §1.3 demande que `NONE` soit indistinguable. **Nous l'avons fait, et ce
n'est pas suffisant — la protection ne tient pas au niveau où vous l'avez
posée.**

Ce n'est pas la donnée qui trahit, c'est **le geste disponible**. Deux exemples,
tirés de votre propre parcours :

1. **Le verbe de validation.** S'il répondait `404` ou `409` sur un inscrit sans
   veille, l'organisateur apprendrait qui se protège en essayant, une ligne à la
   fois. Nous l'avons donc rendu **`202` dans tous les cas où le créneau est le
   sien**, sans effet quand il n'y a rien à valider. C'est notre troisième écart,
   et il n'était pas négociable : sans lui, tout le soin pris sur `NONE` ne
   servait à rien.

2. **`seen-by-host`, que vous gardez.** Il prend un `watchId`, et le seul endroit
   d'où vous le tirez est `pending-arrivals` — une liste qui ne contient, par
   construction, que des gens qui ont armé. Le bouton « je la vois » n'existe
   donc que devant les personnes protégées. **Un hôte apprend qui se protège en
   regardant quels boutons son écran affiche, sans lire un seul champ.**

**Ce que nous vous recommandons**, et c'est plus large que ce que vous
demandiez : que tout ce qui est côté hôte s'adresse par `participationId`, et
que la liste des inscrits devienne la seule surface. Concrètement, `seen-by-host`
accepterait un `participationId`, rendrait `202` pour tout inscrit du créneau —
qu'il ait armé ou non — et ne ferait rien quand il n'y a pas de relance à
repousser. `pending-arrivals` resterait servi pour les applications installées,
mais n'alimenterait plus d'écran.

Le tableau de l'organisateur serait alors uniforme : **une ligne par inscrit, les
mêmes gestes devant chacune**, et `NONE` ne dirait vraiment rien.

Nous ne l'avons pas fait unilatéralement parce que cela change la forme de votre
écran, pas seulement notre contrat. Dites-nous si nous le livrons : c'est une
demi-journée chez nous, et c'est la seule façon que la propriété que vous
cherchez soit vraie ailleurs que sur le papier.

**Un point voisin, à trancher aussi.** `GET /slots/{id}/participants` rend
aujourd'hui un `403` nommé (`SLOT_PARTICIPANTS_HOST_ONLY`) là où tout le module
veille tient le silence du `404`. Notre propre code note déjà l'incohérence.
Maintenant que cet endpoint porte une donnée de sécurité, nous penchons pour
l'aligner sur `404`. C'est une rupture de contrat pour vous, donc votre décision.

---

## 4. La veille sans contact — livrée

`guardianId` est facultatif sur `POST /watches`. La colonne a perdu son
`NOT NULL`, et rien n'a été rétro-rempli : les veilles existantes en ont toutes
un et fonctionnent à l'identique.

### 4.1 · Ce qui ne sort pas

`NO_CONTACT` est l'état terminal de ces veilles-là. Les rappels partent — c'est
l'essentiel de ce qu'une telle veille apporte —, puis, à l'heure où une veille
ordinaire escalade, celle-ci se referme :

- **aucun envoi, jamais.** Le garde est posé au point d'envoi unique du module,
  et non chez ses trois appelants : c'est le passage obligé de tout ce qui sort,
  et le quatrième appelant sera écrit par quelqu'un qui ne connaîtra pas la
  règle ;
- **`alertDelivery: NONE`** de bout en bout ;
- **pas de lien public** — il naît à l'alerte, il n'y a pas d'alerte ;
- **jamais `ESCALATED`.** Votre argument est repris tel quel dans le code : ce
  mot veut dire « un message est parti à un tiers », et votre bandeau corail en
  dépend.

Comme pour `NOT_ARRIVED`, l'état fait plus que nommer — il sort la veille du
champ de la boucle retour, qui rappellerait sinon le point d'envoi à chaque
passage sur une veille sans destinataire.

`NO_CONTACT` reste visible dans « mes veilles actives » pendant 24 h, pour la
même raison que `NOT_ARRIVED` : personne n'a été prévenu — c'est exactement ce
qui avait été accepté — et c'est le seul endroit où la personne le lit.

### 4.2 · Deux limites que nous préférons écrire

**Le code de contrainte ne fait rien sur une veille sans contact.** Son effet
entier est « prévenez le proche en silence » ; sans proche, il n'en reste rien.
Nous avons rendu les deux clôtures **strictement identiques** — même état, même
`closedAt`, aucune trace qui les distingue —, ce qui est plus protecteur que de
marquer la contrainte : une marque que rien ne consomme resterait lisible dans le
journal, sur l'appareil que la personne contrainte a peut-être à montrer. **Votre
écran ne devrait probablement pas proposer de poser un code de contrainte quand
il n'y a pas de contact**, ou devrait dire ce qu'il fera.

**Panic est refusé** (`409 WATCH_NO_GUARDIAN`), plutôt que rendu `202` sans
effet. Ce geste veut dire « prévenez maintenant » : rendre 202 laisserait croire
que quelqu'un a été alerté. Le code nommé vous permet d'éteindre le bouton.

**Et un refus de saisie :** un `backupGuardianId` sans `guardianId` est rejeté
(`422 WATCH_NO_GUARDIAN`). La branche du secours ne s'ouvre qu'après que le
principal a été prévenu sans rien ouvrir ; l'accepter armerait une veille dont le
seul contact ne serait jamais joint — pire que pas de contact, puisque votre
application croirait alors qu'il y en a un.

### 4.3 · L'acquittement (votre §2.3) — nos réponses

**a. Les réglages privés vous vont-ils pour une donnée de sécurité ?** Oui, et
nous préférons cela à un champ nommé. Votre inquiétude est fondée mais elle se
retourne : un champ nommé sur le profil serait auditable depuis le support, et
donc lisible par nous — c'est-à-dire exactement la propriété que la table des
réglages privés a été construite pour ne pas avoir. L'acquittement dit ce que la
personne a compris, pas ce qui lui est arrivé ; il n'a pas à quitter son
porte-clés. Et si un jour le support en a besoin, ce qu'il lui faudra n'est pas
la case cochée mais l'état de la veille — `guardianId` nul, `NO_CONTACT` —, qui
est en base et qui ne ment pas.

**b. Qui l'efface :** **vous**, et nous ne toucherons jamais à cette clé. Nous
n'avons aucun moment naturel pour le faire — l'acceptation d'un consentement est
un événement du module contacts, pas du module veille — et un nettoyage écrit des
deux côtés est un nettoyage que personne ne relit. Vous ignorez la clé dès qu'un
contact accepté existe, comme vous le proposiez.

---

## 5. Vos questions, et un point de votre contrat à corriger

### 5.1 · Réponses courtes

| Votre point | Réponse |
|---|---|
| §1.5 — `seen-by-host` vaut-il déclaration ? | **Non**, et votre raisonnement est le bon : le code naîtrait d'un geste que la personne n'a pas fait, et notre objection du 02/09 reviendrait par la fenêtre |
| §3a — l'insigne dans un DTO public ? | **Jamais.** Il ne sort pas de la liste du créneau. Votre raisonnement sur la réputation est repris dans le code, à l'endroit du type |
| §3b — la page publique dirait-elle qui a validé ? | **Non**, et elle ne porte aujourd'hui aucun état d'arrivée. Si cela change un jour, ce sera sans l'auteur de la validation |
| §8 — `guardianId` servi `null` | **Confirmé.** C'est votre seul moyen fiable de savoir qu'il n'y avait personne à prévenir, et un test le garde |

### 5.2 · `guardianName` n'existe pas

Votre §4, ligne 8, nous demande de confirmer que `guardianId` **et
`guardianName`** sont servis `null`. `guardianName` n'apparaît nulle part dans
notre code : ni sur `WatchDto`, ni sur `WatchDetailDto`, zéro occurrence dans le
dépôt. Nous ne l'avons jamais servi.

Nous le signalons plutôt que de le laisser passer, parce que c'est exactement le
défaut que vous nous avez rapporté ce matin sur les notifications, dans l'autre
sens : un document qui décrit au présent un champ qui n'existe pas. Si votre
écran lit ce nom quelque part, il lit `null` depuis toujours. Dites-nous si vous
en avez besoin — nous l'ajouterons volontiers, avec le repli d'usage du module.

---

## 6. Vérification

Deux classes de tests neuves, **26 tests**, plus les 173 du périmètre veille et
créneaux qui passent inchangés.

Les quatre qui portent le lot :

| Test | Ce qu'il ferme |
|---|---|
| `arriveeDeclaree_neDoitPasEtreClasseePerdueEnChemin` | La collision du §2.5 — le défaut qui aurait rendu le parcours pire que l'absence de parcours |
| `sansGesteDeLhote_laValidationTombeTouteSeule` | Le garde-fou du §1.4, sans lequel nous n'aurions pas livré la validation par l'hôte |
| `avoirArmeSansDeclarer_doitEtreIndistinguableDeNavoirRienArme` | L'insigne comme détecteur |
| `aLecheance_lesRappelsPartent_puisLaVeilleSeReferme` | Qu'une veille sans contact n'envoie rien, ne crée pas de lien, et ne passe jamais `ESCALATED` |

**Les deux garde-fous ont été vérifiés par mutation** — c'est-à-dire en retirant
le correctif pour voir tomber les tests, plutôt qu'en les regardant passer :

- suspension de la boucle aller retirée → **2 échecs**, dont le classement en
  « perdu en chemin » ;
- branche sans contact de la boucle retour retirée → **4 échecs**, dont le
  passage en `ESCALATED`.

Migration `V99`. Elle touche les **trois** endroits qui énumèrent les états
terminaux — l'énumération Java, la contrainte de vocabulaire, l'index d'unicité
— parce que le 02/09 nous a appris ce que coûte leur divergence : un 500 rendu à
quelqu'un qui reprogramme une séance manquée. Un test réarme sur un créneau après
une clôture sans contact, pour que les trois listes ne puissent plus se séparer
en silence.

---

## 7. Récapitulatif

| # | Votre demande | État |
|---|---|---|
| 1 | `arrival/claim` + `arrivalClaimedAt` | **livré** |
| 2 | `arrivals/{participationId}/confirm`, 404 hors de ses créneaux | **livré** — et `202` sans effet quand il n'y a rien à valider (§3) |
| 3 | `code/claim`, une fois, au titulaire, jamais en notification | **livré** — le code est tiré à la remise (§2.3), `duressCode` s'y pose (§2.4) |
| 4 | `arrival: {state, claimedAt, confirmedAt}`, `NONE` indistinguable | **livré** |
| 5 | La bascule automatique, son échéance au DTO | **livré** — 15 min depuis `arrivalClaimedAt`, `arrivalAutoConfirmAt` |
| 6 | `guardianId` facultatif | **livré** |
| 7 | Aucun envoi, `NONE`, pas de lien, état ≠ `ESCALATED` | **livré** — `NO_CONTACT` |
| 8 | `guardianId` null — confirmation | **confirmé** ; `guardianName` n'existe pas (§5.2) |
| 9 | L'acquittement : les réglages privés, et qui efface | **répondu** — oui, et c'est vous qui effacez (§4.3) |
| 10 | L'insigne hors des DTO publics | **confirmé** |
| 11 | `seen-by-host` ne vaut pas déclaration | **confirmé** |
| 12 | Les préconditions au contrat OpenAPI | **livré** (§2.1) |
| — | **`seen-by-host` par `participationId`** | **recommandation, votre décision** (§3) |
| — | **`participants` en 404 plutôt qu'en 403** | **votre décision** (§3) |
