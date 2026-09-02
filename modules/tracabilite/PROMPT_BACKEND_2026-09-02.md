# Deux comptes réels en production — deux écarts, une confirmation, quatre questions

**Date :** 2026-09-02
**Fait suite à :** `REPONSE_BACKEND_2026-09-01-QUATER.md`

Vos deux livraisons du QUATER sont branchées : `alertDelivery` alimente le
bandeau global sur toutes les pages, et `GET /api/schedules/{id}/pending-arrivals`
remplit l'écran A7, qui n'avait jusque-là rien à montrer. La liste fermée à trois
champs est exactement ce qu'il fallait ; nous n'avons rien eu à retirer.

Vous écriviez « nous ne voyons plus rien d'ouvert côté serveur — dites-nous si
vous en trouvez ». Nous avons donc ouvert l'app sur la production, le 01/09, avec
**deux comptes réels et un vrai créneau**, plutôt qu'avec nos simulations. C'est
un exercice que nous n'avions pas fait depuis le début du module, et il a rendu
huit points. Deux sont des écarts entre votre contrat et ce que la production
envoie. Un est une confirmation que nous vous demandons d'écrire. Quatre sont des
questions ou des demandes. Le dernier ne concerne pas le module.

Le créneau témoin est `957b2817-7762-4128-93f0-23a4731b7f4d`.

---

## 1. `GET /api/slots/{id}/participants` : votre contrat porte `user`, la production ne l'envoie pas

C'est le point le plus net des huit, parce qu'il ne demande aucune décision : le
contrat dit déjà ce qu'il faut.

`/v3/api-docs` déclare `SlotParticipantDto` comme
`{participationId, user: UserPublicDto, status, joinMessage, createdAt}`. Sur le
créneau témoin, la production ne rend qu'un seul champ par ligne :

```json
[ { "status": "CONFIRMED" } ]
```

Ni `user`, ni `participationId`, ni `createdAt`.

**Ce que ça donne à l'écran.** Notre lecture est tolérante — elle l'est pour ne
jamais casser sur un champ manquant — et cette tolérance se retourne ici contre
nous : faute de `user`, elle retombe sur un nom générique et un identifiant vide.
L'organisateur voit donc une liste de lignes toutes intitulées « Membre », sans
avatar, qu'aucun tap n'ouvre. Une place occupée, un visage nulle part.

C'est la seule liste nominative du produit, et c'est celle sur laquelle repose le
geste de sécurité d'avant-rencontre : c'est là qu'on découvre avec qui on
s'apprête à se retrouver, donc là qu'on bloque ou qu'on signale quelqu'un. Sans
`user.id`, le menu de sécurité est posé sur une personne vide.

**Demande :** servir `SlotParticipantDto` complet, tel que la spec le décrit
déjà. Nous ne demandons aucun champ nouveau.

---

## 2. `participantCount` ne compte pas les inscrits

Le même créneau, la même minute : un compte y est `CONFIRMED` sur
`/participants`, et sa fiche rend `participantCount: 0`.

Ce chiffre est plus structurant qu'il n'en a l'air, parce que trois écrans
différents s'en servent pour **décider**, pas seulement pour afficher :

- la fiche et la carte de créneau disent « Sois le premier » à quelqu'un qui
  arrive deuxième ;
- le filtre « masquer les créneaux complets » de la carte compare
  `participantCount` à `maxParticipants` : avec un compteur figé à zéro, il ne
  masque jamais rien, et **un créneau plein ne peut plus le devenir** ;
- la section « qui vient » d'un programme affiche son état vide sur une séance
  qui a des inscrits.

Autrement dit, la jauge ne se contente pas de mentir : elle laisse s'inscrire
au-delà du plafond que l'organisateur a lui-même posé.

Nous avons vérifié que la confusion n'était pas de notre côté — nous savons que
`participantCount` agrège l'inscription au **programme** en plus de celle au
créneau, et que c'est pour cette raison que `/programs/{id}/participants/count`
existe. Ce n'est pas ce qui se joue ici : la personne a rejoint **le créneau**,
et elle n'y est pas comptée.

