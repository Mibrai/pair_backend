# Réponse backend — les quatre demandes sont livrées, et la cause était ailleurs

> Les trois routes du §2, §3 et §4 existent, ainsi que le champ du §5. Mais la
> question fermée du §2 avait une réponse en deux temps, et le second temps a
> décidé de l'ordre de livraison : nous avons dû corriger d'abord un défaut du
> modèle de données, antérieur au lot recap, sans lequel les trois routes
> auraient rendu des cartes datées du futur.
>
> Relevé et corrigé sur la base du code, le 2026-08-15.

---

## 1. La question du §2 : oui, mais ce n'était pas la bonne question

**`GET /programs/{id}` ne filtre rien temporellement.** `ProgramService.toDto`
appelle `scheduleRepository.findByProgramId`, sans aucune clause de date, et le
mapping vers `ScheduleDto` n'en ajoute pas. Un créneau passé est donc bien rendu
dans `schedules[]`, avec son `status` réel.

Si les quatre programmes de votre compte de test n'exposaient que des créneaux
futurs, ce n'était pas un filtre. C'était ceci :

**Un créneau récurrent n'a qu'une seule ligne en base, et un job la réécrit en
place.** `RecurringSlotRolloverJob`, toutes les dix minutes, avance `starts_at`
à la prochaine occurrence de la RRULE. Une séance passée ne laissait donc
*aucune trace* : ni ligne, ni date. Vos programmes de seed sont tous récurrents,
d'où l'observation.

Le repli que vous envisagiez — « interroger `GET /slots/{id}/recap` séance
passée par séance passée » — n'aurait donc trouvé aucune séance passée à
interroger. La réponse « oui » était exacte et ne vous aurait servi à rien.

## 2. Ce que ce défaut cassait, au-delà de votre question

En auditant, quatre conséquences sont apparues. Elles touchent des écrans déjà
en production, pas seulement les pages que vous attendez.

| Ce qui était cassé | Pourquoi |
|---|---|
| **Une carte par créneau, jamais par séance** | `slot_recaps.schedule_id` était `UNIQUE`. Un cours hebdomadaire ne pouvait porter qu'une seule carte, réécrite d'une semaine sur l'autre. |
| **`slotStartedAt` dans le futur** | Il était lu depuis `schedules.starts_at`, que le rollover avait déjà avancé. Une carte de mardi dernier s'affichait datée de mardi prochain. |
| **La fenêtre de sept jours ne se refermait jamais** | Elle était comptée depuis `starts_at`, qui reculait à chaque passage du job. `canContribute` restait vrai indéfiniment. |
| **Une seule présence par personne et par créneau, à vie** | `attendances` était `UNIQUE (schedule_id, user_id)`. Venir deux semaines de suite au même cours était impossible à déclarer. |

Et un cinquième, trouvé en chemin : le job avançait le créneau **dix minutes
après son début**, pas après sa fin — il ne regardait que `starts_at`. Une
séance de deux heures disparaissait des écrans pendant qu'on la vivait, et comme
confirmer sa présence exige une séance terminée, la confirmation n'était
proposée que pendant les dix minutes séparant la fin réelle du passage du job.
**Confirmer sa présence à un créneau récurrent était une course de dix
minutes.**

Livrer vos trois routes sans corriger cela aurait produit des pages remplies de
cartes datées de la semaine suivante. C'est pourquoi nous avons pris l'option
longue.

## 3. Le correctif : l'occurrence devient une notion de première classe

Nous n'avons **pas** créé une ligne `schedules` par séance — cela aurait rompu
toutes les inscriptions, conversations et notifications qui pointent sur
`schedule_id`, et changé l'identifiant d'un créneau à chaque semaine.

Une séance est désormais **nommée par l'instant où elle a commencé**, et ce
couple `(schedule_id, occurrence_start)` est la clé de tout ce qui décrit un
moment passé :

- `slot_recaps` porte `occurrence_start` et `occurrence_end`, et son unicité
  passe de « une carte par créneau » à **une carte par séance** ;
- `attendances` conserve `attended_at`, qui portait déjà cette information, et
  son unicité l'admet : **une présence par personne et par séance** ;
- `schedules` porte `last_occurrence_start` / `last_occurrence_end`, que le
  rollover inscrit au moment où il avance la ligne — le seul instant où le
  système sait encore quel moment vient de se terminer ;
- le rollover n'avance plus un créneau **qu'une fois sa séance terminée**.

`occurrence_end` est *copié* sur la carte, pas relu depuis le créneau : la
fenêtre de sept jours en découle, et une carte doit rester figée. Sans cette
copie, allonger la durée d'un créneau rouvrirait une fenêtre close sous les yeux
de quelqu'un qui a déjà partagé la carte.

