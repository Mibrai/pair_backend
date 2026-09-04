# Réponse — quitter un créneau n'est plus définitif

**Date :** 2026-09-04 · Réponse à `PROMPT_BACKEND_2026-09-04.md`

> **Corrigé.** Votre diagnostic était exact au mot près : le contrôle portait sur
> l'existence de la ligne de participation, pas sur son état. Une ligne, une
> condition — §1.
>
> **Votre §3 n'était pas un faux positif, et votre prudence était fondée.** Le
> `0` de départ était la valeur fausse ; `3` puis `2` sont la vérité. Nous vous
> expliquons pourquoi, et nous livrons la migration qui répare les lignes déjà
> figées — §3.
>
> **La liste d'attente ne porte pas le défaut** : elle réactivait déjà
> correctement. C'est même d'elle que vient la forme du correctif — §4.
>
> **Une addition que vous devez connaître :** un nouveau code d'erreur,
> `SLOT_ALREADY_WAITLISTED`. Vous pouvez l'ignorer sans rien casser, mais pas
> l'ignorer sans le savoir — §5.

---

## 1. La cause, et le correctif

Votre reproduction pointait la bonne ligne. La voici :

```java
if (participationRepository.existsByScheduleIdAndUserId(scheduleId, userId)) {
    throw new BusinessException(ErrorCode.SLOT_ALREADY_JOINED, ...);
}
```

`existsByScheduleIdAndUserId`, sans filtre de statut. Et comme `DELETE` pose
`WITHDRAWN` sur cette même ligne — la contrainte d'unicité `(schedule_id,
user_id)` lui interdit d'en créer une seconde —, la ligne survivait au départ et
continuait de valoir refus. **Se désinscrire était donc structurellement
irréversible**, et le message adressait « vous avez déjà rejoint » à quelqu'un
qui venait de partir.

Le contrôle porte désormais sur l'**état** :

- `CONFIRMED` → refus `SLOT_ALREADY_JOINED`. C'est le seul cas où cette phrase
  ait jamais été vraie, et il ne bouge pas ;
- `WAITLISTED` → refus, sous un code à lui (§5) ;
- tout le reste — `WITHDRAWN` en pratique — → la ligne est **réactivée**, et le
  `POST` rend `201`.

Réactiver plutôt que créer n'est pas un choix esthétique : la contrainte
d'unicité ne laisse pas d'alternative sans migration, et la file d'attente
appliquait déjà exactement ce geste.

**Quatre champs sont remis à zéro** à la réactivation, et chacun mentait à sa
façon s'il survivait : `withdrawn_at` (une inscription qui porte une date de
désistement se lit comme un désistement — dont dans le signal de fiabilité),
`waitlist_position` (se met en travers du suivant, index unique partiel V67),
`promoted_at` (raconterait une promotion qui n'a pas eu lieu), et
`attendance_closed_at` (retirerait la séance du signal de fiabilité pour
toujours, `findUnansweredToClose` exigeant qu'il soit nul).

Le message d'accompagnement, lui, n'est écrasé que si vous en envoyez un
nouveau : celui d'une inscription précédente vaut mieux que rien, et le faire
disparaître en silence effacerait un texte que l'hôte a peut-être déjà lu.

---

## 2. Votre §2.a : la capacité était déjà juste

Vous aviez raison de poser la question, la réponse est qu'il n'y avait rien à
faire. Le décompte des places est :

```sql
SELECT (SELECT COUNT(*) FROM user_programs      WHERE ... status = 'ACTIVE')
     + (SELECT COUNT(*) FROM slot_participations WHERE ... status = 'CONFIRMED')
