# Réponse au 02/09 — un écart reproduit, un introuvable, six réponses

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02.md`

> **Votre campagne à deux comptes réels valait tous nos tests.** Elle a trouvé un
> défaut que nos 939 tests laissaient passer depuis le début, parce qu'aucun
> d'eux n'avançait un créneau récurrent. Refaites-la.
>
> **§2 est reproduit, corrigé, et il était plus large que ce que vous avez vu**
> (§2 ci-dessous). **§4 en découlait** : la question « tu y étais ? » vous
> revenait chaque semaine sur le même créneau, et nous avons trouvé pourquoi.
> **§8 est corrigé**, et il était pire que votre relevé.
>
> **§1 est le seul que nous n'arrivons pas à reproduire** — et nous découvrons
> qu'on ne pouvait même pas savoir quel code tourne en production. C'est réparé,
> et nous avons une demande précise pour vous.
>
> **§3 est confirmé** noir sur blanc et verrouillé par un test.
> **§5 et §6 sont oui**, avec pour chacun un détail de conception que vous
> n'aviez pas en main et qui change la façon de les servir.
> **§7 est non**, franchement, et argumenté : supprimez les deux écrans.

---

## 1. `/api/slots/{id}/participants` : nous ne reproduisons pas, et c'est notre faute si nous ne pouvons rien conclure

Vous aviez raison de dire que ce point ne demandait aucune décision. Il en
demande une quand même, mais pas celle qu'on croyait.

**Le code sert les cinq champs.** `SlotService.getParticipants` construit
`participationId`, `user`, `status`, `joinMessage` et `createdAt` ; le DTO n'a
pas changé depuis le 24 juillet ; il n'y a nulle part de configuration Jackson
qui omettrait les nuls, ni de filtre de réponse. Sur une base réelle, la route
rend ceci :

```json
[{"participationId":"7ee3d7a8-…",
  "user":{"id":"0c1019bc-…","displayName":"Participant","bio":null,…},
  "status":"CONFIRMED","joinMessage":null,"createdAt":"2026-09-01T21:45:51.547886Z"}]
```

`joinMessage` est là, à nul. C'est ce qui nous fait dire que la réponse que vous
avez relevée ne peut pas sortir de ce code : un champ nul y sortirait
`"user": null`, jamais une clé absente. Un test le fige désormais, champ par
champ, `user.id` compris.

**Ce que nous ne savions pas dire, et que nous savons maintenant.** En cherchant,
nous sommes tombés sur plus embarrassant que le point lui-même : **rien ne
permettait de savoir quel build tourne en production.** `/actuator/info` figurait
bien dans la liste des points exposés, mais la sécurité ne le laissait pas
passer — et il n'aurait de toute façon rendu qu'un objet vide, faute que la
construction y grave quoi que ce soit. Nous ne pouvions donc ni confirmer ni infirmer
que le serveur que vous avez interrogé exécute le code que nous lisons — ce qui
rend toute discussion « contrat contre production » impossible à instruire, et
pas seulement celle-ci.

C'est réparé. Le jar porte désormais son identité, et la route est ouverte sans
authentification — elle sert précisément quand plus rien d'autre ne répond comme
attendu :

```
GET /actuator/info
  → { "build": { "version": …, "time": …, "commit": "<sha déployé>", … } }
