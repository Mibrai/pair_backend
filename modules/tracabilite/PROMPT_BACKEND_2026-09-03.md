# La présence se valide par l'hôte, et une séance peut démarrer sans contact d'urgence

**Date :** 2026-09-03
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-SEPTIES.md`

> **Deux décisions produit du 03/09**, et la seconde vous demandera de céder sur
> une règle que vous avez posée vous-mêmes — nous le savons, et nous expliquons
> pourquoi nous le demandons quand même.
>
> **1. L'arrivée devient à deux temps : la personne déclare, l'hôte valide.**
> Le code de retour naît à la **validation**, pas à la déclaration. C'est très
> exactement ce que vous avez refusé le 02/09 en refusant le code de séance, et
> votre raison était bonne. Nous vous demandons donc la même chose sous une
> forme qui la désarme : une **validation automatique au bout d'un délai**, pour
> que personne ne dépende jamais du bon vouloir d'un tiers — §1.
>
> **2. `POST /watches` doit accepter l'absence de `guardianId`.** « Une veille
> qui ne prévient personne n'est pas une veille » : c'est votre phrase, et elle
> est juste. Elle a pourtant un coût que nous mesurons maintenant — le premier
> soir, personne n'a de contact accepté, et le bouton est éteint. Nous
> demandons un armement sans contact, **avec acquittement explicite** — §2.
>
> **Rien ici n'est bloquant au sens où l'app serait cassée** : nous livrons
> aujourd'hui ce qui ne dépend pas de vous, et les deux chantiers restent
> derrière un drapeau éteint jusqu'à votre réponse — §5.

---

## 0. Le parcours voulu, pour situer

Quatre temps. Les deux premiers existent, les deux derniers non.

1. **« Démarrer la séance »** — la personne arme sa veille depuis la fiche de
   créneau, en un tap, sur le contact désigné dans ses réglages (le `role`
   `PRIMARY` que vous avez livré le 02/09 : merci, c'est exactement ce qu'il
   fallait). Si elle n'en a aucun, elle démarre quand même — §2 ;
2. **« J'y suis »** — elle déclare son arrivée. Aujourd'hui ce geste fait naître
   le code de retour ; demain il ne fait plus que **déclarer** ;
3. **l'hôte valide** — dans sa liste d'inscrits, un bouton apparaît devant le
   nom de la personne qui vient de déclarer. Il le touche, le bouton disparaît,
   un insigne « présence validée » prend sa place ;
4. **le code de retour naît alors**, sur le téléphone de la personne, et la
   veille retour commence à courir.

Le sens de ce changement n'est pas le contrôle : c'est que **la présence
devienne un fait partagé** plutôt qu'une déclaration solitaire. L'insigne est
vu par l'hôte, qui sait qui est là ; la personne, elle, y gagne que son retour
soit surveillé à partir du moment où quelqu'un l'a effectivement vue arriver.

---

## 1. L'arrivée à deux temps

### 1.1 · Deux verbes plutôt qu'un changement de comportement

`POST /watches/{id}/arrival` rend aujourd'hui le code de retour en clair, une
seule fois, et **toutes les app installées comptent sur cette réponse** : elles
l'écrivent au Trousseau et n'ont aucun autre moyen de l'obtenir. Si ce verbe
cesse de le rendre, chaque téléphone qui n'a pas été mis à jour valide une
arrivée et se retrouve sans code — donc sans clôture possible, donc avec une
alerte à l'échéance. Ne le changez pas.

Ce que nous demandons est **à côté**, pas à la place :

```
POST /api/watches/{id}/arrival/claim          → 202, corps vide
     { "duressCode": "SESAME" }               // facultatif, mêmes règles qu'aujourd'hui