```

`WITHDRAWN` n'y entrait pas, et `WAITLISTED` non plus — c'est même ce qui rend la
promotion possible. Un test le tient désormais explicitement : aller, retour,
aller, et le compteur revient à un, pas à deux.

**Vos §2.b sont entiers**, et chacun a son test : créneau complet, créneau passé,
inscription en cours. Nous n'avons rien assoupli d'autre que ce que vous
demandiez.

---

## 3. Votre §3 : vous aviez raison de ne pas l'affirmer, et raison de le signaler

Votre relevé — `0`, puis `3` après le join, puis `2` après le leave — s'explique
entièrement, et **la valeur fausse est celle de départ**.

`participant_count` est une colonne dénormalisée. Jusqu'au 02/09, deux chemins
d'écriture oubliaient de la rafraîchir et un troisième la remettait à zéro. Le
commit `1e13317` a réparé les chemins — un seul écrivain — mais **n'a rien fait
des lignes déjà fausses**. Elles le sont restées, et le restent jusqu'à la
prochaine écriture sur le créneau : le compteur ne se répare qu'en étant touché.

Votre séquence, relue :

| Lecture | Valeur | Ce qu'elle vaut |
|---|---|---|
| avant le join | `0` | périmée — écrite avant le 02/09 |
| après le join | `3` | **la vérité**, recalculée par votre inscription : deux inscrits que le `0` cachait, plus vous |
| après le leave | `2` | la vérité — les deux inscrits que le `0` cachait |

Aucun `join` ne compte pour trois. Le compteur n'est pas revenu à sa valeur de
départ parce que sa valeur de départ était fausse. Vos deux raisons de prudence
étaient donc les bonnes, et votre contre-observation le confirme : sur un créneau
auquel vous n'avez pas touché, la valeur est stable et s'accorde avec
`/slots/bounds` — parce que les deux lisent la même colonne périmée.

**Ce n'est pas cosmétique**, et c'est pourquoi nous ne nous contentons pas de
vous l'expliquer. Le filtre « masquer les créneaux complets » compare ce chiffre
à `maxParticipants` : figé trop bas, il laisse s'inscrire au-delà du plafond que
l'organisateur a lui-même posé.

**La migration `V100` remet la colonne en accord avec la réalité** sur tout
l'existant, avec la règle exacte du décompte, et resynchronise `OPEN`/`FULL` dans
la foulée — un créneau au compteur figé trop bas restait `OPEN` alors qu'il est
plein, c'est-à-dire exactement la porte que la correction devait refermer. Un
test tient la propriété d'auto-réparation : compteur forcé à une valeur fausse,
première écriture, la vérité revient.

---

## 4. Votre §2.c : la liste d'attente ne porte pas le défaut

Nous l'avons vérifiée, elle est saine — et pour une raison qui vaut d'être dite :
`joinWaitlist` **cherchait déjà la ligne existante et la réactivait** au lieu de
compter sa présence. C'est le geste exact qui manquait à `joinSlot`, écrit à
trois cents lignes de distance.

`POST /programs/{id}/join` non plus, d'ailleurs : son contrôle porte sur
`existsByUserIdAndProgramIdAndStatusActive`, filtré sur l'état.

Autrement dit le bon patron existait deux fois dans le dépôt, et `joinSlot` était
le seul des trois à compter des lignes plutôt qu'à lire des états. Le correctif
l'aligne sur ses deux voisins plutôt que d'inventer une troisième façon.

Si `POST /slots/{id}/waitlist` ne vous répondait pas sur vos créneaux de test,
c'est qu'il refuse un créneau qui n'est pas complet — la file n'existe que pour
les créneaux pleins.

---

## 5. Ce que nous ajoutons, et que vous devez savoir

**Un code d'erreur neuf : `SLOT_ALREADY_WAITLISTED`**, en `422`, servi quand
quelqu'un déjà en file appelle `POST /join`.

Ce cas était déjà refusé, et il le reste — la file existe pour ordonner l'entrée,
et convertir sa propre attente en inscription par ce chemin doublerait tous ceux
qui attendent devant. Ce qui change est la raison donnée : il rendait
`SLOT_ALREADY_JOINED`, donc la phrase « Vous avez déjà rejoint ce créneau »,
adressée à quelqu'un qui attendait précisément de pouvoir le rejoindre. Le même
défaut que celui de ce lot, un cran plus loin.

Il a fallu un code parce que le message est rendu depuis le catalogue de
traductions par `error.<CODE>`, jamais depuis l'exception : à code égal, message
égal. Traduit dans les trois langues.

**C'est additif** : un client qui ne connaît pas ce code affiche le message
rendu, qui est juste. Vous n'avez rien à faire — mais si vous branchez une
logique sur `SLOT_ALREADY_JOINED`, sachez qu'elle ne verra plus ce cas-là.

Le cas est rare et demande une place libre **et** quelqu'un encore en file : la
promotion saute un candidat en conflit d'agenda et le laisse dans la file. C'est
la situation que notre test reconstitue.

---

## 6. Vérification

`SlotRejoinIntegrationTest` — **10 tests, verts**. Le premier est votre
reproduction, geste pour geste. Les autres tiennent ce qui ne devait pas bouger
avec : trois allers-retours d'affilée (une correction qui n'en tiendrait qu'un
aurait seulement déplacé la porte d'un cran), la place rendue puis reprise sans
double comptage, l'inscription en cours toujours refusée, l'attente en cours
refusée sous son code, le créneau complet et le créneau passé toujours refusés
après un départ, les quatre champs effacés, une seule ligne en base après trois
allers-retours, et le compteur périmé qui se répare à la première écriture.

La suite complète a été relancée : **1064 tests, aucun échec**. Elle a d'abord
été rouge, et sur une classe qui n'a rien à voir avec le défaut — `SlotServiceTest`
simulait la méthode de dépôt que le correctif n'appelle plus. Le test disait donc
l'ancien contrat ; il dit maintenant le nouveau, et tient le défaut au niveau
unitaire en plus de l'intégration.

---

## 7. Récapitulatif

| # | Votre point | Réponse |
|---|---|---|
| 1 | `POST` après `DELETE` doit aboutir | **Corrigé** — le contrôle porte sur l'état, la ligne est réactivée |
| 2 | `WITHDRAWN` ne compte pas pour la capacité | **Déjà juste**, désormais tenu par un test |
| 3 | Les autres refus restent inchangés | **Confirmé**, un test chacun |
| 4 | La liste d'attente porte-t-elle le même défaut ? | **Non** — elle réactivait déjà ; `POST /programs/{id}/join` non plus |
| 5 | `participantCount` après un aller-retour | **Expliqué et réparé** : votre `0` était périmé, `V100` remet l'existant d'aplomb — §3 |
| — | *(addition)* `SLOT_ALREADY_WAITLISTED` | Nouveau code, additif, à connaître — §5 |

**Vos trois créneaux de test** — Basketball, Kickboxen, Salsa — se débloquent
d'eux-mêmes au déploiement : leurs participations `WITHDRAWN` cessent d'être un
motif de refus, il n'y a rien à nettoyer.
