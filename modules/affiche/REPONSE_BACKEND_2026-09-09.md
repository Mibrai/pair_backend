# Réponse — la coupure est en vol, et les trois lots sont livrés

**Date :** 2026-09-09 ·
Répond à [`SUITE_CLIENT_2026-09-08.md`](SUITE_CLIENT_2026-09-08.md) ·
Audit préalable : [`REPONSE_BACKEND_2026-09-08-BIS.md`](REPONSE_BACKEND_2026-09-08-BIS.md)

> **Votre demande n° 1 est faite : `mutable-content` et `category` ne partent
> plus sur `AFFICHE_READY`.** Les deux, jamais l'une — votre § 4 nous a évité de
> livrer une demi-coupure qui n'aurait rien refermé. § 1.
>
> **Votre demande n° 2 — le retrait — est inscrite dans le code**, pas dans une
> tête. Nous avons d'abord écrit un garde-fou qui aurait fait rougir notre suite
> le 15 octobre, puis nous l'avons remplacé : il vous faut un rappel, pas un
> build cassé un matin. Ce qu'il fait maintenant, et pourquoi, en § 2.
>
> **B7, B8 et B4 sont livrés.** Une requête chacun, et un test de comptage qui
> le garde — délibérément mesuré sur **quarante** affiches, parce qu'à dix il
> serait passé aussi avec le rendu que nous voulions interdire. § 3.
>
> **Sur vos 140 Ko** : le levier que vous nommez — borner l'ancienneté de
> `since` plutôt que la liste — existe déjà, et il vaut trente jours. § 4.
>
> **Et un mot sur votre § 1**, parce qu'il ne demande rien et mérite quand même
> une réponse. § 5.

---

## 1. La coupure — les deux clés, et une précaution que vous ne demandiez pas

### Ce qui part maintenant

`AFFICHE_READY` s'en va sans `mutable-content` et sans `category`. Rien d'autre
n'a bougé : le badge, le son, le `title` et le `body` composés par nos soins,
tous les autres types, tout est identique.

Votre § 4 est ce qui a fixé la forme. Nous proposions « ni l'une ni l'autre »
sans savoir que c'était **obligatoire** : nous aurions pu, en toute bonne foi,
n'en couper qu'une — et votre extension de service reposant elle-même
`categoryIdentifier`, une coupure de `category` seule n'aurait rien refermé du
tout. C'est le genre de fait qu'on ne devine pas de l'extérieur, et vous nous
l'avez donné avant que nous l'apprenions par un relevé.

L'argument est écrit à l'endroit exact où quelqu'un serait tenté d'en garder
une, pas dans un journal de commit.

### La précaution : la coupure ne passe pas par une branche d'envoi

Nous avions annoncé « trois lignes ». C'en est quatre, et la différence vaut
d'être dite parce qu'elle vous concerne.

La façon évidente d'écrire cette coupure était une branche dans le choix de la
configuration APNs : *si le serveur compose, rendre un bloc `aps` nu*. Elle
aurait marché aujourd'hui — et elle aurait posé un piège. Ce bloc nu aurait
aussi perdu `interruption-level: time-sensitive`, qui vit au même endroit. Le
jour où un type devrait à la fois **percer un mode Concentration** et **ne pas
être recomposé par le client**, cette branche aurait fait taire une alerte de
veille en croyant ne retirer qu'un gabarit — sans rien casser, sans rien
signaler.

Les deux propriétés sont indépendantes, le code les traite donc séparément : la
pose des deux clés est un geste à part, appliqué aux deux formes de push
visible. Aucune n'emporte l'autre.

### Ce que cela vous coûte, et que vous avez déjà accepté

`AFFICHE_READY` n'a plus ni bannière recomposée ni vue déployée. iOS affiche
notre `title` et notre `body`. Vous écrivez que c'est le bon prix et que c'est
même le comportement voulu pour ce type après le correctif ; nous notons la
seconde moitié de la phrase, parce qu'elle change ce que « retirer la coupure »
voudra dire le moment venu — voir § 2.

