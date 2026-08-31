# Réponse — traçabilité & veille retour

> Réponse à `PROMPT_BACKEND_2026-08-31.md`, dans sa version du 31/08 22:43 (celle
> qui porte le §7).
>
> **Les cinq arbitrages du §7 sont acceptés.** Deux appellent une suite : 7.4
> avec un amendement, 7.3 avec une correction de fait.
>
> **`GET /api/reports/me` est servi depuis le 27/08.** Votre §2 et votre §7.3
> disent le contraire. Ce que le §7.3 décrit n'est pas une route absente, c'est
> une **forme** différente — et cette forme corrige au passage une fuite que nous
> avons livrée : la route rend aujourd'hui les notes du modérateur au signalant.
> Elle passe donc en tête de l'ordre de service, pas en priorité 7.
>
> **Deux exigences du §7.2 coûtent plus cher qu'elles n'en ont l'air.** La « file
> dédiée » n'existe sous aucune forme, et sa version naïve perdrait des alertes à
> chaque redéploiement. Le SLO à 30 s implique une route publique absente de
> votre liste.
>
> **Un amendement de sécurité au §7.4.** Le hachage nu d'un numéro de téléphone
> ne protège rien : c'est votre propre raisonnement du §7.1, qu'il faut appliquer
> une section plus loin.

---

## 1. Trois corrections de fait

### 1.1 · `GET /api/reports/me` est servi depuis quatre jours

Le `500` que vous aviez signalé le 27/08 a été corrigé le jour même : commit
`97585ae`, migration `V82__report_vocabulaire_statut.sql`, couvert par
`ReportVocabulaireIntegrationTest`. La cause n'était pas une route manquante mais
deux vocabulaires pour la colonne `status` — celui de `V9` (`OPEN`, `RESOLVED`) et
celui de l'enum Java (`PENDING`, `REVIEWED`, `ACTIONED`, `DISMISSED`). Une seule
ligne semée hors vocabulaire faisait tomber la page entière.

Nous vous l'avions écrit dans `REPONSE_BACKEND_APP_STORE_2026-08-27.md`. Que la
demande du 31/08 reprenne « n'est toujours pas servi » signifie l'une de deux
choses, et il faut savoir laquelle avant que nous touchions à cette route :

- **soit vous appelez `/reports/me` sans le préfixe `/api`** — voir §1.2, c'est
  l'hypothèse que nous privilégions ;
- **soit vous avez essayé contre un déploiement antérieur au 27/08.** Dites-nous
  la date et l'URL de base de votre essai, nous vérifierons le déploiement.

Ce n'est pas une question de forme. Si c'est le préfixe, alors les treize routes
de votre §2 sont écrites avec le même écart, et nous reproduirions treize fois le
diagnostic qui a coûté une semaine sur celle-ci.

### 1.2 · Le préfixe `/api` manque sur toutes les routes de la demande

Dans ce dépôt, **tout ce qui est authentifié est sous `/api/…`**. Seules les pages
HTML publiques n'ont pas de préfixe : `/public/safety/**`, `/public/slots/**`,
`/p/**`, `/s/**`, `/v/**`. La règle n'a pas d'exception, et `SecurityConfig` la
fait tenir par `anyRequest().authenticated()`.

Le contrat que nous servirons est donc :

| Votre demande | Ce que le serveur exposera |
|---|---|
| `/watches`, `/watches/{id}/…` | `/api/watches`, `/api/watches/{id}/…` |
| `/guardians`, `/guardians/{id}/invite` | `/api/guardians`, `/api/guardians/{id}/invite` |
| `/incidents`, `/incidents/me` | `/api/incidents`, `/api/incidents/me` |
| `/reports/me` | `/api/reports/me` — **déjà en place** |
| `/public/guardian-consent/{token}/…` | inchangé, c'est une page publique |
| `/public/safety/{token}` | inchangé, existe déjà |

