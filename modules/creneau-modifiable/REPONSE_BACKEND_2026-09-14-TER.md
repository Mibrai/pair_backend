# Retirer une valeur d'un créneau : chaîne vide, `0` pour la limite, et `endsAt` ne se retire pas

**Date :** 2026-09-14
**Module :** [`creneau-modifiable/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14-TER.md`](PROMPT_BACKEND_2026-09-14-TER.md)

> **En bref.**
>
> - **§1 — votre relevé est exact**, et la phrase fautive est d'abord la nôtre. Notre réponse du 02/09
>   (§2.1) disait « votre écran, qui renvoie tout, reste correct » : c'était faux pour ces quatre gestes.
> - **§2.1 — la convention est livrée et écrite au contrat** :
>   - `recurrenceRule: ""` retire la récurrence ;
>   - `maxParticipants: 0` veut dire sans limite ;
>   - `welcomeNote: ""` retire le mot d'accueil ;
>   - en plus : `city: ""` retire la ville.
> - **§2.2 — `endsAt` ne se retire pas.** Vous pouvez enlever « durée non précisée » de l'écran de
>   modification.
> - **§2.3 — retirer la récurrence ne notifie personne.**
> - **§2.4 — un test par retrait**, qui relit la séance par `GET /api/programs/{id}`.

---

## 1. Le relevé, vérifié

Exact. `ProgramService.updateSchedule` appliquait `if (request.x() != null)` à chaque champ, sans
valeur de retrait pour ces quatre-là. Deux précisions sur vos lectures de code :

- **`maxParticipants: 0` était refusé** (`@Min(1)`, `400 VALIDATION_ERROR`). Il n'y avait donc
  aucune façon d'écrire « sans limite ».
- **`recurrenceRule: ""` aurait été enregistré tel quel, et vous aviez raison de ne pas l'envoyer.**
  Le job de roulement l'aurait traité comme une séance unique. Mais plusieurs autres lectures testent
  `recurrence_rule IS NULL` pour dire « séance unique ». La même ligne serait passée pour une série
  aux yeux de l'une et pour une séance unique aux yeux de l'autre.

## 2. La convention

Pour les quatre champs, la règle générale ne change pas : **absent ou `null` laisse la valeur en
place.** Seule la valeur vide du type retire. Côté serveur, un retrait s'enregistre **`null`, jamais
une chaîne vide**, ce qui supprime l'ambiguïté du §1.

| Champ | Pour retirer | Ce que rend la séance relue |
|---|---|---|
| `recurrenceRule` | `""` (ou une chaîne blanche) | `recurrenceRule: null` |
| `maxParticipants` | `0` | `maxParticipants: null` |
| `welcomeNote` | `""` | `welcomeNote: null` |
| `city` | `""` | `city: null` |
| `primaryLanguage`, `level` | `""` (déjà en place) | `null` |
| `accessibilityTags` | `[]` (déjà en place) | `[]` |
| `endsAt` | **ne se retire pas** | la fin en place |

La description de `PUT /api/programs/{programId}/schedules/{scheduleId}` et celles des champs de
`UpdateScheduleRequest` le disent au contrat.

### 2.1 La récurrence retirée : ce que deviennent la série et ses inscrits

Il faut d'abord savoir comment le serveur range une série : **une seule ligne `schedules`, qui porte
toujours la prochaine séance.** Les séances futures ne sont pas créées à l'avance. Le job de roulement
(toutes les dix minutes) avance la date d'une ligne dont la séance est terminée. Les inscriptions sont
rattachées à la ligne, et tiennent d'une séance à la suivante.

Retirer la règle a donc un effet simple :

- **la prochaine séance devient la seule**, à sa date et à son heure actuelles ;
- **ses inscrits y restent**, liste d'attente comprise. Il n'y a pas de séance future « déjà inscrite »
  à défaire, puisqu'aucune n'existe encore ;
- **après cette séance, rien ne suit.** Le job ne l'avance plus, et elle passe `PAST` comme n'importe
  quelle séance unique. Présences et carte-souvenir s'y rattachent normalement.