```

**Notre demande :** au prochain relevé, joignez-nous la sortie de
`/actuator/info` et la réponse HTTP brute — statut, en-têtes, corps non
retraité. Si le commit correspond à ce que nous lisons et que le corps est bien
amputé, nous aurons quelque chose à instruire. Nous penchons aujourd'hui pour un
build antérieur ou un outil de relevé qui élide les nuls à l'impression, mais
nous ne voulons pas le supposer à votre place.

---

## 2. `participantCount` : reproduit, et il y avait trois trous, pas un

Votre relevé était juste, et la cause est plus large que le symptôme.

**Le rollover récurrent remettait le compteur à zéro sans retirer personne.**
Un créneau récurrent est avancé à son occurrence suivante toutes les dix
minutes par un job. Ce job faisait `participantCount = 0` — mais les
participations, elles, restaient `CONFIRMED`. D'où très exactement votre relevé :

```
>>> APRES ROLLOVER  participant_count=0   inscrits CONFIRMED=1
>>> PARTICIPANTS APRES ROLLOVER = [{…,"status":"CONFIRMED",…}]
```

Il disait « nouvelle occurrence, nouvelles places » — mais il était **seul** à le
dire : `/participants`, `myParticipationStatus`, `/slots/mine` et
`/attendances/pending` traitaient tous l'inscription comme tenant d'une semaine
sur l'autre. C'est cette lecture-là que nous retenons, parce qu'elle était déjà
celle de tout le reste : **une inscription à un créneau récurrent est un
engagement qui tient d'une occurrence à la suivante.** Le compteur est donc
recalculé, plus remis à zéro.

**Deuxième trou, que vous ne pouviez pas voir :** `POST /api/programs/{id}/join`
avec un `scheduleId` prenait une place — le décompte agrège bien les deux sources
— mais ne réécrivait jamais le compteur ni ne passait le créneau en `FULL`.
Rejoindre par le programme prenait donc une place que la fiche continuait
d'annoncer libre. `POST /programs/{id}/leave` avait le défaut symétrique : la
place n'était jamais rendue, et un créneau `FULL` ne rouvrait plus jamais.

**Ce qui change chez vous :** rien à faire. `participantCount` compte désormais
les places prises, toutes sources confondues, sur tous les chemins. Votre filtre
« masquer les créneaux complets » redevient exact, et **un créneau plein peut de
nouveau le devenir** — un créneau récurrent complet le reste après rollover, au
lieu de rouvrir des places qui n'existent pas.

Le compteur n'a plus qu'un seul endroit qui l'écrit (`ParticipantCounter`), au
lieu de cinq chemins qui le recopiaient chacun à sa façon — dont deux qui
l'oubliaient et un qui l'effaçait. Quatre tests le figent, dont celui du créneau
récurrent complet et celui de la place rendue en quittant un programme.

**Un troisième trou, trouvé en bouchant le deuxième.** La capacité d'un créneau
est partagée entre les deux formes d'inscription ; sa **liste d'attente** l'est
donc aussi. Or seul le départ par le créneau la faisait remonter. Une place
rendue en quittant le programme restait libre pendant que quelqu'un l'attendait,
et « vous êtes 1er » ne devenait jamais rien. Les deux chemins passent désormais
par le même code de promotion, sous le même verrou. Vous n'avez rien à faire :
`myParticipationStatus` passe de `WAITLISTED` à `CONFIRMED` comme sur l'autre
chemin, et la notification `WAITLIST_PROMOTED` part comme avant.

---

## 3. L'organisateur n'est jamais parmi les inscrits — confirmé, et durable

**La phrase que vous demandez :** l'organisateur d'une séance n'est jamais compté
parmi ses inscrits. `myParticipationStatus` vaut `null` chez lui, sur sa propre
séance, et cela ne changera pas.

Ce n'est pas une convention d'affichage, c'est une conséquence de trois refus
posés à l'écriture : on ne peut pas rejoindre son propre créneau
(`SLOT_OWN_SLOT`), ni se mettre en attente du sien (`SLOT_OWN_SLOT`), ni
s'inscrire à son propre programme (`PROGRAM_OWN_PROGRAM`). Aucun chemin ne crée
de participation pour l'hôte ; il n'y a donc pas de règle à tenir, il y a une
situation qui ne peut pas se produire.

Nous avons ajouté le test qui le dit, avec vos deux usages nommés dedans — la
visibilité du bloc de sécurité, et le calcul de conflit d'agenda — pour que
quiconque voudrait un jour compter l'organisateur `CONFIRMED` chez lui tombe
dessus avant de le faire.

Votre correctif était le bon, et il était plus juste que votre condition
d'origine : sur la seule séance dont il est certain qu'il s'y rendra, et dont il
est souvent le dernier à repartir, l'organisateur doit voir le partage et
l'armement de veille.

---

## 4. `/api/attendances/pending` : le filtre, et pourquoi vous avez vu ce que vous avez vu

**Le filtre, en toutes lettres.** Une séance vous est proposée si et seulement si
les trois conditions sont réunies :

1. vous l'hébergez, **ou** vous y avez une participation de créneau `CONFIRMED`,
   **ou** vous avez une inscription de programme `ACTIVE` rattachée à ce créneau ;
2. une occurrence est terminée ;
3. vous n'avez pas déjà répondu **pour cette occurrence-là**.

**Ce sont les trois mêmes sources que le `confirm` interroge avant de rendre son
`403`.** Les deux routes lisent le même code, et un test le vérifie des deux
côtés à la fois : un tiers ne se voit rien proposer et reçoit `403` s'il insiste ;
ce qui est proposé passe. **Votre absence de filtre est tenable**, et nous vous
demandons de ne pas en ajouter : il parierait sur une règle qui vit ici.

**Ce que votre utilisateur a probablement vu.** Nous avons reproduit deux
situations qui donnent l'impression d'une question hors sujet, et aucune n'est un
défaut de filtre :

- **une inscription à un créneau hebdomadaire repose la question chaque
  semaine**, indéfiniment. Reproduit sur trois semaines d'affilée. C'est la
  conséquence directe du §2 : l'inscription tient d'une occurrence à la suivante.
  Qui ne veut plus qu'on la lui pose quitte le créneau ;
- **un hôte reçoit la question sur tous les créneaux qu'il héberge**, y compris
  ceux qu'il a publiés et oubliés. Vu de l'écran, « je n'étais pas inscrit à
  ça » est exact : on ne s'inscrit pas à ce qu'on organise.

**Votre §4.2 est fait.** `PendingAttendanceDto` porte un champ de plus :

```
role : "HOST" | "PARTICIPANT"
```

Les deux valeurs sont exclusives par construction — voir §3 : un hôte n'est
jamais aussi participant. Vous pouvez donc poser « tu étais à ta propre séance ? »
et « tu étais à la séance de quelqu'un d'autre ? » sans deviner.

---

## 5. La série de retours confirmés : oui — et elle se compte sur autre chose que ce qu'on croit

**C'est oui**, et votre argument a emporté la décision tel quel : une veille
qu'on n'arme plus ne protège personne, et c'est le seul mode de panne du module
contre lequel aucun contrat serveur ne peut rien.

```
GET /api/watches/{id}
  → { watch, timeline, alertDelivery, consecutiveConfirmedReturns }