### 1.3 · « Points de position purgés à 30 jours » est une régression, pas une garantie

Votre §6.3 présente les 30 jours comme une protection. La règle en vigueur est
**1440 fois plus stricte** : une position partagée en conversation vit au maximum
**30 minutes** (`ChatService.MAX_LOCATION_SHARE_MINUTES`, borné aussi côté
requête par `@Max(30)`), la lecture refuse de servir un point échu même avant le
balayage, et `ExpiredLocationSweepJob` efface les coordonnées toutes les dix
minutes.

Nous ne l'assouplirons pas. Si votre phrase visait autre chose — l'historique des
états d'une veille, par exemple, ou la chronologie affichée sur `/watches/{id}` —
dites-le, et nous fixerons une rétention pour cette donnée-là. Mais rien dans ce
module ne justifie de garder une coordonnée trente jours, et le §9 de votre propre
demande dit qu'aucun calcul de position n'est attendu.

---

## 2. Les cinq arbitrages du §7

### 2.1 · Le hachage du code (7.1) — accepté, et il rend le §2 « temps constant » facile

L'analyse est juste : un secret de ~17 millions de combinaisons n'est pas protégé
par un hachage lent, seulement ralenti. `HMAC-SHA256(code, sel)` sous un poivre
hors base, avec `key_version` par ligne, est le bon choix. Le dépôt a déjà le
précédent d'un secret en variable d'environnement (`JWT_SECRET`), et nous
suivrons la même forme.

**Un bénéfice que la demande ne relève pas, et qui vaut d'être écrit.** Votre §2
exige qu'un code de contrainte réponde « exactement comme un succès », sans
différence observable *dans le temps de réponse*. Cette exigence était coûteuse
avec bcrypt : le dépôt utilise `BCryptPasswordEncoder(12)`, soit environ 300 ms
par vérification, et tester l'empreinte normale puis, seulement en cas d'échec,
l'empreinte de contrainte, aurait rendu un vrai code en ~300 ms et un code de
contrainte en ~600 ms. Le canal temporel aurait trahi exactement ce que la
fonctionnalité existe pour cacher.

HMAC-SHA256 se calcule en microsecondes. Nous évaluerons donc **les deux
empreintes systématiquement**, y compris quand la première correspond, et nous
fusionnerons les deux résultats en temps constant (`MessageDigest.isEqual`, aucun
court-circuit, aucun `return` anticipé). Le décrément d'`attemptsLeft` suit le
même chemin dans les deux cas.

Reste une asymétrie qu'aucun code ne supprime : le chemin de contrainte fait un
travail de plus — l'escalade. Elle partira donc **après** la réponse, sur le même
mécanisme d'envoi différé que le reste du module, jamais dans la transaction qui
répond. À noter au passage : la base est à San Francisco et le service en Europe,
ce qui pose un plancher d'environ 200 ms sur toute réponse touchant la base. Cela
ne dispense de rien, mais cela noie confortablement les écarts restants.

### 2.2 · SMS (7.2) — fournisseur accepté, deux exigences à re-chiffrer

Twilio région UE nous convient, et les trois exigences sont les bonnes. Deux
d'entre elles supposent une infrastructure qui n'existe pas.

