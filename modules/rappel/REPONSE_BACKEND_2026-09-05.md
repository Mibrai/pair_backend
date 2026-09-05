# Module cycle — réponse du backend

**Écrite le 2026-09-05**, en réponse à
[`PROMPT_BACKEND_2026-09-03.md`](PROMPT_BACKEND_2026-09-03.md) (révisé le 04/09).

Trois demandes sur cinq sont livrées. La demande 1 est bloquée sur un accès, et
la demande 3 en dépend. Une réponse ferme à la question de la demande 5 est en
§5 : **c'était `null`**, et le défaut allait plus loin que vous ne le pensiez.

---

## Demande 1 — la mesure : **bloquée, et c'est le seul blocage du lot**

La requête est écrite et validée. Elle n'a pas encore tourné sur les vraies
données : la base de production n'est pas accessible depuis l'environnement de
développement, et la lecture de son URL de connexion nous a été refusée.

Elle a tourné sur la base locale, dont le résultat ne veut rien dire et le
montre : 66 programmes, tous `ACTIVE`, aucun créneau `CANCELLED`, et un délai
création → premier créneau qui vaut **exactement 0,00 ou 2,00 jour** selon la
ligne. C'est la signature d'un jeu de données fabriqué. Nous ne vous rendrons pas
ces chiffres-là comme s'ils répondaient à la question.

**Conséquence, et elle est exactement celle que vous aviez anticipée** : N et M
restent inconnus. L'étape 2 de la relance est écrite, testée et **éteinte**
(§2), et le sommeil n'est pas livré (§3).

---

## Demande 2 — `CYCLE_NUDGE` : **livré**

Le type existe, avec les trois déclencheurs. Un point de contrat a changé par
rapport à votre tableau, et il vaut d'être lu (§2.2).

### 2.1 Ce qui n'a rien coûté

`data.type` **était déjà correct**. Le correctif du 03/09 le pose centralement,
dans `PushNotificationService.dataPayload()`, pour tous les types sans exception
et après la charge métier. Rien à faire, aucun producteur à discipliner.

`CYCLE_NUDGE` n'est ni critique, ni envoyé par e-mail, ni *time-sensitive* : les
heures de silence s'y appliquent donc, comme vous le demandiez. Le critère du
dépôt pour trancher — « que coûte le fait de l'apprendre trop tard ? » — répond
ici « rien » : un programme sans date le sera encore demain matin.

### 2.2 L'étape 7 : nous n'avons pas écrit votre condition, et voici pourquoi

Vous demandiez « dernière occurrence terminée depuis ≥ 24 h, **aucune à venir** »,
avec la règle « une séance sans `ends_at` déclaré n'est jamais réputée terminée ».

Nous avons livré autre chose, et c'est délibéré.

**Le problème de votre formulation** est le mot « à venir ». C'est une
conjonction de deux tests, et le second est précisément celui qui a produit les
défauts que vous avez corrigés trois fois en trois jours. Tant que la phrase
contient « à venir », quelqu'un l'implémentera un jour avec `starts_at > now()` —
c'est ce que faisaient déjà quatre endroits du dépôt. Votre garde sur `ends_at`
ne supprime pas le piège : elle le déplace dans le premier test en laissant le
second l'introduire.

**Le problème de votre garde** est qu'elle aurait été muette. `endsAt` n'est pas
obligatoire à la création d'un créneau (`CreateScheduleRequest`), et le
formulaire complet ne lui donne aucune valeur par défaut. « Jamais terminée sans
`ends_at` » veut donc dire que l'étape 7 **ne serait jamais partie** pour ces
créneaux-là, sans que rien ne le signale. Et cela aurait créé une quatrième
définition de « terminée », alors que `SlotTiming` existe précisément parce que
la convention avait déjà été recopiée trois fois et allait diverger.