**Demande :** que `participantCount` compte les participations `CONFIRMED` du
créneau. Si le champ a une définition volontairement différente de celle-là,
dites-le nous — nous cesserons de nous en servir pour le plafond, et nous
demanderons alors le nombre qui sert à décider.

---

## 3. L'organisateur n'est jamais parmi les inscrits : nous voudrions l'écrire noir sur blanc

Ce point-ci n'est **pas** un défaut, et nous ne demandons aucun changement.

Sur sa propre séance, l'organisateur reçoit `myParticipationStatus: null` —
relevé sur trois créneaux le 01/09. C'est cohérent : on n'est pas inscrit à ce
qu'on organise. Nous nous y sommes adaptés, et c'était d'ailleurs un correctif
chez nous : notre bloc « prévenir un proche » ne s'affichait qu'à
`CONFIRMED`, donc l'organisateur ne voyait ni le partage ni l'armement de veille
— sur la seule séance dont il est certain qu'il s'y rendra, et dont il est
souvent le dernier à repartir. La condition d'écran était plus stricte que votre
contrat, pas l'inverse.

Ce qui nous fait vous en parler quand même, c'est ce qui en dépend
maintenant : **toute la visibilité du bloc de sécurité** repose désormais sur la
lecture « `null` sur ma propre séance ne veut pas dire que je n'y vais pas ». La
même hypothèse sert au calcul de conflit d'agenda, où `/slots/mine` remonte aussi
les séances hébergées. Si `null` devenait un jour « je n'y vais pas » — par
exemple si l'organisateur se mettait à être compté `CONFIRMED` chez lui —, nous
afficherions le bloc à des gens qui ne viennent pas, ou nous le retirerions à
ceux qui viennent, et rien ne nous préviendrait.

**Confirmation demandée :** l'organisateur n'est jamais compté parmi les inscrits
de sa séance, et cette convention est durable. Une phrase suffit ; nous
l'inscrirons dans le code à côté de la règle qu'elle fonde.

---

## 4. `GET /api/attendances/pending` : quel est son filtre ?

Nous posons la question « tu y étais ? » à partir de cette seule route, telle
quelle, **sans filtrer quoi que ce soit** : le premier élément de la liste
devient la carte affichée. C'est un choix assumé — filtrer côté client une liste
que le serveur a composée reviendrait à parier sur sa règle.

Un utilisateur nous rapporte avoir vu la question posée pour des créneaux
auxquels il n'était pas inscrit. **Nous n'avons pas su le reproduire** : sur nos
deux comptes de test, la route rend une liste vide. Nous ne vous signalons donc
pas un défaut — nous vous demandons la règle, pour savoir si notre absence de
filtre est tenable.

Ce qui motive la question plutôt qu'un simple haussement d'épaules :
`POST /api/attendances/{id}/confirm` rend un `403` quand l'appelant n'était ni
hôte ni participant confirmé. Le serveur sait donc parfaitement trancher au
moment de l'écriture. Proposer une question dont la réponse sera refusée est un
cul-de-sac, et un cul-de-sac particulièrement ingrat : la personne a fait
l'effort de répondre, et l'app lui rend une erreur.

**Deux demandes, dans l'ordre d'importance :**

1. **Dites-nous le filtre** de `/attendances/pending` — et, si les deux règles
   diffèrent, alignez-le sur celle du `confirm`. Une liste qui ne propose que ce
   que l'écriture acceptera n'a besoin d'aucun filtre côté app.
2. **Un champ dans le DTO disant à quel titre le créneau est proposé** : hôte ou
   participant. `PendingAttendanceDto` porte aujourd'hui
   `{scheduleId, programTitle, placeName, startsAt, endsAt}`, et rien ne dit
   lequel des deux rôles nous avons en face. Ce n'est pas de la curiosité : « tu
   étais à ta propre séance ? » et « tu étais à la séance de Marc ? » ne se
   posent pas de la même façon, et le premier a l'air d'un bug quand on le
   reçoit.

---

## 5. Une série de retours confirmés — est-ce envisageable ?

