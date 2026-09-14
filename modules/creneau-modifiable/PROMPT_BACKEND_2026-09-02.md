# Un créneau se modifie enfin — et il faut que ses inscrits l'apprennent

**Date :** 2026-09-02
**Module :** `creneau-modifiable`

> **Nous venons d'écrire l'écran qui manquait** : l'auteur d'un créneau peut
> modifier toutes ses informations, et cela passe par votre
> `PUT /api/programs/{programId}/schedules/{scheduleId}`, qui existait depuis
> toujours et que personne n'appelait. Rien à faire de votre côté pour ça.
>
> **Il manque la moitié qui vous appartient : personne n'est prévenu.**
> `SCHEDULE_CHANGED` est dans votre énumération et **n'est émis par personne**
> — vous nous l'aviez écrit vous-mêmes en août. Tant qu'il ne part pas, un
> auteur peut décaler sa séance de deux heures et les six inscrits le
> découvriront sur place.
>
> S'y ajoutent **trois questions de contrat** que la modification pose et que la
> création ne posait pas : la sémantique du `PUT`, un champ qui s'écrit sans
> jamais se relire, et une clé que nous envoyons depuis toujours sans savoir si
> vous l'honorez.

---

## 1. La demande principale — émettre `SCHEDULE_CHANGED`

### Ce qui existe déjà des deux côtés

Le type est dans votre énumération, il traverse notre parseur, il a sa ligne de
préférences dans nos réglages, son gabarit d'affichage, et **son routage** : un
tap sur la notification — bannière poussée comme ligne relue dans la liste —
ouvre `/slots/{scheduleId}`, la fiche du créneau concerné. C'est vérifié par un
test qui parcourt nos vingt-neuf types.

Autrement dit : **le jour où vous l'émettez, tout le chemin client fonctionne
sans une ligne de plus.** Nous n'avons rien à livrer pour l'accueillir.

### Ce qui manque

`REPONSE_BACKEND_LOT7_2026-08.md`, §2 de vos réponses :

> « `SCHEDULE_CHANGED` n'est **jamais émis** par le code applicatif (valeur de
> seed). Si l'exigence produit dit qu'un abonné est notifié du changement, c'est
> une demande nouvelle — chiffrable, dites-nous. »

**Nous vous le disons : oui.** L'exigence produit est celle-ci, dans les mots de
qui l'a posée le 02/09 :

> « Les participants déjà inscrits à ce créneau sont notifiés dans meetDo des
> changements sur le créneau en question. »

### La forme demandée

Sur un `PUT /programs/{programId}/schedules/{scheduleId}` qui **change quelque
chose**, notifier les participants inscrits de ce créneau — et eux seuls.

- **Le destinataire, c'est l'inscrit du créneau**, pas l'abonné du programme :
  quelqu'un qui suit un programme sans être inscrit à cette séance n'a rien à
  faire d'un changement d'adresse. Ne pas notifier l'auteur de son propre geste.
- **Type `SCHEDULE_CHANGED`**, avec le `payload` de séance que vous servez
  déjà : `programId`, `programTitle`, `activityId`, `activityName`,
  `categoryId`, `categoryColorRamp`, **`scheduleId`**, `placeName`, `sessionAt`.
  `scheduleId` est celui qui compte — c'est lui qui ouvre la bonne fiche, et
  sans lui la notification retombe sur le programme, qui liste toutes les
  séances et laisse retrouver soi-même celle dont on parle.
- **Une notification par modification, pas une par champ.** Un auteur qui
  corrige l'adresse *et* l'heure d'un même geste ne doit produire qu'une ligne.
- **Rien si rien n'a changé.** Un `PUT` idempotent — ouvrir l'écran et
  enregistrer sans toucher à un champ — ne doit réveiller personne. C'est le cas
  le plus fréquent d'un formulaire pré-rempli, et le plus facile à oublier.
- **Un `collapse-id` stable par créneau** sur la charge APNs, comme vous l'avez
  fait pour la veille retour (`watch-<watchId>`) : trois corrections d'affilée
  doivent **remplacer** la bannière, pas en empiler trois.

### La question de conception qui vous revient

Faut-il notifier pour **tout** changement, ou seulement pour ceux qui déplacent
quelqu'un ? Notre avis, et il ne nous appartient qu'à moitié :

- **oui, sans hésiter** : `startsAt`, `endsAt`, `placeName`, `addressPublic`,
  `lat`/`lng`, `placeType`. Ce sont les champs qui décident si quelqu'un arrive
  au bon endroit à la bonne heure ;
- **probablement pas** : `welcomeNote`, `accessibilityTags`, `maxParticipants`,
  `isOpenToPartners`. Corriger une faute dans un mot d'accueil ne vaut pas une
  notification, et le premier auteur qui en produira trois d'affilée fera couper
  la ligne dans les réglages — après quoi les changements d'heure ne passeront
  plus non plus.

Si distinguer les deux familles vous coûte cher, notifiez sur les six premiers
seulement et laissez les autres muets : c'est le sous-ensemble qui porte la
valeur, et un sur-ensemble bruyant vaut moins que lui.

---