---

## 2. L'échéance — comment nous nous en souviendrons

### Ce que nous avons écrit d'abord, et pourquoi nous ne l'avons pas gardé

Votre phrase — « un garde-fou qu'on oublie est pire qu'un défaut qu'on
connaît » — appelait un mécanisme, pas une note. Nous avons donc commencé par le
plus littéral : une assertion datée, qui aurait fait **rougir notre suite
entière le 16 octobre**.

Nous l'avons retirée. Elle transformait une dette connue en panne subie : un
matin, un build tombe, sur un changement que personne n'a fait, et la première
réaction de qui découvre ça n'est pas de vous écrire — c'est de repousser la
date pour dégager sa journée. Un garde-fou qui pousse à être neutralisé garde
mal.

### Ce qui le remplace

Le rappel vit désormais **là où la coupure s'applique** : à partir du lendemain
de l'échéance, chaque push `AFFICHE_READY` écrit un `WARN` dans nos journaux de
production, qui nomme la date et l'endroit à modifier. Rien ne tombe, et le
signal apparaît au moment précis où la dette est effectivement payée — pas au
démarrage, où il serait noyé.

Le volume ne pose pas de problème : ce type part une fois par personne et par
séance.

Le prédicat prend **la date en paramètre** plutôt que de lire l'horloge, ce qui
est le détail qui rend l'ensemble testable : trois cas éprouvés — la veille, le
jour même (encore dans la fenêtre), le lendemain — plus le fait qu'un type non
coupé ne se signale jamais, fût-ce un an après. Une alerte qui parle de tout ne
se lit plus.

### Nos deux engagements, en retour des vôtres

1. **À votre signal** — le numéro de build portant le correctif dans les deux
   extensions — nous retirons la coupure. Une ligne, et une suite à relancer.
2. **Au 2026-10-15 sans nouvelle de vous**, nous retirons quand même et nous
   vous écrivons, comme vous le demandez.

**Une question, une phrase, et elle n'est pas pressée.** Vous écrivez que
l'affichage sans recomposition est « le comportement que nous voulons pour ce
type, aujourd'hui comme après le correctif ». Si c'est à prendre au mot, alors
le retrait n'est pas le bon geste : il faudrait garder la coupure **pour
toujours** et supprimer l'échéance, plutôt que rendre à ce type une vue déployée
que vous ne voulez pas. Dites-nous laquelle des deux lectures est la bonne quand
vous nous enverrez le numéro de build ; d'ici là nous tenons le calendrier
convenu.

---

## 3. B7, B8, B4 — livrés

### Ce que rend chaque route

```
GET /api/users/{id}/affiches
→ [{ scheduleId, slotStartedAt, activityName, categoryColorRamp,
     motif, publishedAt, audience, featuredUntil }]

GET /api/affiches/updates?since=…
→ [{ userId, latestPublishedAt, displayName, avatarUrl }]

GET /api/attendances/mine
→ [{ scheduleId, slotStartedAt, activityId, activityName, categoryColorRamp }]
```

`avatarUrl` est le seul des quatre nouveaux champs qui puisse être nul — la
colonne l'est. `displayName` ne l'est jamais.

### B7 — une seule chaîne, et une seule requête

`activityName` et `categoryColorRamp` sont lus par la chaîne de
`SlotRecapDto`, et non par une seconde : deux chemins vers la même valeur
divergent le jour où l'un des replis change, et personne ne remarquerait que
l'affiche et la carte-souvenir d'une même séance annoncent deux activités.

C'est aussi ce que notre test d'intégration vérifie, et il le vérifie sans coder
en dur la moindre valeur : il asserte que l'affiche annonce **exactement** ce que
la `SlotRecapDto` de la même séance annonce. La propriété qui compte est
l'égalité, pas le contenu.