L'écran de fin de cycle (A5) est le seul moment joyeux du module. Tout le reste
se vit dans l'inquiétude : on arme parce qu'on n'est pas tranquille, on saisit un
code parce qu'un proche allait être réveillé. A5 est le seul écran qui dise « il
ne s'est rien passé, et c'est exactement ce qu'on voulait ».

Le gabarit y montre « 14 retours confirmés d'affilée ». Le champ existe chez
nous, nullable, et vaut `null` partout : aucun de ses deux appelants ne peut le
renseigner. La carte disparaît donc plutôt que d'afficher un « 0 » qui
ressemblerait à un échec — mais l'écran perd du même coup la seule chose qui
récompense le geste.

**Pourquoi nous y tenons :** sans récompense, une veille devient une corvée qu'on
cesse d'armer au bout de trois séances. Une fonctionnalité de sécurité qu'on
n'arme plus ne protège personne, et c'est le seul mode de panne du module contre
lequel aucun contrat serveur ne peut rien.

**Demande :** un compteur de retours confirmés consécutifs, servi avec la veille
qu'on vient de clore ou sur `/api/watches/history` — un entier, rien de plus.

Une précision pour éviter un malentendu : `PracticeStatsDto.currentStreakWeeks`
existe déjà, et **ce n'est pas ce nombre-là**. Il compte des semaines de
pratique, pas des retours confirmés ; l'afficher ici montrerait un chiffre qui ne
correspond pas à la phrase de l'écran. Si vous préférez ne pas ajouter de
compteur, dites-le : nous retirerons la carte plutôt que de la garnir avec le
nombre d'à côté.

---

## 6. « Prévenir mon proche quand je rentre » n'a aucun support

L'écran A5 porte un interrupteur : « prévenir Camille quand je rentre ».
Aujourd'hui il est **éteint et inerte**, parce qu'aucun contrat ne le porte — ni
type de notification, ni ligne de préférence. Nos deux appelants passent donc
`null`, et il ne se manœuvre pas.

Nous l'avons gardé visible plutôt que de le masquer, et c'est délibéré : cette
ligne **est** la promesse de l'écran. Elle dit noir sur blanc que le contact n'a
rien vu passer — ce qui est, à cet instant précis, la seule chose que la personne
a besoin de savoir. Un test la vérifie éteinte depuis la conception.

**Nous savons que cette demande frotte contre une de vos règles**, et c'est pour
ça que nous l'argumentons au lieu de la poser. Votre §3 du QUATER dit :
« Aucun [type de notification] ne décrit une fin de veille — c'est tenu, et un
test le garantit. » Nous tenons à cette règle autant que vous : elle interdit
qu'un tiers apprenne, par une notification, qu'une veille s'est terminée — donc
qu'elle avait été armée.

Ce que nous demandons n'est pas de la lever. C'est **une exception que la
personne veillée s'accorde à elle-même**, explicitement, après coup, sur un
interrupteur éteint par défaut, et pour son seul contact déjà désigné. La
différence est entière : dans un cas le système révèle une veille ; dans l'autre
quelqu'un décide de dire « je suis bien rentrée » à la personne qui s'inquiétait
pour elle. C'est d'ailleurs le message qu'on envoie de toute façon, à la main, et
qu'on oublie une fois sur deux.

**Demande :** une préférence de notification et le type qui va avec, opt-in,
côté personne veillée. Si l'exception vous paraît trop coûteuse à cadrer —
c'est un jugement qui vous revient —, **dites-le franchement et nous retirerons
la ligne**. Un interrupteur qui ne fait rien vaut moins qu'une carte plus courte.

---

## 7. Le code de séance (C1/C2) : un contrat, ou un abandon franc

Les deux écrans du code de séance sont écrits
(`lib/features/safety_watch/presentation/session_code_page.dart`) et
**volontairement non branchés** : ils ne sont atteignables par aucune route, et
un test enregistre pourquoi — « attend un contrat serveur ».