**Reprise des données existantes.** Les cartes déjà écrites depuis le 14 août
sont datées depuis `attended_at` de leurs contributeurs — posé à la confirmation
de présence, donc avant tout rollover ultérieur. C'est la meilleure source
disponible, et elle est exacte pour tout ce que votre app a écrit jusqu'ici.

## 4. Les quatre demandes

### §2 — `GET /api/programs/{programId}/recaps`

Livrée. `SlotRecapDto[]`, aucun champ nouveau, trié par `slotStartedAt`
décroissant. Ni position ni rayon.

Visibilité graduée, exactement comme demandé : un **visiteur** reçoit les cartes
`PUBLIC` du programme ; un **participant** y ajoute les séances où sa présence
est confirmée, quelle que soit leur visibilité ; l'**auteur** reçoit toutes les
cartes de son programme, y compris sur un programme privé.

Rend un tableau vide, jamais 404, pour un programme sans carte lisible.

### §3 — `GET /api/activities/{activityId}/recaps`

Livrée. `SlotRecapDto[]` **publiques uniquement**, tous organisateurs confondus,
même tri.

`activityId` est bien l'UUID du référentiel — celui que porte déjà
`BrowsedActivityDto.activityId`. Un filtre s'ajoute à ceux que vous décriviez :
l'activité doit être `visibleOnMap`, comme dans `/activities/browse` d'où vient
cette page. Un organisateur qui s'est retiré de la découverte n'y revient pas
par ses souvenirs.

### §4 — `GET /api/users/{userId}/recaps`

Livrée. `SlotRecapDto[]` **publiques uniquement**, même tri. Une carte privée
d'un tiers n'apparaît pas sur un profil, y compris pour quelqu'un qui était à la
séance — celui-là la retrouve par `/api/recaps/mine`, comme vous le
souhaitiez.

### §5 — `recapWindowClosesAt`

Livré sur `SlotRecapDto`, nullable, aux côtés de `canContribute` :

```json
"recapWindowClosesAt": "2026-08-19T20:00:00Z"
```

Comptée depuis la **fin** de la séance, comme vous l'aviez déduit. `null`
lorsque la fenêtre est déjà refermée — il n'y a alors plus de délai à annoncer,
et une date passée s'afficherait comme un compte à rebours négatif.

C'est une date et non une durée : elle ne se périme pas en transit.

## 5. Ce qui change pour ce que vous avez déjà livré

Aucun contrat n'est rompu, mais deux valeurs deviennent justes là où elles
étaient fausses — et vos écrans en production vont s'en trouver changés :

1. **`slotStartedAt` cesse d'être dans le futur** sur les créneaux récurrents.
   Si votre écran « Mes moments » masquait ou triait autour de cette anomalie,
   c'est le moment de retirer le contournement.

2. **`GET /slots/{id}/recap` peut désormais désigner plusieurs cartes.** Il rend
   la plus récente que l'appelant a le droit de lire. Pour un créneau non
   récurrent — le seul cas où cette route était fiable — le comportement est
   identique.

3. **`GET /attendances/pending`** annonce désormais les dates de la séance à
   confirmer, et non celles que porte la ligne. `endsAt` y reste `null` quand
   aucune fin n'a été déclarée : la convention interne des deux heures sert à
   calculer, pas à afficher une heure que personne n'a annoncée.

4. **Confirmer sa présence à un créneau récurrent fonctionne**, et fonctionne
   pendant sept jours au lieu de dix minutes. Si votre écran de confirmation
   paraissait capricieux sur les cours hebdomadaires, la cause était là.

## 6. Sur le §6 — ce que vous ne demandez pas

Rien de ce que nous avons ajouté n'est une note, une moyenne, un classement ou
un compteur de réactions. `occurrence_start` et `occurrence_end` sont des
instants, `recapWindowClosesAt` est une échéance. La carte continue de ne rien
porter qui décrive les personnes qui y étaient.

## 7. Vérification

- **6 tests unitaires** sur la résolution d'occurrence : une séance en cours
  n'est pas terminée, un créneau déjà avancé désigne la séance retirée et non
  celle à venir, la convention des deux heures s'applique quand la fin n'est pas
  déclarée.
- **5 tests d'intégration** en base réelle, tous montés sur le décor qui était
  invisible jusqu'ici — un créneau hebdomadaire dont le rollover a déjà avancé
  la ligne : la carte porte la date vécue, la fenêtre se referme sept jours
  après la fin réelle, deux séances du même créneau portent chacune leur carte
  et leurs présences, les trois routes rendent la carte publique sans position
  ni rayon, et une carte privée reste invisible aux tiers mais pas à qui y
  était.

Les trois nouvelles requêtes sont interrogées en HTTP contre PostgreSQL, et non
par des mocks : ce sont des JPQL écrites à la main, et seule une vraie base dit
si elles filtrent ce qu'elles prétendent filtrer.

---

*Demande initiale : `docs/specs/PROMPT_BACKEND_RECAP_VISUALISATION_2026-08.md`.*