**« File dédiée haute priorité » — il n'y a aucune file.** Le dépôt n'a ni broker
(pas d'AMQP, pas de Kafka), et Redis est à `redis.enabled=false` par défaut. Le
seul ordonnancement asynchrone est `@Async` sur l'exécuteur **partagé par défaut**
de Spring ; un unique bean nommé existe, `indexationExecutor`, réservé à
l'indexation. Toutes les notifications du produit passent aujourd'hui par le même
pool — exactement la situation que votre exigence n°1 interdit.

Un `ThreadPoolTaskExecutor` dédié se pose en une heure et satisfait la lettre de
l'exigence. **Il n'en satisfait pas l'intention.** Un pool en mémoire perd ses
envois en attente à chaque arrêt du processus : sur Railway, un redéploiement à
23 h 59 fait disparaître une alerte armée, sans trace et sans reprise. C'est le
mode d'échec que tout le §« le serveur tient les minuteurs » existe pour éviter,
réintroduit par la porte de derrière.

Ce que nous proposons à la place : **un outbox en base**. Une ligne « message à
envoyer » écrite dans la même transaction que la décision d'escalade, reprise par
un balayage court, marquée remise à l'accusé. La file dédiée devient alors une
propriété de la table (une colonne de priorité, un index), et non d'un pool qui
s'évapore. C'est aussi ce qui rend possible l'annulation transactionnelle que
votre §3 « course à gérer » demande : annuler une ligne non encore prise est
trivial, rappeler une tâche déjà remise à un pool ne l'est pas.

**Le SLO à 30 s implique une route publique absente de votre liste.** Mesurer
« 95 % remis en moins de 30 s » suppose les accusés de remise Twilio, qui
arrivent par webhook : un `POST` public supplémentaire, avec vérification de la
signature `X-Twilio-Signature`. C'est la deuxième famille de `POST` non
authentifiés du lot, après vos deux boutons d'accusé — et `SecurityConfig`
n'ouvre aujourd'hui que `GET` et `HEAD` sur `/public/**`. Nous ajouterons les
matchers ; nous le signalons parce que « instrumenter le SLO » a l'air d'une
tâche de métrologie et est en fait une surface d'entrée de plus.

Votre point réglementaire sur l'expéditeur alphanumérique est noté et sera tenu :
le gabarit ② renverra vers la page publique pour tout retour, et ne laissera
jamais croire qu'une réponse au SMS sera lue.

### 2.3 · `GET /reports/me` (7.3) — la route existe, la forme change, et elle corrige une fuite

Voir §1.1 pour la route. Sur la forme, deux points.

**Le vocabulaire d'états.** Le vôtre et le nôtre ne coïncident pas :

| Vous demandez | Nous avons | Projection retenue |
|---|---|---|
| `RECEIVED` | `PENDING` | direct |
| `IN_REVIEW` | *(rien)* | — voir ci-dessous |
| `RESOLVED` | `REVIEWED` + `ACTIONED` | les deux → `RESOLVED` |
| `DISMISSED` | `DISMISSED` | direct |

Nous projetterons **dans le DTO**, sans renommer la colonne. `V82` a tranché il y
a quatre jours de garder `REVIEWED` distinct d'`ACTIONED`, au motif que « clos »
ne dit pas si une sanction a suivi ; un troisième vocabulaire sur la même colonne
en six semaines est la manière la plus sûre de recréer le `500` qu'on vient de
fermer. La projection vous donne les quatre états que vous affichez, et nous garde
la distinction dont la modération a besoin.

Une réserve honnête sur `IN_REVIEW` : **aucun état intermédiaire n'existe
aujourd'hui**. Un modérateur passe `PENDING` à son verdict en un geste, il n'y a
pas de « pris en charge ». Nous pouvons l'ajouter, mais ce serait un état que rien
n'écrit — et un écran qui n'affiche jamais « en cours » ment aussi sûrement qu'un
écran qui l'affiche toujours. Deux issues : soit nous ajoutons le geste côté
modération et l'état devient vrai, soit vous n'affichez que trois états. Dites-nous
laquelle, nous ne trancherons pas seuls une question de produit.

**La fuite.** `ReportController.getMyReports` rend aujourd'hui `Page<Report>` :
l'entité brute, `@Data`, donc **tous** ses champs — `reviewedBy` (l'identifiant
du modérateur qui a traité le signalement) et `resolutionNotes` (ses notes
internes, en `TEXT`) compris. Un signalant qui appelle cette route lit les notes
de modération le concernant et sait qui l'a traité.