## 2. Question de contrat — le `PUT` fusionne-t-il, ou remplace-t-il ?

`UpdateScheduleRequest` ne marque **aucun** champ obligatoire. Deux lectures
opposées en découlent, et le contrat ne tranche pas :

- **fusion** : un champ absent du corps reste ce qu'il était ;
- **remplacement** : un champ absent est effacé, ou remis à son défaut.

Nous avons écrit l'écran en supposant le **remplacement**, c'est-à-dire le pire
cas : il repart de toutes les valeurs du créneau et les renvoie toutes, même
celles que l'utilisateur n'a pas touchées. C'est correct dans les deux lectures,
donc sûr — mais cela nous force à savoir relire chaque champ, et §3 dit
pourquoi ce n'est pas toujours possible.

**Dites-nous laquelle des deux est vraie**, et écrivez-la dans la description de
l'opération. Si c'est la fusion, nous pourrons n'envoyer que ce qui change, et
§3 cesse d'être un problème.

---

## 3. `showExactAddress` s'écrit et ne se relit jamais

Le champ est dans `CreateScheduleRequest`, dans `UpdateScheduleRequest`, dans
`QuickSlotRequest` — et dans **aucun** DTO de lecture. Ni `ScheduleDto`, ni
`SlotFeedItemDto`.

C'est le seul réglage du créneau qui touche directement à la vie privée de
quelqu'un : il décide si l'adresse exacte d'un **domicile** est montrée. Notre
écran de modification ne peut donc pas afficher l'état courant.

Ce que nous en avons fait, faute de mieux : la case repart **décochée** —
l'option qui protège — et une ligne sous elle dit à l'utilisateur que son choix
actuel ne nous est pas renvoyé. L'erreur possible est donc de masquer une
adresse qui était montrée, jamais l'inverse. C'est le bon sens de l'erreur, mais
c'est une phrase d'excuse technique sur un écran de produit.

**Demande : ajouter `showExactAddress` à `ScheduleDto`.** Un booléen sur le DTO
que seul l'auteur relit — `GET /programs/{id}` — suffit ; il n'a rien à faire
sur `SlotFeedItemDto`, que tout le monde lit.

---

## 4. `isPubliclyShareable` — l'envoyons-nous pour rien depuis le début ?

Notre formulaire de création envoie `isPubliclyShareable` dans le corps du
`POST /programs/{id}/schedules`. **La clé n'est déclarée ni par
`CreateScheduleRequest`, ni par `UpdateScheduleRequest`**, et le partage a par
ailleurs sa propre route (`PATCH /slots/{id}/shareable`) avec sa propre lecture
(`GET /slots/{id}/share-link`).

Trois possibilités, et nous ne savons pas laquelle :

1. vous l'honorez, et le contrat est incomplet — dites-le, nous continuons ;
2. vous l'ignorez, et **tous les créneaux créés depuis l'app sont partis sur
   votre défaut** quel que soit le choix de l'hôte à l'écran. C'est alors un
   interrupteur qui ne fait rien depuis des semaines, et le pire des trois cas
   parce qu'il est invisible ;
3. elle est refusée en validation stricte — non, puisque nos créations passent.

Par précaution, **notre écran de modification ne renvoie pas cette clé** : la
réémettre à l'aveugle rallumerait un partage qu'un hôte aurait coupé depuis la
route dédiée. Nous la remettrons le jour où vous nous direz laquelle des trois
est la bonne.

---

## 5. Ce que nous avons livré, pour que vous sachiez ce qui appelle vos routes

- **`PUT /programs/{programId}/schedules/{scheduleId}`** est appelé depuis un
  nouvel écran, accessible à l'hôte depuis la fiche du créneau. Il envoie le
  même corps que la création, champ pour champ.
- **Aucune désinscription implicite** : le nombre de places ne peut pas
  descendre sous le nombre d'inscrits. Si votre serveur accepte un plafond
  inférieur, nous préférons ne pas l'utiliser — mais dites-nous ce qu'il fait,
  au cas où la route soit atteinte autrement.
- **Le `409 SCHEDULE_CONFLICT`** est affiché tel quel à l'auteur : nous rendons
  votre `message`, traduit par `Accept-Language`. Merci de le garder
  compréhensible sans contexte, il est lu seul.
- **Nous n'appelons pas `DELETE`** depuis cet écran. Annuler un créneau reste le
  geste distinct qu'il était, avec `POST /slots/{id}/cancel` et son motif.

---

## 6. Récapitulatif

| # | Demande | Nature |
|---|---|---|
| 1 | Émettre `SCHEDULE_CHANGED` aux inscrits sur modification effective d'un créneau, avec `scheduleId` dans le payload et un `collapse-id` stable | Fonctionnalité |
| 2 | Dire si le `PUT` fusionne ou remplace, et l'écrire dans le contrat | Clarification |
| 3 | Ajouter `showExactAddress` à `ScheduleDto` | Champ en lecture |
| 4 | Dire si `isPubliclyShareable` est honoré dans le corps d'un créneau | Clarification |

Le 1 est le seul qui débloque une promesse produit ; les trois autres retirent
chacun une supposition de notre code.
