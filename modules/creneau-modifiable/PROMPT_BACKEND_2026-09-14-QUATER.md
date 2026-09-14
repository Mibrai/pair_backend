# Une série qui s'arrête doit le dire à ses inscrits

**Date :** 2026-09-14
**Module :** `creneau-modifiable`
**Fait suite à :** `REPONSE_BACKEND_2026-09-14-TER.md`, §4
**Décision de l'utilisateur, 14/09/2026 :** oui, prévenir les inscrits quand l'organisateur retire la
récurrence d'une série.

> **Ce que fait l'app** (en cours, derrière le drapeau `scheduleFieldClearing`) : sur « une seule
> fois » d'un créneau récurrent qui a des inscrits, elle prévient **l'organisateur** que la prochaine
> séance est gardée et que les suivantes n'auront pas lieu.
>
> **Ce qui vous appartient** : prévenir **les inscrits**. Aujourd'hui, un inscrit à une série
> hebdomadaire découvre la semaine suivante que le créneau n'est plus là — il a pu garder la soirée
> libre, ou venir. C'est le même principe que `SLOT_CANCELLED` : ce qui disparaît de l'agenda de
> quelqu'un se dit.

---

## 1. Constat (votre réponse TER)

- `recurrenceRule: ""` sur `PUT /api/programs/{programId}/schedules/{scheduleId}` retire la règle :
  la prochaine séance reste, avec ses inscrits et sa liste d'attente ; après elle, rien ne suit.
- `SCHEDULE_CHANGED` ne part que si l'heure ou le lieu change (§1.2 de `REPONSE_BACKEND_2026-09-02.md`).
- Retirer la récurrence ne notifie personne (§4) ; vous proposez une notification dédiée dans le même
  écouteur.

## 2. La demande

1. **Un nouveau type, `SERIES_ENDED`** (nom à votre convenance, dites-le), émis quand une règle de
   récurrence non vide passe à `null` par ce `PUT`.
2. **Destinataires** : les mêmes que `SCHEDULE_CHANGED` — `CONFIRMED`, `INTERESTED`, `WAITLISTED` du
   créneau, et les inscrits du programme rattachés à ce créneau. Jamais l'auteur du geste.
3. **Charge** : `NotificationPayload.ofSchedule` comme `SCHEDULE_CHANGED` (`scheduleId`, `programId`,
   `programTitle`, `activityName`, `placeName`, `sessionAt` = la dernière séance gardée).
   **Pas de jour de semaine récurrent dans le texte ni la charge** : « la série s'arrête après la
   séance du 22/09 » et non « plus de séance le mardi » (doctrine : jamais un lieu et une récurrence
   ensemble dans un contenu qui sort de l'app).
4. **Texte serveur** (push et e-mail comme `SCHEDULE_CHANGED`) : l'essentiel en une phrase —
   la prochaine séance a bien lieu, les suivantes non. Respect des heures de silence comme pour une
   modification ; `collapse-id` : le même que `SCHEDULE_CHANGED` (`slot-changed-<scheduleId>`) nous
   convient, dites-nous si vous préférez distinct.
5. **Rien si** la règle était déjà vide, si le créneau est annulé dans la même requête, ou si la
   séance gardée est déjà passée.
6. **Préférence** : rattachée à la même catégorie que `SCHEDULE_CHANGED` (critique, non désactivable
   séparément) — ou dites-nous la vôtre.
7. Un test d'intégration : série avec deux inscrits et une personne en attente, règle retirée → trois
   notifications de ce type, aucune pour l'organisateur ; règle déjà vide → aucune.

## 3. Côté app, ensuite

Quand le type sera au contrat et déployé : ajout à `NotificationType`, route vers la fiche du créneau
(`notification_route.dart`, `switch` sans `default`), glyphe, préférence, texte push, clés l10n, et
les tests déclaratifs `notification_types_test` / `notification_route_test`. L'extension iOS gardera
le texte du serveur, comme pour `SCHEDULE_CHANGED`.

## 4. Comment nous vérifierons

Relevé `/v3/api-docs` (le type dans l'énumération), puis sur un créneau de test récurrent avec le
second compte de test inscrit (écriture soumise à l'accord de l'utilisateur) : retrait de la règle →
une notification reçue par l'inscrit, qui ouvre la fiche.