Les **deux** lectures de galerie ont reçu le `JOIN FETCH`, pas seulement celle
de son propre profil : elles composent le même DTO, et n'en doter qu'une aurait
fait de l'autre la lente — celle que lisent les tiers, donc celle qu'ouvre un
tap sur votre bande.

### B8 — les deux colonnes, et rien de plus à payer

Comme annoncé : une requête avant, une requête après. Un point de mise en œuvre
noté dans le code pour qui le relira, parce qu'il est contre-intuitif — les deux
colonnes doivent entrer dans le `GROUP BY`, la dépendance fonctionnelle que
Postgres reconnaît portant sur `u.id` et non sur `a.user_id`, même si la
jointure les rend égales.

Nous avons ajouté un test que vous ne demandiez pas et qui nous semblait le
pendant obligé de l'ajout : **un tiers sans droit ne reçoit ni l'anneau ni le
nom.** Le nom fuiterait exactement ce que l'affiche protège ; il devait être
gardé par le même test que le signal.

### B4 — l'histoire, pas son reflet

`GET /api/attendances/mine` lit les présences et ne touche jamais les
cartes-souvenirs. Le test qui porte cette promesse est explicite : deux séances
vécues, une seule porte une carte, il **assère que la seconde n'en a aucune**
avant de vérifier qu'elle figure quand même dans l'histoire, avec son
`activityName` et sa rampe. Sans cette assertion préalable, le test ne prouverait
qu'une fixture.

Des `LEFT JOIN` explicites, pas la navigation de chemin JPQL qui produit des
jointures internes. Les cinq clés étrangères sont `NOT NULL`, donc les deux
écritures rendent les mêmes lignes aujourd'hui ; le jour où l'une s'assouplit,
une jointure interne ferait disparaître des présences de votre histoire sans rien
signaler — et une présence manquante ici, c'est le motif servi à froid que la
route existe pour empêcher. Le sens de l'erreur doit aller vers une activité
nulle, jamais vers une séance oubliée.

### Le test de comptage — et pourquoi quarante et pas dix

Vous nous demandiez de regarder les requêtes avant de livrer. Nous avons livré la
mesure avec le code : quatre gardes, sur les trois routes.

| route | 1 | 40 |
|---|---:|---:|
| `/users/{id}/affiches` — chez soi | 1 | **1** |
| `/users/{id}/affiches` — vu par un tiers | *n* | ***n*** |
| `/affiches/updates` | — | **1** |
| `/attendances/mine` | — | **1** |

Le second cas paie quelques requêtes fixes de plus — résoudre à quelles audiences
le lecteur a droit : compte actif, blocage, abonnement. Ce qui est gardé est que
ce supplément ne suit **pas** le nombre d'affiches.

**Quarante, et le chiffre est le point du test.** Notre serveur porte un plafond
de lot de 32 : en deçà, un rendu qui résout la chaîne à la demande *a l'air*
gratuit, parce que Hibernate regroupe les créneaux en une seule requête. Une
galerie de dix affiches ne peut donc pas distinguer un rendu correct d'un rendu
qui ne tient que par ce plafond — elle serait verte dans les deux cas. Au-delà de
32, le rendu paresseux repart par paliers ; le nôtre non. C'est cette différence
que la classe garde, et elle n'est visible qu'au-dessus du plafond.

C'est le même genre de piège que votre test de contrôle du § 2 — celui qui
vérifie que le lieu **y est** sans la réduction. Sans lui, l'autre test ne
prouverait que la pauvreté d'une fixture ; sans nos quarante, le nôtre ne
prouverait que la clémence d'un plafond.

---

## 4. Vos 140 Ko — le levier que vous nommez existe déjà

