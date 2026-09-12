# Deux correctifs de veille arrivent dans le même déploiement : la veille se referme quand la séance est annulée, et les rappels cessent de dire « Nouvelle notification »

**Date :** 2026-09-12
**Module :** [`tracabilite/`](.)
**Origine :** audit du 10/09 — fiches **P-BL-04** et **P-BL-11** du
`audit/PLAN_BACKEND_LOGIQUE_METIER_2026-09-11.md`, **Lot 0** (sous 48 h).
**Décisions actées :** `audit/DECISIONS_BACKEND_2026-09-12.md` — § `P-BL/D1` (option 1 : annuler un
créneau récurrent annule **la série**).

> **Ce document ne porte aucune demande urgente, et deux vérifications.**
>
> - **§1 — une veille armée ne survit plus à l'annulation de la séance.** Aujourd'hui elle survit,
>   et à l'échéance **un SMS part au proche** pour dire que la personne n'a pas confirmé son retour
>   d'une séance à laquelle elle n'est jamais allée. Vous verrez une veille `CLOSED` avec
>   `ABANDONED` : **un type que vous connaissez déjà**, et le même rendu qu'un désarmement. Rien ne
>   casse chez vous. Une seule chose à faire, et c'est un libellé.
> - **§2 — un nouveau type d'événement, `DEADLINE_SHIFTED`, arrive au lot suivant.** Nous le
>   livrons en comptant sur le fait que votre `WatchEventType.parse` rend `null` pour un type
>   inconnu. **C'est la vérification que nous vous demandons** : que votre chronologie écarte
>   proprement un `null` au lieu de tomber ou d'afficher un trou.
> - **§3 — les cinq push de veille qui partaient en « Nouvelle notification » vont porter un
>   texte.** C'est du serveur, et nous n'attendons rien de vous — **sauf** de vérifier que
>   `push_text.dart` et l'extension iOS n'écrasent pas un titre serveur qui devient utile.
> - **§4 — ce que nous ne promettons pas**, pour que personne ne l'attende.

---

## 1. La veille se referme quand la séance est annulée — livré au Lot 0

### 1.1 Le défaut, pour que le correctif soit lisible

L'échéance d'une veille est **figée à l'armement** et jamais redérivée du créneau — c'est un choix
assumé et documenté dans la migration qui a créé la table. Mais **rien**, côté serveur, ne
référençait `watches` : ni l'annulation d'un créneau, ni sa suppression, ni sa modification, ni le
rollover des récurrents. La veille continuait donc de courir sur une séance qui n'existait plus, et
la boucle retour envoyait ses rappels puis **escaladait vers le contact de confiance**.

C'est le préjudice que le Lot 0 ferme : une fausse alerte à un proche.

### 1.2 Ce que le serveur fait désormais

| Geste | Avant | Après |
|---|---|---|
| l'organisateur annule le créneau (`POST /slots/{id}/cancel`) | la veille reste `ARMED`/`ON_SITE`/… | les veilles **non terminales** du créneau passent **`CLOSED`**, avec `closedAt`, et un événement **`ABANDONED`** dans leur chronologie |
| l'organisateur supprime un créneau qui a des inscrits (`DELETE …/schedules/{id}`) | idem | idem — c'est la même branche d'annulation |
| une veille déjà **`ESCALATED`** sur un créneau annulé | — | **elle n'est pas touchée.** Une alerte déjà sortie reste à lever par la personne : elle a peut-être un vrai souci, et son contact ne doit pas rester sans nouvelle |
| armer une veille sur un créneau **annulé** | accepté | refusé : **404**, « Créneau introuvable. » — même forme que le reste de la méthode |
| armer une veille sur un créneau **passé** et non récurrent | accepté | refusé : **`WATCH_SLOT_ENDED`** |
| une annulation qui tombe **entre deux passages** de la boucle | un rappel partait | la boucle relit le statut du créneau et referme au lieu d'envoyer |

**Aucune migration, aucun changement de contrat.** Les états et les types d'événement utilisés
existent déjà et sont ceux que l'app 1.1.0+16 connaît : `CLOSED` est terminal, `ABANDONED` est dans
`safety_watch_models.dart`. **Pour vous, une veille refermée par annulation est indiscernable d'un
désarmement** — c'est voulu, et c'est ce qui rend ce correctif livrable sans nouvelle version d'app.

### 1.3 Ce que nous vous demandons : un libellé

Le rendu « désarmement » est correct mais muet. Quand une veille est **`CLOSED`** *et* que son
créneau est **annulé**, la personne mérite de savoir pourquoi sa veille s'est refermée toute seule.
La fiche demande un libellé de la forme :