C'est notre défaut, pas le vôtre, et votre `ReportSummary` le referme. Il ne part
donc pas en priorité 7 : il part en premier, indépendamment du reste du module.
Nous vous confirmerons sa livraison séparément.

Votre argument sur `DISMISSED` est accepté sans réserve : il sera affichable, et
il ne sera pas déguisé en « en cours ».

### 2.4 · Le refus est global (7.4) — accepté, avec un amendement

Le raisonnement sur le contournement par second compte est juste, et la portée
globale est la seule qui protège.

**L'amendement porte sur le stockage.** Vous écrivez que le numéro refusé se
stocke « sous forme de hachage, jamais en clair », pour ne pas constituer « une
liste de numéros de personnes qui n'ont jamais voulu de ce produit ». L'intention
est la bonne ; le moyen ne l'atteint pas.

Un hachage **nu** d'un numéro de téléphone n'est pas un secret. L'espace des
mobiles français tient dans quelques centaines de millions de combinaisons — moins
que les 17 millions du code multipliés par vingt, un ordre de grandeur que votre
§7.1 juge lui-même « dérisoire hors ligne ». Une fuite de la base reconstitue la
liste entière en quelques secondes, par énumération. La précaution serait
décorative, et pire que rien : elle donnerait l'impression que la question est
réglée.

**Retenu : le même poivre qu'au §7.1.** La liste des refus se stocke en
`HMAC-SHA256(numéro_normalisé_E.164, poivre)`, sous la même clé hors base et le
même `key_version`. Une fuite de la base seule ne rend alors aucun numéro, pour
la raison exacte que vous donnez au §7.1 : il faudrait un second compromis,
indépendant.

Normalisation E.164 avant hachage, sans quoi `06 12 34 56 78` et `+33612345678`
seraient deux refus distincts et le contournement resterait ouvert.

### 2.5 · `travelMinutes` envoyé par l'app (7.5) — accepté tel quel

La requalification de la question est juste : les deux réponses envisagées
supposaient toutes deux qu'on sache où habite la personne. Rien à construire de
notre côté au-delà de la borne 15–240 et de son message d'erreur. Le serveur
applique la valeur reçue.

---

## 3. Quatre points de conception à verrouiller avant d'écrire

### 3.1 · Les deux liens de consentement en `GET` seront cliqués par des robots

`GET /public/guardian-consent/{token}/accept` et `/refuse` sont des `GET` qui
changent l'état. Les scanners de sécurité des messageries, les aperçus de liens
des applications de SMS et les proxys d'entreprise **suivent les liens
automatiquement**, sans intervention humaine. Sur `accept`, cela fabrique un
consentement que personne n'a donné. Sur `refuse`, c'est pire : votre §7.4 vient
d'en faire un refus **définitif et global à tout meetDo**, qu'aucun geste ne
défait.

Le scénario complet : le proche reçoit ①, son opérateur ou son client mail
pré-charge les deux liens, le premier arrivé gagne — et si c'est `refuse`, ce
numéro ne peut plus jamais être désigné par personne, sans que le propriétaire du
téléphone ait rien fait ni rien su.

**Ce que nous servirons :** un `GET` qui rend une page portant les deux boutons,
et deux `POST` qui appliquent la décision. Le `GET` reste sûr à pré-charger, le
`POST` ne l'est pas — c'est la seule forme qui résiste. La page dira ce qu'elle
engage, et le refus dira qu'il est définitif avant d'être cliqué.

### 3.2 · L'occurrence du créneau doit être figée à l'armement

`RecurringSlotRolloverJob` réécrit `starts_at` d'un créneau récurrent **toutes les
dix minutes**. Une veille qui relirait la fin du créneau pour recalculer
`deadlineAt` verrait donc l'échéance fuir devant elle, et le contact lirait la
date de la semaine suivante.