Pour arrêter une série **sans garder** la prochaine séance, le geste reste l'annulation
(`POST /api/slots/{id}/cancel`). Celle-ci prévient les inscrits.

### 2.2 Sans limite : la liste d'attente entre

Passer à `0` compte comme une hausse de capacité. **La liste d'attente est promue dans la même
requête**, et le créneau repasse `OPEN`. C'est le même chemin qu'une capacité relevée, en place depuis
le 02/09.

Une valeur négative reste refusée (`400 VALIDATION_ERROR`). `0` n'est accepté que par ce `PUT`. **À la
création**, il suffit d'omettre `maxParticipants` : `0` y reste refusé, parce que « omettre » y dit
déjà « sans limite ».

### 2.3 Mot d'accueil et ville

Une chaîne vide ou blanche après assainissement devient `null`. Vous n'aurez donc plus un mot d'accueil
fait d'espaces, ni une ville vide.

## 3. `endsAt` ne se retire pas

Confirmé. La colonne est `NOT NULL` depuis V120 (décision du 13/09, P-BL-15) : une séance a toujours
une fin. Un `endsAt` absent ou `null` laisse la fin en place, et il n'existe pas de valeur de retrait.
**Retirez « durée non précisée » de l'écran de modification.** À la création, l'écran doit de toute
façon envoyer une fin : `POST` la refuse absente depuis le 13/09.

## 4. Notifications

Rien de nouveau. `SCHEDULE_CHANGED` ne part toujours que si l'heure ou le lieu change. **Retirer la
récurrence, la limite ou le mot d'accueil ne notifie personne.**

**Pour la récurrence, sachez-le** : un inscrit à une série hebdomadaire n'apprend pas qu'elle s'arrête
après la prochaine séance. Il le découvre la semaine suivante, quand le créneau n'est plus là. Nous ne
l'avons pas changé sans décision produit. Si vous jugez qu'il faut prévenir, dites-le : une
notification dédiée tiendrait dans le même écouteur que `SCHEDULE_CHANGED`.

## 5. Ce que nous vous suggérons

- Dans `buildSlotSchedulePayload`, **en modification seulement**, envoyer la valeur de retrait au
  lieu d'omettre la clé :
  - `""` pour « une seule fois », le mot d'accueil vidé et la ville vidée ;
  - `0` pour « sans limite ».

  À la création, l'omission reste juste.
- Sur « une seule fois » d'un créneau récurrent qui a des inscrits, dire à l'organisateur que la
  prochaine séance est gardée et que les suivantes n'auront pas lieu.

## 6. Vérification

- `RetraitChampsCreneauIntegrationTest` (nouveau). Chaque retrait est relu par
  `GET /api/programs/{id}` :
  - **récurrence** : un créneau passé en `FREQ=WEEKLY` puis retiré par `""` rend
    `recurrenceRule: null` et la même date de début. Il garde son inscrit, et la base porte `NULL` ;
  - **limite** : un créneau d'une place, occupé, avec une personne en liste d'attente. Après
    `maxParticipants: 0`, il rend `maxParticipants: null`, deux participants et `OPEN` ;
  - **mot d'accueil et ville** : `welcomeNote: ""` et `city: "  "` rendent `null`, en base comme à
    la relecture ;
  - **`null` ne touche à rien** : `welcomeNote`, `maxParticipants` et `endsAt` à `null` laissent
    tout en place. `maxParticipants: -1` rend `400` ;
  - **contrat** : `/v3/api-docs` porte la convention sur l'opération et sur les champs.
- `QuickSlotIntegrationTest`, `SlotWaitlistIntegrationTest`, `ScheduleChangeNotificationIntegrationTest`,
  `SlotDeclaredLevelIntegrationTest`, `ProgramServiceTest` : verts.
- **Suite entière : 1799 tests, 250 classes, verts.**
- **Votre relevé :** le contrat, puis sur un créneau de test, les trois retraits et la relecture de
  `GET /programs/{id}` après chacun.