**Ce qui est livré à la place** : l'**horizon** d'un programme, la dernière
minute qu'il décrit — le maximum, sur ses créneaux non annulés, de leur fin selon
`SlotTiming` (déclarée, sinon deux heures).

> **L'étape 7 part quand `horizon + 24 h ≤ maintenant`.** Il n'y a pas de second
> test.

« Plus aucune séance devant soi » devient une **conséquence du calcul** au lieu
d'une condition qu'il faut penser à écrire correctement : une séance à venir *ou
en cours* a une fin dans le futur, donc un horizon dans le futur. `starts_at`
n'entre pas dans le prédicat — le piège n'est pas évité, il est **inexprimable**.

Bénéfices annexes, sans une ligne de plus :

- **les programmes récurrents se gèrent seuls.** Tant que la série vit, le
  rollover maintient la ligne dans le futur. Quand `UNTIL` est dépassé ou
  `COUNT` épuisé, il cesse d'avancer la ligne et l'horizon devient celui de la
  dernière séance vécue. « Ton cycle est bouclé » est alors littéralement vrai ;
- **les étapes 2 et 7 sont disjointes par construction.** Un programme sans
  créneau non annulé n'a pas d'horizon du tout : il relève de l'étape 2, jamais
  de la 7. Aucune garde croisée n'a été nécessaire ;
- **les 24 h de grâce absorbent la latence des jobs.** Le rollover passe toutes
  les dix minutes ; pendant ce délai l'horizon d'une série vivante est brièvement
  au passé. Vingt-quatre heures rendent cette fenêtre sans effet.

### 2.3 Les plafonds, et un piège que votre document ne couvrait pas

**Par programme, le plafond est devenu structurel.** Un index unique
`(program_id, stage)` en base : trois étapes, une ligne par étape au plus. Votre
« trois au maximum sur toute la vie du programme » n'est plus une vérification
qu'on peut oublier d'écrire, c'est une propriété du schéma — aucun code futur ne
peut la violer.

**Par personne**, une relance par 48 h toutes étapes confondues. Le compte cumule
les lignes déjà en base **et celles que la passe en cours vient d'écrire** :
`NotificationService.notify` est `@Async`, si bien qu'une lecture seule de la
base ne verrait pas ce que le job vient d'envoyer, et quelqu'un ayant trois
programmes dus en recevrait trois d'un coup.

**La salve du premier jour.** Votre document ne la mentionne pas, et c'est le
mode d'échec le plus visible qu'un module de relance puisse avoir : au premier
passage, le prédicat est vrai pour tout l'historique. Les trois déclencheurs sont
donc bornés **des deux côtés** — une fenêtre de sept jours. Un cycle refermé il y
a trois mois n'est jamais candidat. Le prix est explicite : un service arrêté
plus de sept jours perd les relances de cette période. C'est le compromis déjà
retenu deux fois dans ce dépôt, et il est préférable à une salve.

### 2.4 L'étape 2 est livrée mais **éteinte**

Elle s'allume en posant `meetdo.cycle.stage2-delay-days` — rien d'autre à
redéployer. Elle vaut zéro tant que le délai réel n'est pas mesuré : la demande
initiale proposait J+3, ce n'est pas une mesure. Si la médiane réelle est de neuf
jours, relancer à J+3 s'adresse en majorité à des gens qui allaient le faire.

C'est votre propre argument, et nous l'appliquons à la lettre.

### 2.5 Un détail d'affichage sur Android

`AndroidPushText` compose le titre `{activityName} · {programTitle}` dès que la
charge les porte, quel que soit le type. Sur Android le titre sera donc
« Course à pied · Course du mardi » et le corps portera le message de l'étape ;
sur iOS, le titre d'étape. C'est le gabarit existant de toutes les notifications
de programme, nous ne l'avons pas contourné — dites-nous si vous le voulez
autrement.

---

## Demande 3 — le sommeil `DORMANT` : **non livré, en attente de la demande 1**