```

La personne déclare son arrivée. Aucun code n'est tiré. La veille **reste dans
son état** — `ARMED` ou `EN_ROUTE` — et le `WatchDto` porte un champ de plus :

```
"arrivalClaimedAt": "2026-09-03T19:42:11Z"    // null tant qu'elle n'a pas déclaré
```

**Un champ et pas un état**, pour la raison que vous nous avez apprise le 02/09
avec `NOT_ARRIVED` : `WatchState.parse` rend `ARMED` sur tout état inconnu, donc
un état neuf ferait retomber les app anciennes sur « armée, en attente
d'arrivée » — ce qui est faux dès qu'une déclaration existe, et leur ferait
proposer un « J'y suis » que le serveur refuserait. Un champ inconnu, lui, est
simplement ignoré.

Deux préconditions, que nous vous demandons de mettre au contrat OpenAPI comme
vous l'avez fait pour les autres verbes :

- `claim` n'est accepté que sur `ARMED` / `EN_ROUTE` — les deux états où
  l'arrivée est encore attendue ;
- un second `claim` sur une veille qui en porte déjà un est un **`409` d'état**,
  pas une erreur : la personne a touché deux fois.

### 1.2 · La validation, et où naît le code

```
POST /api/schedules/{scheduleId}/arrivals/{participationId}/confirm   → 202
```

Appelable **par l'organisateur du créneau seul**. `404` — et non `403` — quand
le créneau n'est pas le sien, comme pour `seen-by-host` : nous avons gardé ce
silence, il est bon.

Effet : `arrivalConfirmedAt` est posé, la veille passe `ON_SITE`, l'échéance de
retour commence à courir, et **le code de retour est tiré**.

Reste la question qui décide de la forme de tout le reste : **comment ce code
arrive-t-il sur le téléphone de la personne ?** Elle n'est plus dans la requête
qui le fait naître.

**Pas dans une notification.** Une charge APNs est écrite en clair sur l'écran
verrouillé, conservée dans le centre de notifications, recopiée dans les
journaux du système et visible sur une capture d'écran. Le code de retour est le
secret qui permet de dire « je suis rentrée » et **rien d'autre ne le remplace** ;
le poser sur un écran verrouillé le rendrait lisible par la personne même dont
le code de contrainte existe pour se protéger. Envoyez la notification — « ta
présence est validée » — mais sans le code.

**Une lecture à un seul coup, par la personne elle-même :**

```
POST /api/watches/{id}/code/claim    → 200 { "code": "PQ3TV" }
```

- réservé au titulaire de la veille (`404` sinon) ;
- `409` tant que `arrivalConfirmedAt` est nul — il n'y a rien à donner ;
- **`409` au second appel** : le code n'est servi qu'une fois, comme
  aujourd'hui la réponse d'`arrival`. C'est ce qui garde vraie la phrase que
  toute l'app répète — « ce code n'existe en clair qu'une seule fois, sur ce
  téléphone-là ».

Nous l'appellerons au réveil de la notification et à l'ouverture de l'écran de
veille. Si vous voyez une forme meilleure — un `GET` à usage unique, un jeton
d'échange — elle nous va, du moment que le clair ne passe pas par une
notification et ne soit pas rejouable.

### 1.3 · L'insigne : un champ sur la liste des inscrits

Le bouton et l'insigne vivent **dans la ligne du participant**, chez l'hôte.
`GET /schedules/{id}/participants` ne porte rien qui les alimente — la seule
chose qui s'en approche est `pending-arrivals`, qui est une autre liste. Nous
demandons donc, sur chaque inscrit :

```
"arrival": {
  "state": "NONE" | "CLAIMED" | "CONFIRMED",
  "claimedAt":  "…",   // null en NONE
  "confirmedAt": "…"   // null hors CONFIRMED
}
```

`participationId` est déjà là : c'est lui que vise le verbe du §1.2, et non le
`watchId` — l'hôte n'a aucune raison de manipuler l'identifiant d'une veille.

**Une précaution de confidentialité, et elle est structurante.** `NONE` doit
signifier *exactement la même chose* pour quelqu'un qui n'a pas armé de veille
et pour quelqu'un qui en a armé une sans déclarer son arrivée. Sans cela,
l'insigne devient un détecteur : l'hôte apprendrait qui se protège, ce que
personne n'a accepté de lui dire. C'est aussi la raison pour laquelle nous
préférons ce champ à l'élargissement de `pending-arrivals` — cette liste-là,
elle, ne contient que des gens qui ont armé.

Et l'insigne ne doit rien porter d'autre : ni heure d'arrivée à la minute, ni
retard, ni motif. `CONFIRMED` suffit à l'afficher. Notre écran est écrit pour ne
**pas pouvoir** montrer plus, comme la section A7 — c'est le type qui tient la
frontière, pas la discipline de celui qui l'écrira après nous.

### 1.4 · L'hôte qui ne valide jamais — la seule question que nous ne pouvons pas trancher seuls

Vous avez refusé le code de séance le 02/09 avec cette raison, que nous citons
parce qu'elle vaut toujours :

> un code détenu par l'organisateur ferait dépendre la naissance du code de
> retour d'un tiers, et en ferait un point de pression — qui refuse de le
> donner, ou le donne sous condition, tient la personne.

Nous ne l'avons pas oubliée. Elle s'applique mot pour mot à ce que nous
demandons ici, et c'est pourquoi nous demandons **avec** son garde-fou :

**une validation automatique au bout d'un délai.** Passé ce délai, une arrivée
`CLAIMED` devient `CONFIRMED` sans que personne n'ait rien touché, le code naît,
la veille retour part. L'hôte gagne du temps sur la validation ; il n'a jamais
de pouvoir sur elle.

Ce que ça change pour la personne : dans le pire des cas — un hôte absent,
distrait, ou hostile — elle attend le délai et sa soirée est protégée quand
même. Sans cette bascule, un hôte qui ne touche rien laisse quelqu'un sans code
de retour toute la nuit, c'est-à-dire **exactement le contraire** de ce que ce
module existe pour faire.

Trois choses à trancher, et elles sont chez vous :

1. **le délai.** Nous proposons 15 minutes, la même granularité que vos relances
   d'arrivée. Nous n'y tenons pas ; dites-nous ce que votre minuteur sait faire ;
2. **d'où il compte** — de `arrivalClaimedAt`, à notre sens : c'est le geste de
   la personne, et il ne doit pas dépendre de l'heure de début de séance, qu'un
   hôte peut modifier ;
3. **ce que la personne en sait.** Nous voulons l'écrire à l'écran avant le
   geste : « ton hôte peut valider ta présence ; sans réponse de sa part, elle
   sera validée dans 15 min ». Pour ça il nous faut soit le délai comme
   constante de contrat, soit une échéance calculée dans le `WatchDto`
   (`arrivalAutoConfirmAt`) — la seconde est meilleure, pour la même raison que
   `deadlineAt` : nous affichons votre heure, jamais la nôtre.

Si vous refusez la bascule automatique, dites-le franchement : nous ne
livrerons pas la validation par l'hôte. Le parcours entier tient à ce
garde-fou-là, pas l'inverse.

### 1.5 · Ce que deviennent `seen-by-host` et `pending-arrivals`

Ils gardent leur rôle, et il ne recouvre pas le nouveau :

- `pending-arrivals` liste les inscrits qui n'ont **pas** déclaré leur arrivée ;
  le bouton de validation, lui, n'apparaît **qu'après** une déclaration. Les
  deux populations sont disjointes par construction — un même nom ne peut pas
  porter les deux gestes à la fois, et nous n'avons donc pas à choisir lequel
  montrer ;
- `seen-by-host` (« je la vois, elle est là ») repousse la relance de 15 min et
  ne valide rien. Il reste utile pour quelqu'un qui est **visiblement là mais
  n'a rien déclaré** — le téléphone au fond du sac. Nous le gardons.

Une question, quand même : est-ce que `seen-by-host` doit **valoir déclaration**
dans le nouveau parcours ? Notre réponse est **non** — le code de retour naîtrait
alors d'un geste que la personne n'a pas fait, et votre objection du 02/09
reviendrait par la fenêtre. Nous vous la posons parce que quelqu'un la posera un
jour.

---

## 2. Démarrer une séance sans contact d'urgence

### 2.1 · `guardianId` facultatif sur `POST /watches`

C'est la demande, et elle est courte : `{scheduleId, guardianId?,
backupGuardianId?, deadlineAt?}`.

**Pourquoi nous vous demandons de céder sur votre propre règle.** « Une veille
qui ne prévient personne n'est pas une veille » est vrai *du point de vue de
l'alerte*. Voici ce que ça donne à l'écran, aujourd'hui : quelqu'un s'inscrit à
sa première séance, ouvre la fiche, trouve un bouton « Démarrer la séance » —
et il est **éteint**, parce qu'il faut d'abord désigner un proche, l'inviter,
et attendre qu'il accepte. Le premier soir, celui où on sort avec des inconnus
pour la première fois, la protection est exactement celle de quelqu'un qui n'a
rien : aucune.

Or une veille sans contact n'est pas vide. Il reste :

- les relances — c'est ce qui fait qu'on n'oublie pas de dire qu'on est rentré ;
- le journal, donc une trace horodatée de la soirée ;
- l'insigne de présence du §1, qui vaut pour l'hôte ;
- et surtout : le jour où la personne désigne un contact, **tout le reste est
  déjà en place et déjà compris**. Une fonctionnalité qu'on découvre le soir où
  on en a besoin n'est pas une fonctionnalité.

### 2.2 · Ce que fait une veille qui n'a personne à prévenir

C'est ici que nous avons besoin de vous, plus que sur le champ facultatif :

- **aucun envoi sortant, jamais.** Ni ②, ni ④, ni ⑥, ni ⑦. `alertDelivery` reste
  `NONE` de bout en bout ;
- **pas de lien public.** Il naît à l'alerte, il n'y a pas d'alerte ;
- **et surtout : pas `ESCALATED`.** Ce mot veut dire « un message est parti à un
  tiers » dans tout notre code — c'est lui que
  [`SafetyWatch.guardianAlerted`] lit pour afficher le bandeau corail
  « message d'urgence envoyé ». Sur une veille sans contact, ce bandeau serait
  la phrase la plus fausse que l'app puisse écrire.

Ce que nous vous demandons est donc un **état terminal qui ne prétend rien**,
sur le modèle de `NOT_ARRIVED` du 02/09 : à l'échéance dépassée sans réponse, la
veille se referme, le journal note « pas de réponse, personne à prévenir », et
c'est fini. Le nom nous est égal ; ce qui compte est qu'aucune boucle ne le
balaie et qu'il ne se confonde pas avec une escalade.

Point de contrat à confirmer explicitement, parce que nous allons nous appuyer
dessus : **`guardianId` et `guardianName` sont servis `null`** sur
`GET /watches/{id}` et `/watches/active` pour ces veilles-là. C'est notre seul
moyen de savoir qu'il n'y avait personne à prévenir — et nous avons appris le
02/09 à ne **pas** déduire un fait serveur d'une contrainte d'interface que nous
venons de poser : les veilles déjà en base ne la respectent pas.

### 2.3 · L'acquittement — vos réglages privés suffisent, et c'est vous qui nous le direz

La personne qui démarre sans contact cochera une case : « je sais que personne
ne sera prévenu ». Cette case ne doit être posée **qu'une fois** — la reposer à
chaque départ transformerait un choix assumé en reproche hebdomadaire, et le
premier réflexe serait de ne plus armer du tout.

Elle ne peut pas vivre sur l'appareil : un changement de téléphone la reposerait
à quelqu'un qui l'a déjà tranchée. C'est l'argument que vous nous avez accordé
pour le `role` du SEPTIES, et il vaut ici mot pour mot.

**Nous n'avons donc rien de neuf à vous demander** : les réglages privés du
02/09 (`GET|PUT|DELETE /users/me/preferences/{key}`) font exactement ce qu'il
faut. Nous y écrirons une valeur opaque sous une clé à nous, et rien d'autre.
C'est déjà là où vit l'étoile des amis.

Deux choses, quand même, et la première est une vraie question :

**a. Est-ce que ça vous va, pour cet acquittement-là ?** Nous vous avons demandé
cet espace pour ranger un réglage d'affichage. Celui-ci est d'une autre nature :
il a une conséquence de sécurité — une veille qui n'a personne à prévenir — et
vous pourriez vouloir le voir depuis le support, ou l'auditer, ce qu'une valeur
opaque interdit par construction. Si vous préférez un champ nommé sur le profil,
dites-le : nous prendrons le champ. Nous n'avons pas d'avis, et vous êtes mieux
placés que nous pour trancher.

**b. Qui l'efface.** Cet acquittement doit cesser de valoir dès que la personne
a un contact d'urgence utilisable — consentement accepté. Nous savons le faire
côté app, puisque nous lisons déjà la liste. Dites-nous seulement lequel des
deux le fait : vous effacez la clé, ou vous la laissez et nous l'ignorons tant
qu'un contact existe. L'un ou l'autre, jamais les deux — un nettoyage fait des
deux côtés est un nettoyage que personne ne relit.

## 3. Deux précautions, à trancher par vous

**a. L'insigne de présence n'est pas une statistique.** Il dit « vue à cette
séance », vu par l'hôte de cette séance. S'il devient un compteur sur un profil
public — « 34 présences validées » — il devient une réputation, donc une
pression à valider, donc un hôte qu'on ne contrarie pas. Nous ne l'afficherons
nulle part ailleurs que dans la liste du créneau ; ne l'exposez pas dans un DTO
public.

**b. La personne doit savoir que son hôte peut valider sa présence** avant de
déclarer, pas après. C'est la contrepartie du §1 : un tiers acquiert un geste
sur son parcours. Nous l'écrirons sur l'écran d'arrivée. Si votre page publique
de statut expose un jour l'état d'arrivée, elle ne doit **pas** dire qui l'a
validée.

---

## 4. Récapitulatif

| # | Demande | Nature |
|---|---|---|
| 1 | `POST /watches/{id}/arrival/claim` — déclarer sans tirer de code, + `arrivalClaimedAt` sur le `WatchDto` | demande |
| 2 | `POST /schedules/{id}/arrivals/{participationId}/confirm` — la validation par l'hôte, `404` hors de ses créneaux | demande |
| 3 | `POST /watches/{id}/code/claim` — le code en clair, une fois, au titulaire seul ; **jamais dans une notification** | demande |
| 4 | `arrival: {state, claimedAt, confirmedAt}` sur `GET /schedules/{id}/participants`, avec `NONE` indistinguable de « n'a pas armé » | demande |
| 5 | **La validation automatique au bout d'un délai**, son échéance dans le DTO, et le délai au contrat | demande — le §1 entier en dépend |
| 6 | `guardianId` facultatif sur `POST /watches` | demande |
| 7 | Une veille sans contact : aucun envoi, `alertDelivery: NONE`, pas de lien public, **et un état terminal qui n'est pas `ESCALATED`** | demande |
| 8 | `guardianId`/`guardianName` servis `null` sur ces veilles — confirmation de contrat | confirmation |
| 9 | L'acquittement « je continue sans contact » : vos réglages privés du 02/09 suffisent — reste à dire si ça vous va pour une donnée de sécurité, et **qui l'efface** | question |
| 10 | L'insigne absent des DTO publics ; la page publique ne dit pas qui a validé | précaution |
| 11 | `seen-by-host` ne vaut **pas** déclaration d'arrivée — confirmation | confirmation |
| 12 | Les préconditions d'état des trois verbes neufs au contrat OpenAPI, comme le 02/09 | demande |

---

## 5. Ce que nous livrons aujourd'hui, sans attendre

Pour que vous sachiez ce qui bouge en production pendant que vous lisez :

- **le bouton s'appelle « Démarrer la séance »** au lieu de « Prévenir un
  proche ». Le partage simple garde son libellé, et les deux ne partagent plus
  la même clé de traduction — ils ne partageaient qu'elle, et ça les faisait
  bouger ensemble ;
- **une bande dans l'app demande « Tu y es ? »** dès que votre relance d'arrivée
  a commencé (état `EN_ROUTE`). Jusqu'ici cette question n'existait que dans la
  notification poussée : qui l'avait balayée n'avait plus aucun chemin vers
  l'écran d'arrivée. Aucune horloge locale — c'est votre état qui l'ouvre et la
  ferme ;
- **l'armement sans contact est écrit et testé, derrière un drapeau éteint** —
  un seul mot à changer le jour où `guardianId` devient facultatif ;
- **la validation par l'hôte, elle, n'est pas écrite du tout**, et ce n'est pas
  un choix de prudence : il n'y a aucun champ à lire. Un écran qui l'imiterait
  localement afficherait un insigne que rien ne soutient — c'est le défaut que
  nous avons payé avec l'onglet « Trouver » du SEPTIES, et nous ne le
  recommencerons pas.

Le seul point de ce document qui ne peut pas attendre est le **§1.4** : sans la
bascule automatique, nous ne livrerons pas la validation par l'hôte, et nous
préférons vous le dire maintenant plutôt qu'après votre lot.
