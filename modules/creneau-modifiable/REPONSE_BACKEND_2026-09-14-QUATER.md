# Une série qui s'arrête le dit : notification `SERIES_ENDED`

**Date :** 2026-09-14
**Module :** [`creneau-modifiable/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14-QUATER.md`](PROMPT_BACKEND_2026-09-14-QUATER.md)
**Décision de l'utilisateur, 14/09/2026 :** prévenir les inscrits quand la récurrence est retirée.

> **En bref.**
>
> - **§2.1 — nouveau type `SERIES_ENDED`**, le nom que vous proposiez. Il est émis quand
>   `recurrenceRule: ""` retire une règle non vide.
> - **§2.2 et §2.3 — destinataires et charge conformes** : ceux de `SCHEDULE_CHANGED`, jamais
>   l'auteur ; charge `NotificationPayload.ofSchedule`, avec `sessionAt` = la séance gardée.
> - **§2.4 — push et e-mail sans aucun jour de semaine**, date comprise (voir §3).
> - **Heures de silence : `SERIES_ENDED` les traverse**, comme `SCHEDULE_CHANGED`. C'est ce que veut
>   dire « comme une modification » : une modification est critique.
> - **Regroupement : `slot-series-ended-<scheduleId>`**, distinct de `slot-changed-…` (voir §4).
> - **§2.5 et §2.7 — conformes.** Rien ne part si la règle était déjà vide, si le créneau est annulé
>   ou si la séance gardée est terminée, et un test couvre le cas deux inscrits plus une personne en
>   attente.
> - **§2.6 — critique comme `SCHEDULE_CHANGED`, et réglable comme lui** : un seul interrupteur dans
>   l'app suppose qu'elle écrive les deux préférences (§2).

---

## 1. Le type

`SERIES_ENDED` rejoint `NotificationType`, à côté de `SLOT_CANCELLED`. Il figure au contrat partout où
l'énumération est servie (`NotificationDto.type`, préférences).

**Pourquoi un type et non une valeur de `changedFields`.** C'est ce que nous avions d'abord écrit, avant
de lire votre demande. Nous l'avons abandonné pour vos raisons. Un type vous donne sa route, son glyphe
et sa préférence. Et `changedFields` reste ce qu'il dit : ce qui a bougé de la séance. Une fin de série
ne dit pas qu'une séance a bougé, elle dit que les suivantes n'auront pas lieu.

**Retirer la règle et changer l'heure d'un même geste** envoie **deux** notifications : un
`SCHEDULE_CHANGED` (`changedFields` = `["TIME"]`) et un `SERIES_ENDED`. Ce sont deux nouvelles
distinctes, et chacune garde son texte.

**La version publiée de l'app** range un type inconnu en `unknown`. Les inscrits qui ne l'ont pas mise à
jour reçoivent quand même la push, avec le texte du serveur ; seule la carte dans l'app est générique.

## 2. Quand il part, et à qui

**Il part** quand un `PUT /api/programs/{programId}/schedules/{scheduleId}` fait passer
`recurrenceRule` d'une valeur non vide à `null`. En pratique, c'est un `""` sur une série.

**Il ne part pas** :

- si la règle était déjà vide ;
- si l'on change seulement de règle (hebdomadaire → toutes les deux semaines) : la série continue ;
- si le créneau est annulé. Le `PUT` refuse de toute façon un créneau annulé
  (`SLOT_CANCELLED_READONLY`), et l'annulation a déjà son message ;
- **si la séance gardée est déjà terminée**. C'est la fenêtre de dix minutes pendant laquelle le
  roulement n'a pas encore avancé la ligne. Il n'y a alors plus de « prochaine séance » à annoncer.

**Destinataires** : ceux de `SCHEDULE_CHANGED` (`SlotConcernedPeople`). Ce sont les participations
`CONFIRMED`, `INTERESTED` et `WAITLISTED` du créneau, et les inscrits du programme qui y sont
rattachés, sans doublon. **L'auteur du geste est retiré.**

**Charge** : `NotificationPayload.ofSchedule(slot)`, rien de plus. Soit `scheduleId`, `programId`,
`programTitle`, `activityName`, `placeName` et `sessionAt`, qui est la séance gardée, donc la dernière.
**Aucune règle de récurrence ni aucun jour de semaine** n'y figure.

**Classement** : critique et envoyé par e-mail, exactement comme `SCHEDULE_CHANGED` et
`SLOT_CANCELLED` :

- **il traverse les heures de silence**, pour la même raison que l'annulation : quelqu'un garde sa
  soirée, ou se déplace, pour une séance qui n'aura pas lieu ;
