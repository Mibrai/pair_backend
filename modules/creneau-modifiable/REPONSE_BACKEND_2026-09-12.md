# Une série annulée ne se rouvrira plus la semaine suivante — et votre feuille d'annulation doit dire « toute la série »

**Date :** 2026-09-12
**Module :** [`creneau-modifiable/`](.) — nouveau module, ouvert par ce document
**Origine :** audit du 10/09 — fiche **P-BL-02** du
`audit/PLAN_BACKEND_LOGIQUE_METIER_2026-09-11.md`, **Lot 0** (sous 48 h).
**Décision actée :** `audit/DECISIONS_BACKEND_2026-09-12.md` § `P-BL/D1` — **option 1 : annuler un
créneau récurrent annule la série.** Tranchée par le produit.

> **Un défaut qui faisait se déplacer des gens pour rien, et un mot à changer chez vous.**
>
> - **§1 — un créneau hebdomadaire annulé revenait `OPEN` la semaine suivante**, avec ses anciens
>   inscrits toujours `CONFIRMED` et le rappel J-2 h qui repartait vers eux. Des personnes se sont
>   déplacées pour une séance annulée.
> - **§2 — c'est corrigé au Lot 0. Aucune migration, aucun changement de contrat, aucune nouvelle
>   version d'app requise.**
> - **§3 — la décision qui vous concerne : annuler, sur un créneau récurrent, annule TOUTE LA
>   SÉRIE.** Votre feuille « Annuler ce créneau » doit le dire. C'est la seule demande de ce
>   document, et c'est un libellé.
> - **§4 — une fenêtre pendant laquelle vous verrez des choses bizarres**, parce que nous reprenons
>   les lignes déjà rouvertes à la main, et que certaines resteront **délibérément** ouvertes.
> - **§5 — ce que nous ne promettons pas**, en particulier « annuler une seule séance ».

---

## 1. Le défaut

La requête qui alimente le job de rollover des récurrents ne filtrait que « `recurrence_rule` non
nulle, et `starts_at` déjà passé ». Le job avançait donc la ligne à l'occurrence suivante **et lui
posait `OPEN`** — y compris si elle était `CANCELLED`. Dans le même passage, il recalculait le
compteur de participants sur des inscriptions restées `CONFIRMED`.

Et `cancelled_at`, `cancelled_by`, `cancellation_reason` n'étaient **jamais effacés** : la ligne
portait à la fois « annulée le 3 » et « ouverte, prochaine séance le 10 ».

Le rappel J-2 h filtre `OPEN`/`FULL`. **Il repartait donc vers les anciens inscrits**, pour une
séance que l'organisateur avait annulée. C'est le préjudice : quelqu'un traverse la ville pour
personne.

Deux précisions utiles, parce qu'elles écartent l'hypothèse d'un usage volontaire :

- **aucune route de réouverture n'existe.** Seuls trois endroits du code posent `OPEN` : ce job, le
  recalcul de compteur (`FULL` → `OPEN`) et la duplication de programme. **Aucun créneau annulé n'a
  donc jamais été rouvert exprès** ;
- les deux chemins d'annulation divergeaient. `DELETE …/schedules/{id}` posait `CANCELLED` **sans
  aucune des trois colonnes d'annulation** ; `POST /slots/{id}/cancel` les posait. C'est ce qui rend
  la reprise de §4 en deux populations.

---

## 2. Ce que le serveur fait à partir de ce déploiement

| | Avant | Après |
|---|---|---|
| un créneau récurrent `CANCELLED` dont l'heure est passée | avancé à l'occurrence suivante et **remis `OPEN`** | **jamais touché** : ni statut, ni date |
| son compteur de participants | recalculé sur des inscrits d'une séance annulée | non recalculé |
| le rappel J-2 h, la relance de présence, la veille | repartaient | **ne partent plus** pour cette ligne |
| une ligne `OPEN`, `FULL` ou `PAST` | avancée et remise `OPEN` | avancée, et `OPEN` seulement si elle l'était déjà — le compteur décide ensuite de `FULL` |

**`CANCELLED` devient terminal.** Une série annulée ne revient pas, par aucun chemin automatique.

**Aucune migration. Contrat inchangé.** Votre app 1.1.0+16 n'a rien à changer pour que ce correctif
lui profite — il n'y a **rien à livrer côté app pour le Lot 0**. Le §3 est une amélioration de
libellé, pas une condition.

### 2.1 Ce qui ne change pas, et qu'il ne faut pas confondre

**`PAST` n'est pas `CANCELLED`.** Une série close par son `UNTIL` est `PAST`, et elle est laissée en
l'état : ce n'est pas une annulation, c'est une fin normale. Si vous distinguez ces deux cas à
l'écran, la distinction reste valable et devient plus fiable qu'avant.

---

## 3. La décision, et le mot qui doit changer chez vous

`P-BL/D1` a été tranchée — par le produit — en faveur de **la série** :

> Quand un organisateur annule (ou « supprime ») un créneau hebdomadaire, **il annule toute la
> série.** `CANCELLED` est terminal pour la ligne.

**Pourquoi cette option plutôt que « la seule séance ».** L'option « la seule séance » demande une
table d'exceptions d'occurrence, et **chaque lecture** — fil, rappel, relance, veille, présence,
carte-souvenir — devrait la consulter. Tout oubli recrée exactement le défaut du §1. L'option « la
série » le supprime en **une ligne de requête**, et c'est l'erreur la moins coûteuse : ne pas avoir
de séance vaut mieux qu'en attendre une qui n'aura pas lieu.

**La friction est réelle et assumée** : un organisateur qui voulait seulement sauter une semaine
doit republier sa série. Elle est visible, et c'est ce qui la rend acceptable.