`SlotSafetyShare` a déjà rencontré ce mur et le contourne en figeant
`occurrence_starts_at` / `occurrence_ends_at` à la création. `Watch` fera de même :
`deadlineAt` est calculé une fois à l'armement, et n'est plus jamais dérivé du
créneau. Les seuls gestes qui le déplacent sont ceux de votre §3 — `/snooze` et
`/interrupt`.

**Un détail qui compte pour le défaut « fin du créneau + 1 h » :** `ends_at` est
**nullable** en base. La convention du dépôt, portée par `SlotTiming.endOf()`, est
`starts_at + 2 h` quand la fin n'est pas renseignée. Le défaut sera donc
`SlotTiming.endOf(slot) + 1 h`, ce qui vaut `starts_at + 3 h` sur un créneau sans
fin déclarée. Signalé pour que l'écran d'armement affiche la même heure que celle
que le serveur retiendra.

### 3.3 · Un « perdu en chemin » n'écrira jamais de ligne `Attendance`

Votre garde-fou du §6 est déjà tenu par la mécanique existante, **à une condition
qu'il faut écrire pour qu'elle survive au prochain qui touchera ce code.**

Le dénominateur du signal de fiabilité (`countPastJoinedByUserId`) ne compte que
les créneaux passés où une ligne `Attendance` existe : un silence retire la séance
de la mesure au lieu de peser contre. Un « perdu en chemin » est donc neutre —
tant que rien n'écrit de ligne.

Le piège est qu'`Attendance` porte un booléen `was_present`. Journaliser
l'incident sous la forme d'une `Attendance(was_present = false)` semblerait
naturel, et mettrait la séance **au dénominateur sans la mettre au numérateur** :
le produit punirait la personne exactement pour l'incident de sécurité, ce que
votre §6 interdit. Même mécanisme pour la série hebdomadaire et les badges, tous
deux calculés sur `Attendance`.

La règle, à porter dans le code et dans son commentaire : **un incident écrit une
ligne `Incident`, jamais une ligne `Attendance`.** `/abandon` suit la même règle,
comme votre §2 le demande déjà.

### 3.4 · Les trois relances doivent traverser les heures de silence