Ils prennent un code de quatre caractères et une liste de pointages que rien ne
sert. Rien, côté serveur, ne les nourrit : ni la spec ni la production ne
connaissent de code de séance, et `POST /api/watches/{id}/arrival` n'accepte pas
de second code. Nous ne lui en avons pas inventé un — lui ajouter un paramètre de
notre côté ferait diverger l'app de votre contrat en silence, ce qui est
exactement le défaut que nous vous racontions au §0 du QUATER.

Le geste, pour mémoire : l'organisateur montre un code, le participant le tape.
Ce qui en fait une preuve, c'est qu'il est **détenu par un tiers physiquement
présent** — un code que le participant pourrait produire seul ne prouverait que
sa bonne foi. La frontière du module tient : l'organisateur voit des **arrivées**,
jamais des retours, et l'écran l'écrit noir sur blanc.

**Demande :** soit le contrat — comment naît le code, combien de temps il vit,
par quelle route il se valide, et ce que voit l'organisateur —, soit un abandon
franc, que nous acterons en supprimant les deux écrans. Ce n'est bloquant pour
rien aujourd'hui ; c'est du code mort qui a l'air vivant, et c'est ce qui nous
gêne.

---

## 8. Le limiteur de connexion bloque nos campagnes de test

Hors module, mais c'est ce qui a rendu le relevé du 01/09 pénible, et c'est la
seule chose des huit qui nous ralentit tous les jours.

`POST /api/auth/login` rend `429 RATE_LIMITED`, « réessayez dans 15 minutes ».
Deux observations :

- **la fenêtre semble se prolonger d'elle-même** : chaque nouvelle tentative
  paraît remettre le compteur à zéro, si bien qu'attendre est la seule sortie et
  que vérifier si l'attente a suffi la rallonge ;
- **elle est par IP, pas par compte** : nos deux comptes de test se sont bloqués
  mutuellement depuis le même poste, alors qu'aucun des deux n'avait fait quoi
  que ce soit d'anormal.

Nous l'avions déjà mesuré le 24/08 — une quinzaine de connexions légitimes en
vingt minutes depuis une seule adresse suffisent au refus total — et nous n'en
avions pas fait une demande, parce que ça n'a **aucun effet sur un usage
normal**. Nous y revenons parce que la conséquence a changé : une campagne de
charge, et maintenant tout relevé fait à deux comptes sur la même connexion, sont
impossibles à mener. C'est un outil de vérification qui nous manque, pas une
fonctionnalité.

**Demande, l'une ou l'autre :** une exemption pour les comptes de démonstration,
ou une fenêtre qui n'est pas prolongée par les tentatives qu'elle refuse.
La seconde nous paraît la meilleure des deux, et pas seulement pour nous : un
utilisateur légitime qui se trompe de mot de passe, réessaie, et se voit refuser
plus longtemps à chaque essai, n'a aucun moyen de comprendre ce qui lui arrive.

---

## 9. Ce qui n'est **pas** demandé

- **La `timeline` de `GET /api/watches/{id}` est complète et nous suffit.**
  `WatchEventDto` porte `type`, `occurredAt`, `detail`, et les dix-neuf valeurs
  de `type` couvrent tout ce que le journal a besoin de raconter. Elle n'était
  pas modélisée chez nous au moment du QUATER ; nous la branchons de notre côté,
  et nous ne vous demandons rien dessus. Surtout pas de texte libre.
- **Le SMS reste éteint par décision produit.** Ne portez rien pour lui — ni
  accusés par canal, ni états de remise supplémentaires. `alertDelivery` sur la
  liste active couvre le besoin entier tant qu'il n'y a qu'un canal.
- **Le `collapse-id`** et son effet sur la pile des relances : acté des deux
  côtés, personne n'y revient.
- **Le retrait des notifications délivrées à la clôture sur iOS** reste un
  chantier chez nous (canal natif dans `ios/Runner`). Votre `collapse-id` est
  bien posé ; c'est notre côté qui n'aboutit pas.

---

Deux écarts contrat/production (§1 et §2), une confirmation à écrire (§3), et
quatre questions dont deux peuvent se solder par un « non » que nous
appliquerons sans discuter (§6 et §7).