### 3.1 Ce que nous vous demandons

Sur un créneau **récurrent**, la feuille « **Annuler ce créneau** » doit dire que c'est **toute la
série** qui s'arrête.

**La formulation est la vôtre.** Ce que le libellé actuel a de trompeur, c'est « **ce** créneau » :
au singulier, sur une ligne hebdomadaire, il laisse croire à une occurrence. L'organisateur doit
comprendre, **avant de taper**, que la série entière s'arrête et qu'il devra la republier s'il
voulait juste sauter une semaine.

Deux points d'appui dans votre propre plan UX :

- **P-MU-06** — « se désister d'un créneau se fait sans confirmation, quitter un programme en
  demande une ». Ici la confirmation existe : c'est son **texte** qui est en cause ;
- **P-MU-23** — « cinq mots pour le même rendez-vous ». Si « série » n'est pas dans votre
  vocabulaire arrêté, c'est le moment de décider par quel mot vous la nommez, et de l'utiliser
  partout.

Sur un créneau **non récurrent**, rien ne change : le libellé actuel est juste.

---

## 4. Reprise des lignes déjà rouvertes : la fenêtre pendant laquelle vous verrez des choses bizarres

Les lignes **déjà** rouvertes à tort sont reprises à la main, par un runbook
([`docs/runbooks/REPRISE_CRENEAUX_ROUVERTS_2026-09.md`](../../docs/runbooks/REPRISE_CRENEAUX_ROUVERTS_2026-09.md)),
**en SQL et non par l'API**, avec deux règles que nous vous signalons parce qu'elles sont visibles
de chez vous :

### 4.1 Nous ne renotifions pas

L'annulation a **déjà** été annoncée en son temps. La renvoyer des semaines plus tard ne corrige
rien et rouvre une inquiétude sur une séance dont plus personne ne se souvient.

**Donc : pendant cette reprise, des créneaux vont passer à `CANCELLED` sans qu'aucune notification
ne l'accompagne.** Si votre app cache un statut de créneau plutôt que de le relire, c'est le moment
de le vérifier. Si vous voyez passer un `SLOT_CANCELLED` pendant la fenêtre de reprise, il ne vient
pas de nous, et il faut nous le dire.

### 4.2 Certaines lignes resteront ouvertes — exprès

Si une ligne rouverte à tort a reçu de **nouvelles inscriptions après la date d'annulation**, nous
**ne la refermons pas**. Quelqu'un a rejoint une séance qu'il croit réelle : la refermer d'un
`UPDATE` ferait disparaître de son agenda un rendez-vous qu'il a pris, sans un mot.

Ces lignes sont listées, et **un humain écrit à l'organisateur** pour lui demander ce qu'il veut
faire de sa série. S'il annule à nouveau, ce sera **par l'app ou par l'API** — donc avec
notification, cette fois, parce que ces inscriptions-là n'ont jamais été prévenues.

**Conséquence pour vous** : pendant un temps, une série annulée peut légitimement apparaître
**ouverte** dans vos écrans. Ce n'est pas une régression, et il n'y a rien à corriger côté app.

---

## 5. Ce que nous ne promettons pas

- **« Annuler cette séance seulement » n'existe pas, et n'est pas planifié.** C'est l'option 3 de
  `P-BL/D1` : une table `schedule_occurrence_exceptions`, un paramètre `scope: OCCURRENCE|SERIES`
  sur `POST /slots/{id}/cancel`, et toutes les lectures passant par un point unique. Elle est au
  **Lot 3**, **si** le besoin est confirmé par des retours, et le plan dit « à ne pas démarrer sans
  décision ». **Ne construisez pas d'écran qui la suppose.**
- **Le statut d'un créneau annulé n'est pas encore lisible partout.** Aujourd'hui, *« un créneau
  annulé est indiscernable dans "Mes créneaux" et sur sa fiche »* — c'est **P-BL-08**, au **Lot 1**.
  Le correctif de §2 empêche le préjudice, il ne rend pas l'annulation visible. Votre libellé
  d'annulation (§3) est **utile avant** cette fiche ; l'affichage de l'état annulé l'attend.
- **`DELETE …/schedules/{id}` va changer de réponse, mais pas de manière qui vous casse.** Au Lot 2,
  `P-BA/D3` option B : la route rendra **`200 {outcome: DELETED|CANCELLED, schedule}`** au lieu de
  `204`. Nous avons vérifié que votre `_dio.delete<void>` **ignore le corps et accepte tout 2xx**
  (`program_repository.dart`) : c'est la raison pour laquelle cette option a été retenue plutôt
  qu'un `409`. **Si cette lecture de votre code est fausse, dites-le maintenant** — c'est le seul
  point de ce document où nous nous appuyons sur une hypothèse à votre sujet.
- **Modifier l'heure ou le lieu d'un créneau ne prévient toujours personne.** C'est **P-BL-06**, au
  Lot 1, avec **P-BL-14** avant lui (on ne notifie pas une modification qu'on va refuser trois
  lignes plus loin). Ne comptez pas sur un `SCHEDULE_CHANGED` avant cette livraison.
- **Le double `SLOT_CANCELLED`** et les deux chemins d'annulation qui divergent : **P-BL-19**, Lot 1.
  D'ici là, vous pouvez encore recevoir deux notifications pour une même annulation selon le chemin
  emprunté.
- **Les créneaux récurrents ne reçoivent toujours pas la relance de présence** (**P-BL-07**,
  Lot 1) : ce correctif-ci ne la rétablit pas, il l'empêche seulement de partir pour une série
  annulée.