> **« séance annulée, veille refermée »**

**La formulation est la vôtre** — c'est votre vocabulaire, et vous avez déjà tranché « veille »
contre « suivi ». Nous ne demandons que la distinction : ne pas laisser croire à un désarmement que
la personne aurait fait elle-même.

Les deux informations dont vous avez besoin sont déjà au contrat : l'état de la veille et le statut
du créneau. Le statut du créneau devient **explicitement lisible sur la fiche et dans « Mes
créneaux »** avec **P-BL-08**, au Lot 1 — si vous préférez attendre cette fiche pour brancher le
libellé proprement, c'est raisonnable, et le défaut du Lot 0 est déjà fermé sans vous.

### 1.4 Reprise des données existantes, de notre côté

Les veilles **déjà** armées sur des séances déjà annulées sont refermées à la main, par un runbook
([`docs/runbooks/RUNBOOK_VEILLES_ARMEES_SEANCES_ANNULEES_2026-09-12.md`](../../docs/runbooks/RUNBOOK_VEILLES_ARMEES_SEANCES_ANNULEES_2026-09-12.md)),
**relu à deux**, et **sans rien envoyer à personne** : l'annulation a déjà été annoncée en son
temps, et prévenir un proche maintenant l'alarmerait pour rien.

Conséquence pour vous, et c'est la seule : **des veilles vont passer à `CLOSED` sans qu'aucun geste
de l'utilisateur ne l'explique**, dans la fenêtre de ce runbook. Si vous cachez l'état d'une veille,
c'est le moment de vérifier que vous relisez la liste plutôt que de tenir un état local.

---

## 2. `DEADLINE_SHIFTED` — le nouvel événement, et la vérification qu'il demande

**Au Lot 1**, pas au Lot 0 : quand l'organisateur **déplace** un créneau, les veilles non terminales
de ce créneau verront leur `occurrenceStartsAt`, leur `outboundBaseAt` et leur `deadlineAt` décalés
d'autant. C'est la suite logique de §1 : aujourd'hui, déplacer une séance de deux heures laisse la
veille avec l'ancienne échéance, donc un rappel **pendant** la séance.

Ce décalage posera dans la chronologie un événement d'un **type neuf** :

```
DEADLINE_SHIFTED
```

### 2.1 Pourquoi nous vous le disons avant de le livrer

Nous nous appuyons sur une lecture de votre code, et nous préférons qu'elle soit confirmée par vous
plutôt que supposée par nous : `WatchEventType.parse` **rend `null`** pour un type qu'il ne connaît
pas (`safety_watch_models.dart`, autour de la ligne 1068). Nous en concluons que le nouveau type est
**toléré** par l'app 1.1.0+16 sans mise à jour.

**La vérification que nous vous demandons, et c'est la seule chose que ce document attende de
vous** : que votre chronologie **écarte proprement un événement rendu `null`** — qu'elle le saute,
sans tomber, sans rendre une ligne vide, et sans casser l'ordre des autres.

Si ce n'est pas le cas, **dites-le et nous n'utiliserons pas de type neuf** : la fiche prévoit
explicitement le repli — *« sinon utiliser un événement existant »*. C'est une décision qui vous
appartient, parce que c'est votre écran qui la paie.

> À rapprocher de **P-MA-10** de votre plan d'architecture (« un type de notification inconnu
> devient `system`, sans aucun signal ») : le principe est le même, et si vous outillez le signal
> d'un type inconnu, ce cas-ci en sera le premier client.

### 2.2 Un point de schéma, réglé

La fiche laissait une question ouverte : `watch_events.type` porte-t-elle une contrainte `CHECK` qui
demanderait une migration ? **Non.** La colonne est `VARCHAR(40) NOT NULL`, sans contrainte de
vocabulaire (`V85__watches_et_chronologie.sql`). `DEADLINE_SHIFTED` tient, et **aucune migration
n'est nécessaire**.

### 2.3 Ce qui prévient la personne, et ce qui ne la prévient pas

`DEADLINE_SHIFTED` est un **fait de chronologie**, pas une notification. Ce qui prévient la personne
du déplacement, c'est le `SCHEDULE_CHANGED` de **P-BL-06** (Lot 1), dont le payload portera
`watchShifted: true` quand une veille a été décalée. Si la nouvelle échéance est déjà passée, la
veille est **refermée** comme au §1.2 plutôt que décalée.

---

## 3. Les push de veille vont porter un texte — P-BL-11

