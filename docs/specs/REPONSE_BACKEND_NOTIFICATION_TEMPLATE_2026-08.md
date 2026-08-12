# Réponse backend — template de notification (août 2026)

> Réponse à `PROMPT_BACKEND_NOTIFICATION_TEMPLATE_2026-08.md`. **Cinq demandes
> sur six sont livrées** — N1, N2 (option A), N4, N5 et N6. Voir « Ce qui est
> livré » en fin de document. Seule **N3** reste à faire : elle demande de
> séparer iOS d'Android dans l'envoi, et un arbitrage de votre côté.
>
> Le document ouvre sur une correction, parce qu'elle déplace le point de départ : la
> notification que vous avez mesurée n'est jamais passée par le code qui produit
> les notifications. Ce que vous en concluez sur N1 est donc à refaire, et la
> conclusion change — **la moitié de N1 est déjà servie**. En échange, N2 est plus
> dure que vous ne le pensez, et N6 se répond en un mot : le job n'existe pas.

---

## La mesure porte sur une ligne de seed

`f1000000-0000-0000-0000-000000000002` est une ligne écrite en SQL brut par la
migration `V27__reset_and_seed_germany.sql:985`. Son payload y est littéral :

```sql
('F1000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002',
 'NEW_PEER_REC', 'IN_APP',
 '{"fromUserId":"00000000-0000-0000-0000-000000000003","fromUserName":"Max Schmidt"}',
 TRUE, NOW()-INTERVAL '19 days', NOW()-INTERVAL '18 days'),
```

Deux conséquences.

**`NEW_PEER_REC` n'est émis par personne.** C'est une valeur *legacy*, conservée
dans l'enum uniquement parce que les seeds V12/V13/V27 la contiennent —
`NotificationType.java:21-28` le dit en commentaire. Aucun appel à `notify()` ne
la produit. Même chose pour `NEW_REVIEW`, `NEW_BADGE`, `MATCH_FOUND`,
`PROGRAM_CANCELLED`, `SCHEDULE_CHANGED`, `SYSTEM`.

**Le compte de test `lena.mueller@web.de` ne porte que des lignes de seed.** Les
deux clés que vous y voyez ne viennent pas de `NotificationPayload` : elles ont
été tapées à la main dans un fichier `.sql`. Mesurer ce compte mesure la qualité
du jeu de démonstration, pas celle du contrat d'API.

Le vrai producteur est `NotificationPayload` (`NotificationPayload.java`), et il
sert déjà bien plus que ce que vous avez relevé. La suite reprend votre tableau
sur cette base.

---

## N1 — six clés à ajouter, pas dix

Les payloads réellement émis passent tous par un des trois constructeurs de
contexte de `NotificationPayload` : `ofSchedule` (l. 65), `ofProgram` (l. 77),
`ofUserActivity` (l. 87). Voici l'état exact, pour un type qui porte une séance
(`SLOT_JOINED`, `SLOT_CANCELLED`, `ATTENDANCE_PROMPT`, `ACTIVITY_ALERT_MATCH`) :

| Clé du template | État réel | Où |
|---|---|---|
| `programId` | ✅ servi | `ofProgram`, l. 79 |
| `scheduleId` | ✅ servi | `ofSchedule`, l. 68 |
| `programTitle` | ✅ **servi** | `ofProgram`, l. 80 |
| `activityName` | ✅ **servi** | `ofUserActivity`, l. 96 |
| `categoryColorRamp` | ✅ **servi** | `ofUserActivity`, l. 101 |
| `scheduledAt` | ✅ servi, au 1<sup>er</sup> niveau | `NotificationDto:108-121` |
| `authorId` | ⚠️ servi sur 2 types seulement | `SubscriptionService:141,166` |
| `authorName` | ⚠️ idem | `SubscriptionService:142,167` |
| `authorAvatarUrl` | ❌ absent (la donnée existe) | `Program.organizerAvatarUrl` |
| `categoryIcon` | ❌ absent (la donnée existe) | `Category.icon`, l. 27 |
| `endsAt` | ❌ absent (la donnée existe) | `Schedule.endsAt`, l. 59 |
| `addressPublic` | ❌ absent (la donnée existe) | `Schedule.addressPublic`, l. 48 |
| `messageAuthorName` | ✅ **servi** (renommé, N2 option A) | `ChatPushListener` |
| `messageBody` | ✅ **servi** (renommé, N2 option A) | `ChatPushListener` |
| `conversationId` | ⚠️ servi, push uniquement | `ChatPushListener:42` |
| `fromUserId` / `fromUserName` | ❌ **n'existent pas** hors seed | — |