- **il traverse un blocage entre l'inscrit et l'organisateur**, comme toute notification critique ;
- **il est réglable, comme `SCHEDULE_CHANGED`**. « Critique » ne veut pas dire « verrouillé » : les
  seuls types qu'aucun réglage ne coupe sont ceux que l'app affiche verrouillés
  (`kNotificationTypesNotSettable` : vérification de compte, mot de passe, veille), et la liste
  serveur doit rester identique. Le serveur n'a pas de catégorie de préférences : chaque type a sa
  ligne. **Pour un seul interrupteur « modifications de créneau », l'app écrit les deux** :
  `PUT /api/notifications/preferences` pour `SCHEDULE_CHANGED` et pour `SERIES_ENDED`. Si vous préférez
  que `SERIES_ENDED` soit verrouillé, ajoutez-le à `kNotificationTypesNotSettable` : nous l'ajouterons
  à la liste serveur dans le même mouvement.

Le robinet `pair.notifications.schedule-changed.enabled` coupe les deux types ensemble.

## 3. Les textes

| | Français | Anglais | Allemand |
|---|---|---|---|
| Titre push | La série s'arrête : {programme} | Series ending: {programme} | Serie endet: {programme} |
| Corps push | La séance du 22 septembre a bien lieu, les suivantes non. | The session on 22 September still takes place, the following ones do not. | Der Termin am 22. September findet statt, die folgenden nicht. |
| Objet e-mail | La série s'arrête : {programme} | Series ending: {programme} | Serie endet: {programme} |
| Corps e-mail | La séance « {programme} » ne se répète plus : la prochaine a bien lieu, les suivantes non. Retrouvez les détails dans l'application. | … | … |

**La date du corps push n'a pas de jour de semaine, et c'est un format à part.** Les autres pushs
écrivent `push.tpl.datePattern`, qui commence par le jour (« mar. 22 sept. »). Suivi de « la série
s'arrête », ce jour dirait quel jour de la semaine la série avait lieu. `SERIES_ENDED` a son motif
propre (`push.SERIES_ENDED.datePattern`, « 22 septembre »), sans heure. Si `sessionAt` est illisible,
le corps devient « La prochaine séance a bien lieu, les suivantes non. »

**L'e-mail n'écrit aucune date**, comme celui de `SCHEDULE_CHANGED` : il n'a ni la langue de l'appareil
ni son fuseau. **Aucun des textes ne nomme le lieu.**

## 4. Le regroupement : distinct, et pourquoi

Vous acceptiez `slot-changed-<scheduleId>`. Nous avons retenu **`slot-series-ended-<scheduleId>`**.
Un organisateur qui retire la règle puis corrige l'heure une minute plus tard enverrait sinon un
`SCHEDULE_CHANGED` qui **remplace** la bannière « la série s'arrête ». La personne ne verrait plus que
« nouvel horaire », et la fin de la série ne resterait que dans la liste des notifications. Deux
identifiants évitent ce cas, sans empiler de bannières quand l'organisateur retouche seulement l'heure.

## 5. Ce que nous vous suggérons

- Dans `notification_route.dart`, faire ouvrir la fiche du créneau par `SERIES_ENDED`, comme
  `SCHEDULE_CHANGED`. La fiche relue porte `recurrenceRule: null`.
- Une préférence dans la même rubrique que `SCHEDULE_CHANGED`, qui écrit les deux types (§2).
- Pour l'extension iOS, garder le texte du serveur, comme vous le prévoyez.

## 6. Vérification

- `ScheduleChangeNotificationIntegrationTest` :
  - **série avec deux inscrits et une personne en attente, règle retirée** : chacun des trois reçoit
    exactement un `SERIES_ENDED`, dont `sessionAt` vaut la séance gardée et `scheduleId` le créneau.
    Aucun ne reçoit de `SCHEDULE_CHANGED`, puisque ni l'heure ni le lieu n'ont bougé. **L'organisateur
    ne reçoit rien** ;
  - **règle déjà vide, ou règle changée** : rien, ni `SERIES_ENDED` ni `SCHEDULE_CHANGED` ;
  - **séance gardée déjà terminée** : rien.
- `PushNotificationServiceTest` : le titre, et un corps qui porte « 20 août » et « les suivantes non »,
  sans jour de semaine ; en anglais et en allemand aussi.
- `EmailServiceTest` : un texte et un objet propres, qui ne disent ni « horaire » ni « annulée ». Le test
  déclaratif « chaque type envoyé par e-mail a son texte » inclut le nouveau type.
- `RegroupementCreneauModifieTest` : `slot-series-ended-<id>`.
- **Votre relevé :** `/v3/api-docs` (le type dans l'énumération), puis sur un créneau de test récurrent
  avec le second compte inscrit, le retrait de la règle. Une notification `SERIES_ENDED` doit arriver
  chez l'inscrit, et rien chez l'organisateur.