Comme vous le demandiez : une échéance annoncée dans l'app que le serveur
n'applique pas est pire que pas d'échéance.

L'audit a toutefois vérifié votre tableau, et il est **plus favorable que vous ne
l'espériez**. Toutes les surfaces publiques filtrent déjà `p.status = 'ACTIVE'` :

| route | vérifié |
|---|---|
| `GET /programs?lat&lng` | en sort ✅ |
| `/activities/browse` (+ facettes, suggested) | en sort ✅ |
| `/search` | en sort ✅ |
| **`/slots/bounds`, `/slots/feed`** | en sort **deux fois** — `p.status = 'ACTIVE'` **et** `s.status IN ('OPEN','FULL')` ✅ votre troisième ligne est vérifiée |
| `GET /users/me/programs` | c'est la liste des *inscriptions*. Celle de l'auteur est `GET /programs` → y reste ✅ |
| `GET /programs/{id}` | répond normalement à son auteur ✅ |
| `POST /programs/{id}/join` | refuse déjà tout ce qui n'est pas `ACTIVE` ✅ |

Le réveil est déjà livré : `PATCH /programs/{id}` avec `{"status": "ACTIVE"}`
fonctionne, réservé à l'auteur, dès que l'énumération connaît la valeur.
`programs.status` n'a **aucune contrainte `CHECK`** : ajouter `DORMANT` ne coûte
pas de migration.

Il ne reste donc que la valeur et le job. Les deux partent dès que M est connu.

> ⚠️ **Une correction à apporter à votre plan.** `DELETE /programs/{id}`
> **n'efface rien** : il pose `ARCHIVED` et `archived_at`. Votre bouton
> « Supprimer définitivement » après trente jours de sommeil archiverait. Le
> libellé promet autre chose que ce qui se passe.

---

## Demande 4 — les accents : **livré, et le flou avec**

Votre relevé était exact. `ActivityService` passait par des requêtes dérivées
(`…ContainingIgnoreCase`) qui n'ignorent que la casse.

Les six cas de votre tableau sont désormais des tests, tels quels, plus la
symétrie que vous n'aviez pas mesurée : `COURSE À PIED` avec accents *et*
majuscules, et l'égalité des trois écritures.

`unaccent` était déjà installée (V101, pour la recherche de personnes) : la
correction n'a demandé aucune migration.

**Le « souhaité, non bloquant » est livré aussi**, en repli — jamais fusionné
avec la recherche exacte, la règle que `V77__trigram_search.sql` pose déjà.

Un mot sur le choix de la fonction, parce qu'il n'était pas évident.
`similarity()` **ne rattrape pas** `corse` : mesurée sur le référentiel,
`similarity('course à pied', 'corse')` vaut **0,250**, sous tout seuil utilisable,
parce que les deux tiers du nom cible ne sont pas dans la requête.
`word_similarity('corse', 'course à pied')` vaut **0,444** : elle compare la
requête au meilleur *fragment* du nom, ce qui est exactement le cas ici — mot
cherché court, cible longue.

Le seuil de 0,4 est mesuré, pas choisi :

| requête fautive | rendue | mesure |
|---|---|---|
| `randonee` | Randonnée | 0,727 |
| `escallade` | Escalade | 0,727 |
| `musculatoin` | Musculation | 0,667 |
| `tenis` | Tennis | 0,625 |
| `natasion` | Natation | 0,500 |
| `corse` | Course à pied | 0,444 |
| `yoag` | Hatha Yoga | 0,400 |

Le plancher de bruit de `corse` est à 0,167.

Deux garde-fous : **pas de repli au-delà de la première page** (une page vide y
signifie « la liste est finie », pas « rien ne correspond »), et un test vérifie
qu'une requête qui trouve son mot ne voit surgir aucun voisin approchant.