**`programTitle` est déjà servi.** C'est votre seuil, celui qui décide de tout :
il est là depuis que `NotificationPayload` existe, sur tous les types qui portent
un programme. Ce que vous avez mesuré ne le montrait pas parce que la ligne
mesurée n'en porte pas.

**Six clés restent à ajouter** — `authorName`, `authorAvatarUrl`, `categoryIcon`,
`endsAt`, `addressPublic`, et `authorId` sur les chemins de séance. Toutes les
six lisent un champ qui existe déjà sur l'entité : ce sont six lignes dans
`ofSchedule`/`ofProgram`/`ofUserActivity`, pas six jointures.

**`fromUserId` et `fromUserName` sont à retirer de votre liste.** Vous les
rangez sous « déjà servi, ne rien changer » ; ils ne sont servis nulle part
ailleurs que dans le seed. La clé de jointure que vous proposez en N2 n'existe
donc pas dans un payload réel — voir N2.

### Sur vos quatre points de contrat

1. **Données brutes** — c'est déjà la règle, et elle est plus stricte que vous ne
   le demandez : `NotificationDto` documente explicitement l'absence de `title`
   et de `message` comme un choix (l. 25-30), pour la raison exacte que vous
   donnez. Un `Instant` traverse en ISO-8601 par `normalize()` (l. 125-136).
2. **`categoryColorRamp` n'est pas une couleur** — d'accord, et c'est déjà le
   cas : la valeur vient de `Category.colorRamp`, la même colonne que lisent les
   DTO de la carte. Vous et le pin peignez depuis la même source par
   construction.
3. **`addressPublic`, pas `address`** — d'accord sur le nom, avec une réserve qui
   n'est pas cosmétique. `addressPublic` **n'est pas** l'adresse diffusable : la
   colonne porte l'adresse tout court, et c'est `SlotAddressVisibility.resolve()`
   qui décide de la montrer ou non (lieu `PUBLIC`, ou `showExactAddress=true`, ou
   participation `CONFIRMED`). Servir la colonne telle quelle dans un payload
   ferait fuiter sur écran verrouillé exactement ce que cette classe protège
   partout ailleurs. La valeur passe donc par ce filtre avant d'être écrite, et
   la clé est **absente** quand le lieu n'est pas diffusable — jamais tronquée ni
   floutée. Votre repli « la ligne d'adresse disparaît » couvre ce cas.

   **Une restriction que nous assumons :** la troisième branche de la règle — « a
   une participation `CONFIRMED` » — n'est **pas** appliquée. Elle dépend de qui
   regarde, or un payload est composé **une fois pour N destinataires** :
   `SLOT_CANCELLED` prévient tous les inscrits d'un seul payload, et ces inscrits
   incluent des `INTERESTED`, qui n'ont pas droit à l'adresse. Servir l'adresse à
   un participant confirmé demanderait de composer un payload par destinataire —
   un changement de `notify()` lui-même, hors de ce lot. Conséquence concrète :
   sur un **lieu privé non partagé**, l'adresse n'arrive à personne, pas même à
   un participant confirmé qui la voit pourtant dans l'app. C'est le sens de
   notre question 3 en fin de document.
4. **`scheduledAt` reste au premier niveau** — il y est et il y reste
   (`NotificationDto.scheduledAt`, l. 72). Une précision utile : il est dérivé de
   `payload.sessionAt` pour **quatre types seulement**
   (`NotificationDto.java:41-45`). `SLOT_CANCELLED` et `ATTENDANCE_PROMPT`
   portent un `sessionAt` mais n'exposent délibérément pas `scheduledAt` : l'un
   désigne une séance annulée, l'autre une séance passée. Un compte à rebours
   vers l'une ou l'autre serait faux. Ne les traitez pas comme un oubli.

**Sur le `snake_case`** : merci, mais inutile. Le producteur est unique et écrit
en `camelCase`. Le seed est la seule source de `snake_case` (`{"starts_in":…}` en
V12), et il ne survivra pas à ce lot.

---