### 3.1 Ce qui partait, et ce qui va partir

`buildTitle` retombait sur `push.generic.title` pour tout type sans `case`. Seul
`WATCH_ARRIVAL_CONFIRMED` en avait un. **Cinq types réellement émis partaient donc en « Nouvelle
notification »** :

| Type | Ce qu'il annonce |
|---|---|
| `WATCH_RETURN_REMINDER` | le rappel de confirmer son retour, **avant** l'escalade |
| `WATCH_ARRIVAL_PROMPT` | la demande de confirmer son arrivée |
| `WATCH_GUARDIAN_ALERT` | l'alerte au contact de confiance |
| `WATCH_LOST_ORGANIZER` | la non-arrivée signalée à l'organisateur |
| `GUARDIAN_CONSENT_REQUEST` | la proposition d'être le contact de quelqu'un |

C'est la raison pour laquelle les trois rappels avant l'escalade passent inaperçus : personne
n'ouvre « Nouvelle notification ». Les clés arrivent dans les **trois langues**, au tutoiement côté
français — cohérent avec l'app, et avec **P-MU-14** de votre plan UX.

Deux contraintes que nous nous imposons, et qui vous concernent :

- **aucun lieu dans ces textes.** Ils s'affichent sur l'écran verrouillé (**P-MS-10**) ;
- `deadlineAt` est formaté **dans le fuseau de l'appareil**, comme les autres heures.

Un test déclaratif ferme la porte derrière nous : tout type **émis** qui retomberait sur le titre
générique fait échouer la suite, dans les trois locales.

### 3.2 La vérification que nous vous demandons

Nous lisons dans nos notes que **iOS recompose déjà via ses extensions**, en utilisant le texte
serveur comme repli, et qu'**Android affiche directement** le texte reçu. Donc, sur le papier, rien
à faire chez vous.

Mais nous venons de rendre utile un titre qui ne l'était pas, et **c'est précisément le cas où un
repli devient un écrasement** : une extension qui remplace systématiquement un titre serveur
« générique » par un titre composé localement remplacera désormais un **bon** titre.

**À vérifier** : que `push_text.dart` et l'extension iOS **n'écrasent pas** un titre serveur
désormais utile pour ces cinq types. Si votre composition locale est meilleure que la nôtre, gardez
la vôtre — nous voulons juste que le choix soit conscient et non hérité de l'époque où notre titre
ne disait rien.

### 3.3 Le lien qu'il ne faut pas perdre de vue

**P-MU-01** de votre plan UX : l'autorisation des notifications n'est demandée que dans Réglages.
**Sans elle, aucun de ces cinq textes n'arrive** — pas plus que « Nouvelle notification ».
Améliorer le texte d'un push qui ne part pas ne sert à rien : les deux fiches se tiennent, et la
veille est le cas où la demande d'autorisation se justifie le mieux.

---

## 4. Ce que nous ne promettons pas

Pour que rien ne soit attendu qui ne soit prévu :

- **Les textes de §3 ne sont pas définitifs.** La fiche dit « formulations à relire par le
  produit », et personne ne les a relues. Un rappel de veille trop anxiogène fait désinstaller
  l'app : si vous avez un avis, c'est le moment. Ne codez rien qui dépende du mot exact.
- **Pas d'état « annulée » pour une veille.** Nous réutilisons `CLOSED` + `ABANDONED`. Il n'y aura
  pas de nouvel état terminal, et donc rien de neuf à gérer dans vos `switch` d'état.
- **`DEADLINE_SHIFTED` est au Lot 1, pas au Lot 0**, et il peut ne jamais arriver si votre réponse
  au §2.1 est négative.
- **Rien n'est envoyé pendant la reprise de §1.4.** Ni push, ni SMS, ni e-mail. Si vous voyez passer
  une notification de veille pendant cette fenêtre, elle ne vient pas de la reprise, et il faut
  nous le dire.
- **Le plafond du report de veille** (`P-BL/D8` : 3 reports ou 2 h cumulées, puis escalade, et une
  mention « reporté N fois » dans le message au contact) est **décidé mais pas livré** — Lot 2, et
  il attend l'avis du produit. Aujourd'hui « Reporter » reste illimité.
- **Les types `WATCH_*` ne passent pas encore par l'outbox.** C'est `P-BA/D5` option C, en suite du
  Lot 2. D'ici là, une notification de veille en file est perdue si le conteneur redémarre — les SMS
  et e-mails aux proches, eux, passent **déjà** par l'outbox et sont durables.