> **Une conséquence produit à connaître** : une requête qui rendait 0 peut
> désormais rendre des résultats approchants. Si votre écran propose « créer
> cette activité » quand la recherche est vide, il le proposera moins souvent —
> ce qui est le but, mais change ce que voit l'utilisateur.

---

## Demande 5 — `nextSessionAt` : **la réponse est `null`**, et c'était pire que ça

### La réponse à votre question

Pour un programme non récurrent dont l'unique séance est en cours,
`ProgramDto.nextSessionAt` valait **`null`**. Le calcul était
`min(startsAt)` parmi les créneaux dont le début est futur ; une séance commencée
en était exclue.

Donc oui : **`programIsExpired()` rendait `true` pendant la séance.** Vous aviez
raison de poser la question plutôt que de deviner.

### Ce que vous n'aviez pas vu

Le même défaut existait en **quatre autres endroits**, et deux d'entre eux ne
sont pas des champs mal rendus mais des **disparitions** :

| surface | ce qui se passait |
|---|---|
| `GET /programs` — `nextSessionAt` | `null` pendant la séance |
| `/activities/browse` | l'entrée quittait le catalogue pendant sa séance — le filtre `isExpired` est dans le `WHERE` |
| `/map/activities` | le marqueur quittait **la carte** pendant la séance |
| `/slots/mine?upcoming=true` | le créneau quittait « mes créneaux » à la seconde où il démarrait |
| `/slots/mine/calendar.ics` | un agenda resynchronisé pendant une séance la perdait |

Et un défaut symétrique, sur la même frontière : **un créneau annulé mais futur
alimentait `nextSessionAt`**, si bien qu'un programme sans un seul créneau vivant
paraissait avoir un pin sur la carte. C'est exactement la frontière sur laquelle
repose votre demande 1.

### Ce qui est livré

Les cinq surfaces lisent désormais la même définition — celle de `SlotTiming`,
fin déclarée sinon deux heures — et ignorent les créneaux annulés.
`nextSessionAt` **reste la séance en cours tant qu'elle n'est pas terminée**,
ce que vous demandiez.

Deux conséquences en second ordre :

1. sur la carte, `includeExpired=true` ne ramène plus les créneaux **annulés**.
   Ils sont écartés avant l'agrégation — obligé, sinon `totalInBounds`, les
   `count` des clusters et `truncated` seraient faussés. La sortie de secours
   continue de ramener les *expirés* ;
2. dans le catalogue, `schedule_count` compte toujours **tous** les créneaux,
   annulés compris, pour que « une entrée sans aucun créneau n'est jamais
   expirée » garde exactement le sens qu'il avait. Seul `next_session_at` change.

### Ce que nous n'avons pas touché

`GET /map/bounds` ne filtre **rien** : ni le statut du créneau, ni le temps. Il
rend donc des créneaux annulés et passés. C'est antérieur à ce lot et nous l'avons
laissé tel quel — mais il diverge maintenant de `/map/activities` sur les
annulés. À arbitrer si un client le consomme.

---

## Ce qui vous attend

| demande | état |
|---|---|
| 1 — la mesure | ⛔ **bloquée** : il nous faut un accès en lecture à la production |
| 2 — `CYCLE_NUDGE` | ✅ livré ; étape 2 éteinte tant que N est inconnu |
| 3 — `DORMANT` | ⏸ en attente de M |
| 4 — les accents | ✅ livré, flou compris |
| 5 — `nextSessionAt` | ✅ livré sur les cinq surfaces |

**Rien à changer côté client à la livraison.** Un type de notification inconnu
retombe sur `system`, `status` reste une chaîne libre, et `nextSessionAt` ne
change pas de type — seulement de valeur, dans le sens qui vous arrangeait.

**Suite de tests** : 1140 tests, 142 classes, entièrement verte. Les 1076 tests
antérieurs au module passent tous, et **aucune assertion existante n'a été
modifiée** — les deux classes de test touchées le sont en ajout seul.