## N2 — la jointure que vous proposez n'existe pas

Vous classez `conversationId` en clé n°1, « le fil désigne **directement** son
programme, sans ambiguïté ». Ce n'est pas le cas ici.

`Conversation` ne porte pas de programme. Elle porte un **contexte d'activité** :

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "activity_context_id")
private Activity activityContext;          // Conversation.java:35-37
```

Une activité porte N programmes. La jointure par `conversationId` ramène donc un
ensemble, pas un programme — la même ambiguïté que celle de la paire
d'utilisateurs, déplacée d'un cran. Votre « point à trancher côté produit » n'est
pas un cas limite : c'est le cas nominal, et aucune des deux clés que vous
proposez ne le tranche.

**Et il y a plus gênant.** Un `NEW_MESSAGE` ne crée **aucune notification
in-app**. Le chemin de la messagerie passe par `notifyPushOnly`
(`NotificationService.java:93`), qui envoie la push et s'arrête là. La raison est
documentée et tient toujours : un message a déjà son écran, son fil et son
compteur ; le doubler d'une notification in-app le compterait deux fois dans le
badge et remplirait le centre d'entrées que personne n'a demandées.

La variante « message » de votre template n'a donc **pas de carte in-app à
peupler**. `messageAuthorName` et `messageBody` n'ont de sens que dans la charge
push — où ils voyagent déjà, sous les noms `senderName` et `messagePreview`.

Trois issues, à trancher côté produit avant que nous écrivions quoi que ce soit :

| | Ce que ça donne | Coût |
|---|---|---|
| **A** | On s'arrête là : le template « message » ne vit qu'en push. Renommer `senderName`/`messagePreview` en `messageAuthorName`/`messageBody` suffit. | Deux lignes. |
| **B** | `Conversation` gagne un `program_id` nullable, posé à la création quand le fil naît d'un créneau (`SlotService:150-155` a le programme sous la main). La jointure devient exacte, et seulement pour les fils créés après. | Une migration, un champ, un backfill impossible pour l'historique. |
| **C** | On crée une notification in-app pour les messages. | Rouvre la double-comptabilisation du badge. Nous le déconseillons. |

Notre recommandation : **A maintenant, B si la carte « message » in-app devient
un objectif**. A débloque votre template sur les trois autres variantes sans rien
attendre.

> **Tranché : c'est A, et c'est livré.** Le payload d'un `NEW_MESSAGE` porte
> désormais `messageAuthorName` et `messageBody`, aux noms de votre template.
> Les anciens noms `senderName`/`messagePreview` ne sont **plus servis du tout** —
> servir les deux ferait vivre deux contrats pour une même donnée. Le renommage
> n'a pas eu besoin de repli : ce payload est construit et consommé dans le même
> processus, aucune version n'en lit d'une autre.
>
> Ce qui reste vrai après A, et qu'il faut avoir en tête : **un `NEW_MESSAGE` ne
> crée toujours aucune notification in-app**. Ces deux clés ne voyagent que dans
> la charge push. Si votre liste in-app doit un jour afficher une carte
> « message », c'est l'option B, et c'est un lot à part.

**Sur le fond de votre argument, en revanche, nous sommes entièrement d'accord :**
ne jamais joindre par nom d'affichage. C'est déjà la règle — aucun payload émis
n'utilise un nom comme clé, tous portent des UUID normalisés (`normalize()`,
l. 129).

---

## N3 — la moitié existe, l'autre moitié demande de séparer iOS d'Android

Ce qui est déjà en place (`PushNotificationService.java:229-290`) : un `title` et
un `body` traduits, composés serveur, dans la langue de **l'appareil** et non de
la requête. Les tokens sont groupés par locale et un message part par groupe
(l. 69-83). Votre règle « la langue vient de `locale`, propriété de l'appareil,
envoyée à `POST /notifications/devices` » est exactement ce qui est implémenté —
la colonne `device_tokens.locale` existe depuis `V49`, et le repli est le
français.

Ce qui manque pour votre tableau, et ce n'est pas un détail de formatage :

**Un seul texte part pour les deux plateformes.** `sendToTokens` construit un
`MulticastMessage` unique avec une `Notification` commune (l. 97-102) ; l'`Aps`
et l'`AndroidNotification` ne portent que le badge, le son et la couleur. Votre
tableau demande un `title` **différent** sur iOS (`{programTitle}`) et Android
(`{activityName} · {programTitle}`), plus un `subtitle` iOS-seulement. Il faut
donc grouper les tokens par **(locale, plateforme)** et non par locale seule.
`DeviceToken.platform` existe (l. 35) — c'est un second niveau de `groupingBy`,
puis un `Aps.setSubtitle()` sur la branche iOS.

**Les formules diffèrent.** Aujourd'hui le corps d'un `PROGRAM_REMINDER` est
`« Votre session commence dans {timeUntil} »` (`messages.properties:64`) — une
phrase traduite, avec un `timeUntil` que personne ne calcule (aucun émetteur ne
pose cette clé, elle sort donc vide). Votre formule veut
`{rebours} · {date} {heure}` puis `{addressPublic}` en seconde ligne. C'est une
réécriture des clés `push.*.body`, pas un ajustement.

**Un point de contrat à trancher.** Vous demandez que le serveur écrive
`{rebours}` — « dans 45 min ». C'est précisément ce que votre point de contrat
n°1 en N1 interdit : une push qui a dormi deux heures dans le centre afficherait
un rebours faux, et contrairement à la carte in-app, **aucun code client ne
repassera le recalculer**. Le texte d'une push est figé à l'émission, par
construction. Nous le composerons donc à partir de `sessionAt` au moment de
l'envoi, en assumant qu'il vieillit — mais dites-nous si vous préférez une
formulation absolue (`« à 20:00 »`) sur cette ligne, qui elle ne vieillit pas.

**D'accord sans réserve** sur les trois autres règles : l'ordre des segments,
l'adresse en seconde ligne, et ne pas répéter « meetDo » — le titre actuel ne le
fait pas non plus.

---

## N4 — déjà fait, à un détail près

`ChatService.java:39` :

```java
private static final int PREVIEW_MAX_LENGTH = 120;
```

et la coupe, l. 128-132, appliquée **à l'émission de l'événement**, donc avant
que quoi que ce soit parte vers APNs. Le commentaire cite votre raison mot pour
mot : « `content` monte à 4000 caractères, une notification n'en montre qu'une
poignée, et la charge push est plafonnée à 4 Ko par APNs ».

**Le détail :** la coupe est un `substring` brut, pas une coupe sur frontière de
mot. « On se retrouve devant le court 3 ? » coupé à 120 peut finir au milieu d'un
mot. Vous demandez une frontière de mot — c'est une ligne, nous la ferons.

**Une réserve sur votre raisonnement, en revanche.** Tronquer `messageBody` ne
suffit pas à garantir les 4 Ko. La charge porte aussi `putAllData(payload)`
(l. 103-107) : **tout** le payload voyage en données, et N1 va l'allonger de six
clés dont `addressPublic` (300 caractères en base) et `authorAvatarUrl` (une URL
complète). Le risque de dépassement se déplace du message vers le contexte. Nous
ajouterons un garde-fou sur la taille sérialisée totale, avec les clés les moins
utiles écartées en premier — c'est le vrai correctif à « rien ne le signale ».

---

## N5 — absent, et effectivement inerte

Aucune occurrence de `mutable-content` ni de `category` dans le code
(`ApnsConfig`/`Aps`, l. 112-117 : seulement `badge` et `sound`). Vous avez raison
sur les deux points : l'extension ne se déclencherait pas, et les deux clés sont
sans effet tant qu'elle n'existe pas.

Deux lignes sur le `Aps.Builder`. Nous les posons avec N3, puisque c'est le même
fichier et la même méthode.

Une remarque : `sendBadgeUpdate` (l. 147-175) ne doit **pas** les recevoir. C'est
une push de fond, sans `alert` ; lui ajouter `mutable-content` réveillerait
l'extension pour une charge qui n'a rien à afficher.

---

## N6 — le rappel n'est pas planifié. Du tout.

C'est la réponse à vos trois questions, et elle est plus courte que les
questions : **il n'existe aucun producteur de `PROGRAM_REMINDER`.**

Ce qui existe autour du type entretient l'illusion du contraire, et c'est
probablement ce qui a motivé votre demande :

- le type est dans l'enum (`NotificationType.java:11`) ;
- il a un titre et un corps traduits dans les trois langues
  (`messages{,_en,_de}.properties`) ;
- il est le premier des quatre types qui exposent `scheduledAt`
  (`NotificationDto.java:42`) ;
- le seed V27 en pose deux lignes (l. 996 et 1021), avec un `sessionAt` correct ;
- des préférences par défaut existent pour lui (`notification_prefs`).

Mais aucun `notificationService.notify(…, PROGRAM_REMINDER, …)` nulle part.
Les seuls jobs planifiés du dépôt sont `AttendancePromptJob` (deux crons
horaires), `GdprPurgeJob` et `RecurringSlotRolloverJob`. Aucun ne regarde les
créneaux à venir.

Vos questions, dans l'ordre :

1. **Pour tous les inscrits ou seulement l'hôte ?** Ni l'un ni l'autre : pour
   personne. Quand nous l'écrirons, ce sera pour tous les participants
   `CONFIRMED` — `AttendancePromptJob:59` a déjà exactement cette itération, à
   la fenêtre temporelle près.
2. **Replanifié au déplacement, annulé à la suppression ?** Sans objet. Nous le
   construirons sans état planifié : un cron qui balaie les créneaux entrant dans
   la fenêtre T-2h et marque ceux qu'il a traités. Un créneau déplacé est
   rebalayé naturellement ; un créneau annulé passe en `CANCELLED` et sort du
   balayage. Rien à replanifier, rien à annuler — c'est le même modèle que
   `AttendancePromptJob`, qui tourne en production sans planification par
   entité.
3. **`scheduledAt` porte-t-il le début du créneau ?** Oui, et c'est verrouillé.
   `NotificationPayload.ofSchedule` écrit `sessionAt = slot.getStartsAt()`
   (l. 70), et `NotificationDto.extractScheduledAt` relit cette clé sans jamais
   la fabriquer (l. 108-121). Aucun chemin n'y écrit une heure d'envoi. Votre
   décompte vers `scheduledAt` est correct, et le restera.

**Sur votre seuil de deux heures :** il n'y a rien à accorder puisqu'il n'y a
rien en face. Nous prendrons exactement T-2h, ce qui fait tomber le rappel à la
frontière de votre `isImminent`. Un cron ne tirant pas à la seconde, la
notification arrivera **peu après** T-2h, donc du bon côté de votre seuil —
jamais avant. Si vos trois tests testent une inégalité stricte, ils passeront.

**Sur votre dernière phrase**, en revanche, la distinction est faite : « le
rappel n'est pas envoyé » est la bonne hypothèse, et c'était vérifiable sans
téléphone.

---

## Ce que ça change à votre priorisation

Vous avez posé N1 comme bloquante et le reste en aval. L'audit redistribue :

| | Votre priorité | État réel | Reste à faire |
|---|---|---|---|
| **N1** | bloquant | **à moitié servi**, `programTitle` compris | ✅ livré — 6 clés + le filtre d'adresse |
| **N2** | accompagne N1 | **la jointure proposée n'existe pas** | ✅ livré en option A (renommage) |
| **N3** | haute | titre/corps traduits en place | grouper par plateforme, réécrire les formules |
| **N4** | haute | **déjà livré à 120 caractères** | ✅ livré — frontière de mot + garde-fou de taille |
| **N5** | basse | absent | ✅ livré — 2 lignes |
| **N6** | à vérifier | **rien n'existe** | ✅ livré — le job, le vrai chantier de ce lot |

Deux inversions à retenir. **N1 n'est pas le blocage** : votre seuil
`programTitle` est franchi depuis longtemps, et sur les types qui comptent
(`SLOT_JOINED`, `ACTIVITY_ALERT_MATCH`, `ATTENDANCE_PROMPT`) la carte devrait
déjà s'afficher — si elle ne s'affiche pas, le défaut est côté lecture, et c'est
à regarder avant d'attendre quoi que ce soit de nous.

**N6 est le vrai chantier.** Vous l'aviez classé « à vérifier, pas forcément à
développer » ; c'est le seul poste du lot qui demande un job, une fenêtre de
balayage, une idempotence et des tests. Le reste est du remplissage de payload.

---

## Ce dont nous avons besoin pour livrer

1. ~~**N2, la décision A / B / C.**~~ **Tranché : A, livré.** Reste à savoir si
   la carte « message » doit un jour vivre dans la liste in-app — auquel cas
   l'option B devient un lot à part.
2. **N3, le rebours dans une push.** Relatif et vieillissant, ou absolu et
   stable ? Nous sommes partis sur relatif, et **c'est déjà en production** : le
   corps d'un `PROGRAM_REMINDER` calcule le temps restant depuis `sessionAt` à
   l'émission (voir N6). Une push restée deux heures dans le centre affiche donc
   une valeur périmée — exactement ce que votre point de contrat n°1 interdit
   pour la carte in-app, et qu'une push ne peut pas éviter. Si cela ne vous va
   pas, la bascule vers « à 20:00 » est d'une ligne.
3. **Une confirmation sur l'adresse.** Une notification dont le créneau n'est pas
   diffusable arrive **sans** `addressPublic`, y compris pour un participant
   confirmé (voir la restriction en N1, point 3). Confirmez que votre repli « la
   ligne d'adresse disparaît » couvre ce cas — c'est le comportement nominal pour
   un lieu privé, pas un cas dégradé rare. Si vous le jugez inacceptable pour les
   participants confirmés, c'est un lot à part : il faut composer un payload par
   destinataire.

Tout le reste est livré. Voir ci-dessous.

---

## Ce qui est livré

**N1, N2 (option A), N4, N5 et N6 sont dans le code.** Seule N3 reste à faire.

### N1 — les six clés manquantes

`NotificationPayload` les sert désormais sur **tous** les types qui portent une
séance ou un programme, sans qu'aucun émetteur ait à les poser :

| Clé | Source | Ajoutée à |
|---|---|---|
| `authorId` | `userActivity.getUser().getId()` | `ofProgram` |
| `authorName` | `organizerName` → repli `displayName` | `ofProgram` |
| `authorAvatarUrl` | `organizerAvatarUrl` → repli `avatarUrl` | `ofProgram` |
| `categoryIcon` | `Category.icon` | `ofUserActivity` |
| `endsAt` | `Schedule.endsAt` | `ofSchedule` |
| `addressPublic` | `SlotAddressVisibility.broadcastableAddress` | `ofSchedule` |

Le repli `organizerName → displayName` est celui qu'appliquent déjà la fiche du
programme et la carte : une séance ne peut pas avoir deux auteurs selon l'écran
d'où on la regarde. Deux `.with("authorName", …)` qui l'écrasaient dans
`SubscriptionService` ont été retirés pour cette raison.

L'adresse passe par `broadcastableAddress`, une méthode ajoutée à
`SlotAddressVisibility` — la règle reste dans la classe qui la détient, plutôt
qu'une seconde définition qui divergerait un jour de la première.

### N4 — la coupe et le plafond

La coupe de l'aperçu recule jusqu'au dernier espace de la fenêtre. Un texte de
plus de 120 caractères sans aucun espace — une URL, un collage — est coupé net :
il n'y a pas de frontière où reculer.

Le vrai correctif au « rien ne le signale » est ailleurs, et il est livré aussi :
`dataPayload` borne la charge de données à **3 Ko** et sacrifie les clés dans un
ordre explicite — `authorAvatarUrl`, puis `addressPublic`, puis `welcomeNote`,
puis `placeName`. **Chaque éviction laisse un `WARN` en log**, et un dépassement
résiduel un `ERROR` : c'était le point de votre demande, une notification qui
disparaît sans trace. `programTitle` n'est pas évictable et ne doit pas le
devenir — c'est votre seuil.

### N2 — option A, le renommage

`messageAuthorName` et `messageBody` remplacent `senderName` et
`messagePreview`, aux noms de votre template. Les anciens ne sont plus servis :
deux noms pour une même donnée, c'est deux contrats à tenir.

Le renommage touche aussi le texte composé serveur — `buildTitle` et `buildBody`
lisaient les anciens noms. Sans cela, une bannière serait partie en « Nouveau
message de  », suivie du texte de repli, **sans qu'aucune erreur ne soit levée**.
C'est le genre de renommage qui casse en silence ; deux tests le verrouillent
désormais, là où ce chemin n'avait aucune couverture.

Ce qui n'a **pas** changé, et qu'il faut avoir en tête : un `NEW_MESSAGE` ne crée
toujours aucune notification in-app. Ces deux clés ne voyagent que dans la charge
push.

### N6 — le rappel T-2h

Le job existe. Il balaie au lieu de planifier : le `WHERE` de
`findDueForReminder` porte les trois propriétés, sans état planifié à maintenir.

| Clause | Ce qu'elle garantit |
|---|---|
| `status IN ('OPEN','FULL')` | un créneau annulé sort du balayage — annulation sans annuler |
| `startsAt > :now` | pas de salve rétroactive après un arrêt du service |
| `reminderSentFor <> startsAt` | un créneau déplacé redevient éligible — replanification sans replanifier |

D'où une colonne qui mémorise **pour quel `starts_at`** le rappel est parti, et
non un booléen : aucun chemin de déplacement n'a à penser à la remettre à zéro.

Vos trois questions : **tous les inscrits** (hôte, participations `CONFIRMED`,
suiveurs du programme sur ce créneau) ; **oui** pour le déplacement et
l'annulation ; **`scheduledAt` porte bien le début du créneau**, jamais l'heure
d'envoi.

**Cadence de cinq minutes.** Le rappel part peu après T-2h, jamais avant, avec un
retard borné — votre seuil d'imminence est donc franchi du bon côté. Une cadence
horaire aurait fait d'un « rappel deux heures avant » un rappel une heure avant.

### N5 — les deux clés APNs

`mutable-content: 1` et `category: "MEETDO_TEMPLATE"` sur les pushes
**visibles** uniquement. `sendBadgeUpdate` ne les porte pas, et un test le
verrouille : réveiller votre extension pour enrichir une push de fond, qui n'a
pas d'`alert`, n'aurait pas de sens.

La constante est `PushNotificationService.APNS_TEMPLATE_CATEGORY`. Elle est
contractuelle avec vous : la changer d'un seul côté rend l'extension muette.

### Vérification

Suite complète avant et après, sur la même machine :

| | Tests | Échecs | Erreurs |
|---|---|---|---|
| Avant (`master`) | 319 | 7 | 2 |
| Après | 348 | 7 | 2 |

**+29 tests, et exactement les mêmes six classes rouges qu'avant** —
`SecurityInjectionIntegrationTest`, `WebSocketChatIntegrationTest`,
`ChatFlowIntegrationTest`, `AuthServiceTest`, `BusinessErrorCodeIntegrationTest`,
`MapActivitiesIntegrationTest`. Elles échouaient déjà avant ce lot, pour des
causes qui lui sont étrangères (inscriptions en 409, un timeout WebSocket, un
stub Mockito inutile). Aucune régression.

Les 29 tests ajoutés couvrent : les six clés de N1, le repli `organizerName`, un
créneau sans `endsAt`, un programme sans auteur chargé, **les quatre cas de
visibilité de l'adresse** (public / privé partagé / privé non partagé / en
ligne), la coupe sur frontière de mot, l'ordre de sacrifice de la charge, la
présence — comme l'absence — des deux clés APNs, les clés renommées du message et
le fait que les anciennes ne sortent plus, et pour N6 : l'audience du rappel, le
marquage, un destinataire supprimé, plus **le balayage exécuté contre une vraie
base** (créneau annulé écarté, créneau déplacé redevenu éligible, séance
commencée ignorée).

---

## Références du code audité

| Quoi | Où |
|---|---|
| Producteur unique du payload | `domain/notification/NotificationPayload.java` |
| DTO exposé, dérivation de `scheduledAt` | `domain/notification/dto/NotificationDto.java:41-45,108-121` |
| Émission, chemin in-app et chemin push-seul | `domain/notification/NotificationService.java:42,93` |
| Composition du texte push, groupement par langue | `domain/notification/PushNotificationService.java:69-83,229-290` |
| Chemin de la messagerie, troncature à 120 | `domain/chat/ChatService.java:39,110-132` |
| Payload d'un `NEW_MESSAGE` | `domain/chat/ChatPushListener.java:38-47` |
| Contexte d'une conversation (activité, pas programme) | `domain/chat/Conversation.java:35-37` |
| Règle de visibilité du lieu | `domain/program/SlotAddressVisibility.java:28-44` |
| Champs disponibles non servis | `Schedule.java:48,59` · `Category.java:27` · `Program.java:73,76` |
| La ligne mesurée par le client | `db/migration/V27__reset_and_seed_germany.sql:985` |
| Jobs planifiés existants | `attendance/jobs/` · `gdpr/jobs/` · `program/jobs/` |