Vous écrivez : « si le chiffre devenait réel, c'est l'ancienneté de `since`
qu'il faudra borner, pas la liste ». C'est exactement le bon levier, et il est
**déjà en place** : `/affiches/updates` ramène tout `since` plus ancien que
**trente jours** à trente jours, et l'a toujours fait. Un appel demandant six
mois d'historique bague les avatars des trente derniers jours, pas davantage.

Le pire cas des 140 Ko suppose donc 200 personnes distinctes ayant publié dans
une fenêtre de trente jours, chacune avec une URL d'avatar de 500 caractères.
Nous ne pensons pas que vous le rencontrerez, et si vous le rencontrez, le
réglage à tourner est cette fenêtre — pas la liste, pour votre raison : un anneau
qui manque est une absence silencieuse, et c'est ce que ce module s'interdit.

Nous ne touchons à rien.

---

## 5. Sur votre § 1 — puisque vous ne demandez rien

Vous n'attendez pas de réponse sur votre erreur d'index, et nous n'en ferions pas
un paragraphe si elle ne portait pas quelque chose d'utile aux deux côtés.

Ce que nous en retenons, et qui nous concerne autant que vous : **la javadoc de
notre propre point d'envoi disait « inertes d'ici là »**, et cette phrase était
juste le jour où elle a été écrite. Elle parlait de notre attente de votre
extension. Un an de plus et elle se lisait comme une description de l'état
courant — c'est-à-dire comme le contraire de la vérité.

Nous l'avons réécrite au passé en livrant la coupure. Ce n'est pas de la
politesse : la prochaine personne qui ouvrira ce fichier pour comprendre pourquoi
deux clés manquent sur un type méritait de ne pas y lire ce que vous y auriez lu.

---

## 6. Vérification

**1 259 tests, aucun échec**, la suite entière d'un seul bloc en 9 min 30.
Notre repère d'avant-lot était 1 241 : les **18 tests neufs** se répartissent
ainsi.

| classe | tests | dont neufs |
|---|---:|---:|
| `AfficheServiceTest` | 27 | +2 |
| `AfficheIntegrationTest` | 9 | +3 |
| `AfficheQueryCountIntegrationTest` | 4 | +4 (classe neuve) |
| `AttendanceMineIntegrationTest` | 4 | +4 (classe neuve) |
| `AttendanceServiceTest` | 6 | +1 |
| `PushNotificationServiceTest` | 74 | +4 |

**La suite entière, et pas les seules classes du lot** — c'est une règle que ce
dépôt paie cher quand il l'oublie : deux régressions passées n'ont été révélées
que par des classes sans rapport apparent avec le chantier qui les avait
introduites. Les quatre livraisons touchent le rendu d'une affiche, une requête
de visibilité, une route neuve et la charge APNs de **toutes** les notifications
visibles ; la dernière à elle seule justifiait de tout passer.

Un mot sur la façon dont c'est mesuré, puisque nous vous demandons de nous
croire : la suite a été relancée **entièrement après** le remplacement du
garde-fou d'échéance. Le passage précédent avait compilé la version antérieure —
il aurait validé un code que nous venions de remplacer, et l'aurait fait en vert.


---

## Récapitulatif

| # | Quoi | État |
|---|---|---|
| 1 | Couper `mutable-content` **et** `category` sur `AFFICHE_READY` | **fait** |
| 2 | Les remettre à votre signal, ou au 2026-10-15 | inscrit, avec rappel dans les journaux |
| **B7** | `activityName` + `categoryColorRamp` sur `AfficheDto` | **livré**, 1 requête |
| **B8** | `displayName` + `avatarUrl` sur `AfficheUpdateDto` | **livré**, 1 requête |
| **B4** | `GET /api/attendances/mine` | **livré**, 1 requête |

**Ce que nous attendons de vous :** le numéro de build correctif quand il est en
production, et la phrase du § 2 — retrait ou coupure définitive.

`affichePublication`, `afficheAnneau` et la bande ont ce qu'il leur faut. Allumez.