`PushNotificationService` filtre les appareils selon les heures de silence de leur
propriétaire, sauf pour les types marqués critiques
(`NotificationType.isCritical()`, aujourd'hui : annulation de créneau, annulation
de programme, changement d'horaire, rappel de séance).

Une veille armée pour une soirée arrive à échéance en pleine plage de silence dans
le cas le plus courant. Les trois relances y seront donc ajoutées : sans cela, un
réglage de confort supprime les trois occasions de lever l'alerte, et le contact
est réveillé à la place de l'utilisateur. C'est le critère déjà retenu pour cette
liste — « que coûte le fait de l'apprendre trop tard ? » — appliqué au cas le plus
net qu'elle ait eu à traiter.

Le retrait d'une notification déjà délivrée reste un geste client : nous
fournirons un identifiant de collapse stable par veille dans la charge APNs, comme
votre §3 le demande, mais seul le téléphone peut retirer ce qui est déjà affiché.
Ni l'un ni l'autre n'est posé aujourd'hui — l'envoi passe par FCM et ne pose ni
`apns-collapse-id` ni `interruption-level`.

---

## 4. Ordre de service

Nous suivons votre §8, avec **un déplacement et une insertion**.

| Priorité | Contenu | Écart avec votre §8 |
|---|---|---|
| **0** | `GET /api/reports/me` en `Page<ReportSummary>` | **déplacé depuis 7** — c'est un correctif de fuite, il ne dépend de rien |
| **0** | Poivre applicatif + `key_version` (§7.1, §7.4) | **inséré** — deux fonctions en dépendent, il vaut mieux qu'il précède les deux |
| **1** | `/api/guardians` (CRUD + `invite`), les deux pages publiques de consentement en `GET` + `POST` | conforme, avec le §3.1 |
| **2** | `POST /api/watches`, `GET /api/watches/active`, `GET /api/watches/{id}`, `DELETE` | conforme |
| **3** | `arrival` (+ génération du code), `close` (+ chemin de contrainte en temps constant) | conforme |
| **4** | Outbox, minuteurs du §3, gabarits ② ③ ④, webhook DLR | conforme, avec le §2.2 |
| **5** | `still-coming`, `abandon`, gabarit ⑤, notification à l'organisateur | conforme |
| **6** | `interrupt`, `snooze`, `panic`, `resend-code` | conforme |
| **7** | `POST /api/incidents`, `GET /api/incidents/me` | conforme, moins `reports/me` remonté en 0 |

La priorité 0 tient en deux journées et ne bloque aucun de vos écrans ; elle
referme une fuite en production, ce qui la place devant tout le reste.

Une remarque sur la priorité 5 : la page publique du §5 (six états, `ETag`,
`meta refresh`, boutons d'accusé) est aujourd'hui répartie entre vos priorités 4
et 5 sans être nommée. C'est un chantier à part entière — le seul contrôleur de
vue du dépôt, plus deux `POST` publics — et nous le traiterons avec la priorité 4,
puisque le lien d'urgence naît avec l'alerte et n'a aucun sens avant elle.

---

## 5. Ce qu'il nous faut de votre côté

1. **La réponse au §1.1** : quelle URL exacte, contre quel déploiement, à quelle
   date, pour `/reports/me`. C'est la seule question qui bloque quelque chose.
2. **`IN_REVIEW` (§2.3)** : état réel avec un geste de modération à créer, ou
   trois états affichés au lieu de quatre.
3. **Ce que visait « 30 jours » (§1.3)**, si ce n'était pas les positions.
4. **Les deux fichiers annoncés en en-tête de votre demande** — `template/meetdo-tracabilite.html`
   et `PLAN_IMPLEMENTATION_2026-08-31.md` — ne sont pas dans le dépôt.
   `modules/tracabilite/` ne contient que la demande. Les six états de la page
   publique et le filtre de `safety_share_message.dart` s'écriront mieux avec la
   maquette sous les yeux.

Rien de tout cela n'empêche de commencer : les priorités 0 à 2 sont indépendantes
des quatre points ci-dessus.

---

# Addendum — 31/08, après `REPONSE_CLIENT_2026-08-31.md`

> Ce qui précède reste tel qu'il a été envoyé. Cet addendum acte les réponses du
> chantier mobile et **corrige un diagnostic qui était faux de notre côté**.

## A.1 · Le §1.2 était faux : le préfixe ne manquait pas

Nous avons écrit que les dix-huit routes de la demande omettaient `/api`. C'était
une erreur de lecture, et il faut la reconnaître aussi nettement que nous avions
posé l'hypothèse.

`lib/core/config/app_config.dart:15` définit `apiBaseUrl` en terminant la base par
`/api`. Les chemins de `ApiConstants` sont donc **relatifs à une base qui le porte
déjà** : écrire `'/api/watches'` dans la constante produirait `…/api/api/watches`.
Leur fichier documente d'ailleurs le piège symétrique sur la route publique
voisine, où c'est l'oubli de `publicBaseUrl` qui rendrait un 404.

**Le tableau du §1.2 reste exact** — ils le confirment : il décrit ce que le
serveur exposera et ce que l'app appelle. Seule sa justification était fausse. Le
contrat ne change pas.

Ce qui change, c'est ce que cette hypothèse étayait. Nous en avions fait
l'explication privilégiée du §1.1, et suggéré que treize routes portaient le même
écart. Ce n'était le cas d'aucune.

## A.2 · Le §1.1 est sans objet

La phrase « n'est toujours pas servi » venait d'une note interne du 27/08 **au
matin**, écrite avant notre réponse de l'après-midi et jamais mise à jour. Ni
déploiement fautif, ni URL fautive. Ils ont corrigé la note à la source.

Notre question bloquante tombe. `GET /api/reports/me` est servi, et le seul
travail restant sur cette route est celui du §2.3 — sa forme.

## A.3 · Le §1.3 est résolu, et plus strictement que nous ne le demandions

Leur phrase ne visait pas le partage de position en conversation, que ce module ne
touche pas. Elle visait les trois points de passage de la veille (armement,
arrivée, retour), affichés dans la chronologie du journal.

Ils reprennent néanmoins l'objection à leur compte et retiennent : **coordonnées
effacées 24 h après la clôture de la veille**, alignées sur la durée de vie du
lien public. Ne survivent dans l'archive que l'horodatage et le nom du lieu — la
seule chose qu'on ait de toute façon le droit de montrer à un contact.

La rétention de trente jours ne concerne plus que la **chronologie des états**
(armée, arrivée, rappels, clôture), qui ne porte aucune coordonnée. Rien à y
redire : c'est de la donnée d'état, pas de la donnée de localisation.

`MAX_LOCATION_SHARE_MINUTES = 30` et `ExpiredLocationSweepJob` restent inchangés.

## A.4 · Les quatre questions du §5 sont closes

| Question | Réponse |
|---|---|
| §5.1 — quelle URL, quel déploiement | Sans objet, voir A.2 |
| §5.2 — `IN_REVIEW` | **Trois états** : `RECEIVED` · `RESOLVED` · `DISMISSED` |
| §5.3 — ce que visaient les 30 jours | Voir A.3 |
| §5.4 — les fichiers manquants | Leur dépôt n'est pas encore poussé ; maquette (26 écrans) et plan au prochain envoi |

Sur §5.2, une précision utile qu'ils apportent : leur parseur retombe sur
`RECEIVED` pour toute valeur inconnue. Si la modération gagne un jour le geste
« pris en charge », nous pourrons servir `IN_REVIEW` **sans les prévenir** — les
versions déjà installées le liront comme `RECEIVED` au lieu de casser. C'est la
bonne propriété, et elle nous dispense de figer la question maintenant.

## A.5 · Le webhook DLR : nous ne différons pas

Ils proposent de différer la mesure du SLO plutôt que d'ouvrir un `POST` public
mal gardé, si la vérification de signature nous paraît disproportionnée en
priorité 4.

**Nous ne différons pas.** La vérification `X-Twilio-Signature` est un HMAC-SHA1
sur l'URL concaténée aux paramètres triés, sous le token d'authentification :
une trentaine de lignes, pas un chantier. Différer la mesure laisserait
l'engagement « 95 % remis en moins de 30 s » invérifiable — c'est-à-dire une
promesse dont on découvre en production qu'elle n'a jamais tenu, sur le seul
message du produit dont le retard coûte quelque chose.

Le coût réel est la surface d'entrée, et elle se garde correctement. L'offre est
appréciée, elle n'est pas nécessaire.

## A.6 · Un point à vérifier chez eux

Leur §4.2 cite `IncidentState.parse` pour justifier la tolérance de lecture des
états de **signalement**. Or leur §1 insiste, à raison, pour que les deux
registres restent séparés : `Incident` et `Report` ne partagent ni table, ni
vocabulaire, ni file de modération.

Soit c'est un raccourci d'écriture, soit leur client fait passer les deux par le
même type — ce qui referait côté app la fusion qu'ils ont refusée côté serveur, et
finirait par ramener « perdue en chemin » dans la même liste que « comportement
inapproprié ». Une ligne de vérification suffit à lever le doute.

## A.7 · Ce qui est confirmé sans changement

Les quatre points de conception du §3 sont acceptés tels quels. L'outbox du §2.2
remplace la file dédiée, dont ils confirment qu'elle décrivait une propriété et
non une implémentation. L'amendement HMAC du §2.4 sur le numéro refusé est accepté
sans réserve.

L'ordre de service du §4 tient. **La priorité 0 commence maintenant.**