```

Un entier, jamais nul — zéro est zéro. Servi sur le détail plutôt que sur la
clôture : `POST /watches/{id}/close` rend un `202` au corps vide, et c'est
délibéré — c'est la clause d'indistinguabilité. Nous n'avons pas voulu y toucher
pour un compteur. A5 relit la veille qu'elle vient de refermer.

**Ce que vous n'aviez pas en main, et qui change tout.** Une clôture **sous
contrainte** ne laisse pas la veille dans l'état `CLOSED` : elle la laisse en
`ESCALATED`, parce qu'elle ne referme rien. Une série calculée sur l'état aurait
donc affiché **un nombre différent au moment précis où l'écran est regardé par
la personne qui contraint**. Nous aurions livré, sans le voir, un révélateur de
code de contrainte sur le seul écran joyeux du module.

Elle se calcule donc sur l'événement `CLOSED_BY_CODE`, que les deux clôtures
écrivent à l'identique. Trois issues, une seule rompt :

- refermée par un code — elle compte, **contrainte comprise** ;
- mal finie sans code (escalade, abandon, perdue en chemin) — la série s'arrête ;
- désarmée avant le départ, ou encore en cours — ni comptée ni rompante. Il n'y
  avait pas de retour à confirmer.

Cinq tests, dont un qui vérifie qu'une clôture sous contrainte incrémente bien la
série comme une clôture normale.

Et vous aviez raison sur `PracticeStatsDto.currentStreakWeeks` : ce n'est pas ce
nombre-là, il ne le sera jamais, ne l'affichez pas ici.

---

## 6. « Prévenir mon proche quand je rentre » : oui — sans créer de type de notification

**C'est oui**, et votre argumentation est celle qui nous a convaincus : la
différence entre « le système révèle une veille » et « quelqu'un décide de dire
qu'elle est bien rentrée » est entière. Nous ne levons pas notre règle ; nous la
laissons exactement où elle est.

**Nous ne créons aucun `NotificationType`, et c'est le cœur de la réponse.** En
regardant comment le servir, nous avons trouvé deux choses qui auraient fait
échouer l'implémentation naïve — celle que vous demandiez, de bonne foi, et que
nous aurions livrée :

- **les préférences de notification sont opt-out chez nous**, pas opt-in. Un
  nouveau type serait arrivé **allumé** par défaut chez tout le monde ;
- **la notification in-app est écrite systématiquement**, préférence ou pas. Le
  proche aurait eu une ligne « elle est rentrée » dans son centre de
  notifications, sans que personne ne l'ait demandé.

Autrement dit : passer par le catalogue de notifications aurait produit
l'exact contraire de votre demande. L'interrupteur devait vivre du côté de la
personne veillée et conditionner **l'envoi lui-même**. C'est ce qu'il fait :

```
POST /api/watches/{id}/close
  { code, enteredAt, notifyGuardian? }     ← facultatif, faux par défaut
```

Absent ou faux : personne n'est prévenu, exactement comme aujourd'hui. Vrai : un
message part vers le contact principal déjà désigné et **consentant**, sur son
canal.

**Trois garde-fous, qui sont la contrepartie du oui :**

- **le message ne parle pas de veille.** « *Camille est bien rentrée.* » Ni le
  dispositif, ni le lieu, ni l'heure limite, ni l'activité — tout ce qu'on
  ajouterait recomposerait la veille dans la tête du destinataire. Un test
  vérifie l'absence de ces mots dans le corps envoyé ;
- **rien ne part sous contrainte.** Le drapeau est ignoré sur cette branche :
  rassurer le proche pendant qu'une escalade silencieuse part serait l'exact
  contraire de ce que la personne vient de demander. Testé ;
- **rien ne part non plus si une alerte était partie** — le contact reçoit alors
  la levée, qui n'est pas facultative et dit déjà que tout va bien.

**Votre bandeau global n'est pas touché**, et il s'en est fallu de peu. Nous avons
d'abord rattaché ce message à la veille dans la file d'envoi, comme tous les
autres — ce qui aurait fait passer `alertDelivery` de `NONE` à `SENT` sur une
veille où aucune alerte n'est jamais partie, et affiché votre bandeau d'alarme le
jour où un « tout va bien » rebondit. L'annonce n'est donc rattachée à rien :
`alertDelivery` continue de ne parler que d'alertes. Un test le vérifie.

Un envoi vers un tiers laisse une trace dans le journal de la personne :
`WatchEventType` gagne `RETURN_ANNOUNCED`. Nous savons que vous nous demandiez de
ne rien toucher à la `timeline` ; nous faisons l'exception, parce qu'un message
sortant qu'aucun incident ne motive est précisément celui dont il faut pouvoir
rendre compte. Le journal n'est rendu qu'à la propriétaire de la veille :
l'inscrire n'apprend rien à personne d'autre. Vous n'avez rien à en faire.

Votre §3 du QUATER et le test qui le garde sont intacts. Un second test, du côté
de l'exception, vérifie qu'aucun type de notification n'a été créé pour ce
message — pour que l'exception ne devienne pas la brèche.

**Allumez l'interrupteur.**

---

## 7. Le code de séance (C1/C2) : non — supprimez les deux écrans

C'est un non franc, comme vous nous l'avez demandé, et il ne tient pas au coût.

Le geste que vous décrivez **existe déjà, autrement.** L'organisateur dispose de
`POST /api/watches/{id}/seen-by-host` : « je la vois, elle est là ». Le journal
l'inscrit, et la relance d'arrivée est repoussée. C'est bien un tiers
physiquement présent qui atteste — exactement votre argument.

Ce que ce geste ne fait délibérément **pas**, c'est valider l'arrivée à la place
de l'intéressée ni créer de code. Cette frontière est écrite dans le code depuis
la conception, et voici pourquoi nous n'en bougeons pas : **l'arrivée est ce qui
fait naître le code de retour**, donc ce qui arme la mécanique qui pourrait
réveiller un proche. Un code détenu par l'organisateur ferait dépendre le
déclenchement de cette mécanique d'un tiers — et il en ferait aussi un point de
pression : quelqu'un qui refuse de donner le code, ou qui le donne sous
condition, tient la personne. Le module a été construit pour que ce geste-là
appartienne à l'intéressée seule, et c'est la seule chose qu'on ne partage pas.

Un contrat de code de séance ne s'ajouterait donc pas à l'existant : il
remplacerait une décision de conception par son contraire, pour une preuve
marginalement plus forte. Nous préférons vous le dire nettement plutôt que de
vous laisser un chantier ouvert.

**Supprimez `session_code_page.dart` et son test.** Vous aviez raison de nous
dire que le code mort qui a l'air vivant est ce qui gêne.

---

## 8. Le limiteur de connexion : corrigé, et il était pire que ce que vous avez mesuré

Vos deux observations étaient justes toutes les deux, et la première était même
en dessous de la vérité.

**Ce que nous avons trouvé :**

- **le compteur ne redescendait jamais.** « Dix par quinze minutes » était en
  réalité « dix en tout, pour la durée de vie du processus ». Le plafond était de
  dix : votre quinzaine de connexions en vingt minutes ne pouvait pas passer, et
  aucune attente ne l'aurait fait passer non plus ;
- **les connexions réussies comptaient** autant que les échouées. Travailler
  normalement consommait le budget ;
- **la clé était l'adresse IP seule**, d'où vos deux comptes qui se bloquaient
  mutuellement ;
- **une fois le verrou expiré, dix requêtes suffisaient à le refermer** — d'où
  votre impression, exacte dans ses effets, que vérifier si l'attente a suffi
  rallonge l'attente.

**Ce que nous avons fait — c'est votre seconde proposition, celle que vous
préfériez, et vous aviez raison de la préférer :**

- **une fenêtre glissante qui glisse vraiment.** Chaque clé garde les
  horodatages de ses échecs et oublie ceux qui sont sortis de la fenêtre. Le
  budget se reconstitue tout seul, minute après minute ;
- **seuls les échecs consomment.** La vérification ne consomme rien, et une
  connexion réussie remet le compteur du compte à zéro. **Une campagne à deux
  comptes depuis un même poste ne consomme plus rien du tout**, tant que les
  mots de passe sont bons — quel qu'en soit le nombre ;
- **deux clés, deux budgets.** Le budget serré (10 échecs / 15 min) est sur le
  **compte**, qui est ce qu'une attaque vise. L'adresse garde un plafond large
  (50 échecs / 15 min), qui n'existe que pour borner un balayage de plusieurs
  comptes depuis un même point ;
- **un refus n'ajoute rien.** Piétiner devant la porte ne la referme pas.

Pas d'exemption pour les comptes de démonstration : votre seconde proposition la
rend inutile, et vous aviez raison sur le fond — un utilisateur légitime qui se
trompe de mot de passe et se voit refuser plus longtemps à chaque essai n'a
aucun moyen de comprendre ce qui lui arrive.

Douze tests, dont celui qui franchit le bord de la fenêtre à l'horloge réglable —
une fenêtre glissante ne se prouve qu'en la traversant.

Au passage : un second limiteur, complet et correct, dormait dans le dépôt
derrière un drapeau éteint, injecté nulle part. Deux limiteurs dont un mort est
une invitation à corriger le mauvais ; il est supprimé.

---

## 9. Ce que nous n'avons pas touché

- **La `timeline`** : rien changé, sauf l'ajout de `RETURN_ANNOUNCED` expliqué au
  §6, qui n'a d'existence que si vous allumez l'interrupteur. Toujours pas de
  texte libre.
- **Le SMS** : rien porté pour lui. `alertDelivery` reste le seul retour de
  remise, sur la liste active et sur le détail.
- **Le `collapse-id`** : acté, personne n'y revient.
- **`POST /close`** rend toujours `202` au corps vide, `409` avec `attemptsLeft`
  sur code faux. Le champ `notifyGuardian` ne change rien à ces réponses.

---

## Récapitulatif des changements de contrat

| Route | Changement |
|---|---|
| `GET /api/slots/{id}` et le fil | `participantCount` compte enfin les places prises, toutes sources confondues |
| `GET /api/attendances/pending` | nouveau champ `role` : `"HOST"` \| `"PARTICIPANT"` |
| `GET /api/watches/{id}` | nouveau champ `consecutiveConfirmedReturns` (entier, jamais nul) |
| `POST /api/watches/{id}/close` | nouveau champ de requête facultatif `notifyGuardian` (faux par défaut) |
| `GET /actuator/info` | ouvert, rend l'identité du build déployée (version, heure, commit) |

Aucune suppression, aucun champ renommé, aucun changement de type. Tout ce qui
existait continue de répondre à l'identique.

**Ce que nous attendons de vous, dans l'ordre :** la sortie de `/actuator/info`
et la réponse brute de `/participants` au prochain relevé (§1) ; la suppression
des deux écrans du code de séance (§7) ; l'allumage de l'interrupteur du §6 et
de la carte du §5.

Et refaites la campagne à deux comptes réels. Elle a rendu, en une soirée, ce que
939 tests ne voyaient pas.
