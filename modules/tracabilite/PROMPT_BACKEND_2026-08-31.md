# Traçabilité & veille retour — ce que l'app attend du serveur

**Date :** 2026-08-31
**Maquettes :** `modules/tracabilite/template/meetdo-tracabilite.html`
**Plan client :** `modules/tracabilite/PLAN_IMPLEMENTATION_2026-08-31.md`
**État :** prêt à envoyer. Les 21 décisions produit et les 5 points d'infrastructure sont arbitrés — il ne reste **aucune question ouverte** de notre côté.

---

## En deux phrases

Un utilisateur arme une « veille retour » sur un créneau. S'il ne confirme pas son retour dans le temps qu'il a fixé, un proche qu'il a désigné reçoit un message automatique — après trois relances. La confirmation se fait avec un **code court que lui seul connaît**, créé au moment où il valide son arrivée sur place et détruit à la clôture.

Le module couvre aussi le trajet aller (une personne qui n'arrive jamais), l'interruption en cours de séance, et le signalement d'incident.

---

## Le point de contrat le plus important

**Le serveur tient tous les minuteurs. L'app n'en planifie aucun.**

Ce n'est pas un détail d'architecture, c'est la décision qui rend le module utile. Si l'app tenait l'horloge, une batterie vide donnerait **zéro alerte** — précisément dans le cas où l'on en veut une. Côté serveur, une batterie vide donne une **fausse** alerte, et une fausse alerte se lève ; une alerte absente ne se rattrape pas.

Conséquence sur la rédaction des messages : ils ne doivent jamais dire « est en danger », seulement « n'a pas confirmé ». Le serveur ne sait pas si la personne va bien. Il sait qu'elle n'a pas répondu.

---

## 1. Modèle de données

### `Watch` — une veille

| Champ | Type | Note |
|---|---|---|
| `id` | uuid | |
| `scheduleId` | uuid | le créneau concerné |
| `userId` | uuid | |
| `state` | enum | `ARMED` · `EN_ROUTE` · `ON_SITE` · `REMINDING` · `ESCALATED` · `RESOLVED` · `CLOSED` |
| `armedAt` | instant | |
| `arrivalConfirmedAt` | instant? | **null tant que la personne n'a pas validé** |
| `interruptedAt` | instant? | |
| `deadlineAt` | instant | recalculable — voir §3 |
| `remindersSent` | int | 0..3 |
| `guardianId` | uuid | contact principal |
| `backupGuardianId` | uuid? | contact de secours |
| `closedAt` | instant? | |

### `ReturnCode` — le secret

| Champ | Type | Note |
|---|---|---|
| `watchId` | uuid | 1 pour 1 |
| `hash` | string | **HMAC-SHA256(code, sel) poivré** — voir §7.1 |
| `attemptsLeft` | int | 3 au départ |
| `createdAt` | instant | = `arrivalConfirmedAt` |
| `duressHash` | string? | le code de contrainte, si l'utilisateur en a un |

**Le serveur ne stocke jamais le code en clair.** C'est la contrainte qui rend vraie la phrase « connu de lui seul » : sans elle, un accès à la base — ou quelqu'un de l'équipe — peut lever la veille à la place d'un utilisateur. Le code est généré, renvoyé **une seule fois** dans la réponse de création, puis oublié côté serveur.

Le choix de l'algorithme n'est pas libre : un code de 5 caractères est trop court pour qu'un hachage lent le protège seul. Voir §7.1.

À la clôture : la ligne est **supprimée**, pas marquée obsolète.

### `Guardian` — un contact d'urgence

| Champ | Type | Note |
|---|---|---|
| `id` | uuid | |
| `ownerId` | uuid | |
| `memberId` | uuid? | si le contact a un compte meetDo |
| `name` / `phone` / `email` | string? | si hors meetDo |
| `consentState` | enum | `PENDING` · `ACCEPTED` · `REFUSED` |
| `consentToken` | string | pour les liens accepter/refuser |

**Pas d'adresse postale.** Rien dans ce flux n'envoie de courrier ; c'est une donnée sensible de tiers sans usage, et on ne la stocke pas.

**Un contact hors meetDo doit avoir accepté avant d'être sélectionnable.** Un seul message lui est envoyé, jamais de relance. Un refus est définitif et bloque le numéro pour tout usage futur, par n'importe quel compte. Sans ça : RGPD discutable, et des SMS non sollicités depuis la marque à 2 h du matin — c'est-à-dire des signalements pour spam.

### `Incident`

Registre **séparé** de `/reports`. Cible : `PERSON` · `PLACE` · `ORGANISATION` · `TRANSIT` · `SELF`. Seule `PERSON` bascule dans le flux de signalement existant.

Mêler « perdue en chemin » à « comportement inapproprié » polluerait la modération et mettrait la victime dans la colonne des signalés.

---

## 2. Routes

### Veille

| Verbe | Chemin | Corps / retour |
|---|---|---|
| `POST` | `/watches` | `{scheduleId, guardianId, backupGuardianId?, deadlineAt}` → `Watch` |
| `GET` | `/watches/active` | `Watch[]` — les veilles non closes de l'appelant |
| `GET` | `/watches/{id}` | `Watch` + chronologie |
| `POST` | `/watches/{id}/arrival` | `{}` → `{watch, returnCode}` — **le code en clair, une seule fois** |
| `POST` | `/watches/{id}/still-coming` | repousse la relance d'arrivée de 15 min |
| `POST` | `/watches/{id}/abandon` | « je n'y vais pas » — désarme sans message et **sans compter d'absence** |
| `POST` | `/watches/{id}/interrupt` | `{reason, alreadyHome: bool, travelMinutes?: int}` |
| `POST` | `/watches/{id}/close` | `{code, enteredAt}` → `202` / `409` (code faux) |
| `POST` | `/watches/{id}/snooze` | +30 min, **sans code** |
| `POST` | `/watches/{id}/panic` | envoie le message immédiatement |
| `POST` | `/watches/{id}/resend-code` | exige le mot de passe du compte · 1×/cycle · inscrit à la chronologie |
| `DELETE` | `/watches/{id}` | désarmement avant départ |

Sur `/close` : **`enteredAt` fait foi, pas l'heure de réception.** Une saisie faite hors ligne à 23:32 et transmise à 23:58 doit lever la veille — sinon rentrer chez soi dans un immeuble mal couvert déclenche une alerte chez sa mère.

Sur `/close` avec le `duressHash` : répondre **exactement comme un succès**, et déclencher l'escalade en silence. Aucune différence observable dans le corps, le code HTTP ou le temps de réponse.

### Contacts

`GET|POST|DELETE /guardians`, `POST /guardians/{id}/invite`, et deux routes publiques `GET /public/guardian-consent/{token}/accept` et `/refuse`.

### Incidents

`POST /incidents` (avec pièce jointe optionnelle — le volume de stockage est monté depuis le 31/08), `GET /incidents/me`.

### Et une dette existante

**`GET /reports/me` n'est toujours pas servi.** L'écran de suivi ne peut pas afficher « reçu · pris en charge · en cours » sans lui. Ce n'est pas cosmétique : un signalement sans nouvelles est un signalement qu'on ne refait pas.

---

## 3. Les minuteurs

### Boucle retour

```
deadlineAt              → rien n'est envoyé, la fenêtre s'ouvre
deadlineAt + 15 min     → rappel 1   (push time-sensitive)
deadlineAt + 30 min     → rappel 2
deadlineAt + 45 min     → rappel 3   « sans réponse à HH:MM, on prévient X »
deadlineAt + 60 min     → message ② au contact principal
        + 15 min        → message ② au contact de secours, si le principal n'a rien ouvert
```

`deadlineAt` par défaut : **fin du créneau + 1 h**, modifiable par l'utilisateur à l'armement. Un `/snooze` le décale de 30 min et **réarme toute la chaîne**.

### Boucle aller

```
début du créneau + 15 min  → demande 1 « tu y es ? »
              + 30 min     → demande 2
              + 45 min     → demande 3 sans réponse ⇒ état PERDU EN CHEMIN
                             · notification in-app à l'organisateur
                             · message ⑤ au contact
                             · incident journalisé
```

Le statut « perdu en chemin » ne se pose **qu'à la troisième demande**. À T+15, c'est une question — quinze minutes, c'est un métro en retard ou une place de parking, et coller une étiquette alarmante à ce moment-là serait faux.

L'organisateur n'est prévenu qu'à T+45 lui aussi. À T+15 il recevrait une notification pour chaque retardataire de chaque séance, et il couperait ses notifications dès la troisième.

### Interruption

`alreadyHome: true` → code demandé, clôture immédiate.
`alreadyHome: false` → `deadlineAt` recalé sur *maintenant + `travelMinutes`*, où **`travelMinutes` est envoyé par l'app** (45 par défaut, ajusté par l'utilisateur). Le serveur ne calcule ni n'estime rien : il applique la durée reçue. Statut public « repartie plus tôt », code demandé à l'arrivée.

C'est la branche qui compte : refermer la veille en **quittant** le lieu l'éteindrait juste avant le trajet de retour — celui-là même qu'on voulait couvrir, et d'autant plus si la personne part parce que ça se passait mal.

### Course à gérer

Quelqu'un valide à 23:29 pendant que le rappel de 23:30 se prépare. La clôture doit annuler les envois en attente **dans la même transaction**, et l'app doit pouvoir retirer une notification déjà délivrée. Prévoir un identifiant de collapse stable par veille dans le payload APNs.

---

## 4. Les cinq gabarits

Aucun champ libre. Un texte que l'utilisateur pourrait écrire, envoyé par SMS depuis une marque de confiance à un numéro arbitraire, serait un outil de harcèlement offert.

**① Accord du contact** — présentation, ce qu'il recevra, deux liens accepter/refuser, et la phrase « un seul message vous sera envoyé sans réponse de votre part ».

**② Alerte retour** — `{prenom_nom}` n'a pas confirmé son retour à `{heure_limite}` après trois rappels. Dernier signe de vie `{heure}`, `{lieu_nom}`, `{ville}`. Activité `{titre}`, terminée à `{heure_fin}`. Lien `{lien_statut}`. Et la clause : *« meetDo ne sait pas si elle va bien — seulement qu'elle n'a pas répondu. En cas de danger immédiat, appelez le 112. »*

**③ Levée** — fausse alerte, `{prenom}` vient de confirmer. Même canal, même fil. **Non facultatif.**

**④ E-mail d'alerte** — version longue de ②, avec la chronologie complète, le lien en bouton, et un désabonnement réel.

**⑤ Non-arrivée** — distinct de ②. `{prenom_nom}` n'est pas arrivée : partie de `{lieu_depart}` à `{heure_depart}` pour `{titre}`, `{lieu_nom}`, `{ville}` à `{heure_debut}`. Un contact qui lit « n'est pas rentrée » alors que la personne n'est jamais partie cherche au mauvais endroit.

**L'organisateur ne reçoit aucun de ces SMS** — seulement une notification in-app portant le nom, l'absence de validation et l'heure. Ni le lieu de départ, ni le contact, ni la position ne le regardent.

---

## 5. La page publique

Extension de `/public/safety/{token}`, qui existe déjà.

- **Rendu côté serveur**, avec `<meta http-equiv="refresh" content="60">` comme plancher. Elle s'ouvre depuis un SMS à 2 h du matin, dans un navigateur intégré, parfois un client mail d'entreprise, parfois sur un vieux téléphone : un rendu qui dépend d'un sondage JS peut afficher une page vide au pire moment.
- `ETag` + `Cache-Control: max-age=20`. Dix contacts qui laissent l'onglet ouvert toute la nuit ne doivent pas faire un déni de service accidentel.
- Elle affiche l'heure de dernière mise à jour. Quand le téléphone est mort, « actualisé il y a 47 min » est plus utile qu'une page qui feint la fraîcheur.
- **Six états** : en trajet · sur place · repartie plus tôt · retour à confirmer · alerte envoyée · rentrée. Le dernier compte autant que les autres — quelqu'un réveillé par ② doit pouvoir recharger et lire « bien rentrée » sans attendre ③.
- Deux boutons d'accusé : « j'ai vu » et « je l'ai eue au téléphone ». Ils remontent dans l'app. **Aucun bouton ne clôture** : la page est publique et non authentifiée, un bouton de clôture clôturerait pour quiconque a le lien.
- Expire 24 h après la clôture. Révocable par son propriétaire à tout moment.

**Le lien d'urgence naît avec l'alerte**, pas à l'armement. S'il partait à l'armement, le contact verrait chaque soirée de quelqu'un : la veille deviendrait un mouchard, et la personne arrêterait de l'armer.

Le filtre de contenu de `safety_share_message.dart` s'applique tel quel : **jamais l'adresse exacte** (le `displayAddress` d'un créneau contient le numéro et la rue quand l'hôte a gardé le lieu privé), jamais un téléphone, jamais un e-mail, jamais la liste des participants.

---

## 6. Le principe qui doit tenir jusque dans la base

> « L'utilisateur ne doit pas avoir l'impression d'être surveillé et suivi par des inconnus. »

Trois conséquences côté serveur :

1. **Aucune vérification silencieuse.** L'arrivée est déclarative, et le serveur ne croise pas la position pour en juger. Une mesure que la personne ne voit pas est de la surveillance, même bienveillante.
2. **L'organisateur ne voit jamais les retours** de ses participants, même agrégés. Les arrivées, oui, nominatives — c'est de la présence, pas de la traçabilité.
3. **Tout expire par défaut.** Points de position purgés à 30 jours, codes détruits à la clôture, liens publics à durée de vie bornée. La donnée qui reste doit être celle qu'on a décidé de garder, pas celle qu'on a oublié de supprimer.

Et un garde-fou produit : **un « perdu en chemin » ne compte ni comme une absence, ni contre la fiabilité, la série ou les badges.** Sinon le produit punit quelqu'un pour un incident de sécurité — et cette personne désarme la veille la fois d'après. Exactement l'inverse de l'objectif.

---

## 7. Les cinq points d'infrastructure — tranchés le 31/08

### 7.1 · Le hachage du code : HMAC-SHA256 avec un poivre hors base

Un code de 5 caractères sur un alphabet de 28 symboles vaut ~17 millions de combinaisons. C'est confortable face à trois essais en ligne, et **dérisoire hors ligne** : si la base fuit, l'espace entier se parcourt en quelques secondes avec un hachage rapide, en quelques heures avec bcrypt. Un hachage lent ne protège pas un secret aussi court — il ralentit une attaque, il ne l'empêche pas.

**Retenu : `HMAC-SHA256(code, sel_par_code)` avec une clé (poivre) stockée hors de la base** — variable d'environnement ou KMS, jamais dans une table.

La différence est de nature, pas de degré : une fuite de la base **seule** ne permet aucune attaque hors ligne, l'attaquant n'ayant pas la clé. Il lui faudrait un second compromis, indépendant.

À écrire noir sur blanc dans la documentation interne, parce que c'est vrai et que le prétendre autrement mène à des décisions fausses plus tard : **la défense principale reste le plafond de 3 essais et la durée de vie de quelques heures du code.** Le hachage protège du regard interne et de la fuite de base. Il ne protège pas d'un attaquant qui aurait tout.

La rotation du poivre doit être possible sans casser les veilles en cours : garder un identifiant de version de clé sur chaque ligne `ReturnCode`.

### 7.2 · SMS : Twilio région UE, file dédiée, SLO 30 s

**Fournisseur retenu : Twilio, région UE**, pour la maturité des accusés de remise (DLR par webhook — l'écran « Remis / Lu » en dépend) et l'expéditeur alphanumérique. Vonage et OVH sont des replis acceptables. Brevo est écarté : son SMS est conçu pour du marketing, pas pour du transactionnel critique.

Trois exigences comptent davantage que le choix du fournisseur :

1. **File dédiée haute priorité.** Les alertes ne partagent aucune queue avec les notifications produit. Un SMS d'alerte coincé derrière quatre mille rappels de séance est le scénario qui rend tout ce module inutile.
2. **SLO : 95 % remis en moins de 30 s.** À instrumenter et à mesurer, pas à espérer. Un message d'alerte livré avec vingt minutes de retard ne vaut à peu près rien.
3. **SMS et e-mail partent en parallèle, jamais en cascade.** L'e-mail n'est pas un repli en cas d'échec du SMS : c'est un second canal, envoyé au même instant.

**Point réglementaire à ne pas découvrir en production :** en France, un expéditeur alphanumérique (« meetDo ») **ne peut pas recevoir de réponse**. Quelqu'un qui répondrait « je l'ai eue au téléphone » écrirait dans le vide, au pire moment possible. Le gabarit ② doit donc renvoyer explicitement vers la page publique pour tout retour — et ne jamais laisser croire qu'une réponse au SMS sera lue.

### 7.3 · `GET /reports/me` — livré avec `/incidents`

Les deux routes partagent la même forme ; les séparer dans le temps n'a aucun intérêt technique et laisse l'écran de suivi vide.

```
GET /reports/me → Page<ReportSummary>
ReportSummary { id, targetType, state, createdAt, updatedAt }
state ∈ RECEIVED · IN_REVIEW · RESOLVED · DISMISSED
```

Quatre états suffisent. **`DISMISSED` doit exister et être affichable :** un signalement classé sans suite qu'on maintient indéfiniment en « en cours » est pire qu'un refus assumé — il apprend à l'utilisateur que le suivi ment.

### 7.4 · Le refus d'un contact est global au numéro

Si un refus ne valait que pour la personne qui a désigné le contact, il suffirait d'un second compte pour redésigner le même numéro. Le module deviendrait un canal de harcèlement avec une étape de contournement triviale.

**Retenu : un refus vaut pour tout meetDo, définitivement, quel que soit le compte qui redésigne.**

Et il se stocke sous forme de **hachage du numéro**, jamais en clair. Sinon on constitue une liste de numéros de personnes qui n'ont jamais voulu de ce produit — précisément le fichier qu'on n'a aucune raison de détenir.

### 7.5 · Le trajet de retour n'est pas estimé, il est demandé

La question de départ était mal posée. Ses deux réponses possibles — calcul serveur, ou constante côté app — supposaient l'une comme l'autre qu'on sache où habite la personne. Le calcul serveur exigerait de stocker une adresse de domicile : ce qu'on a refusé pour le contact d'urgence, et ce qui contredit frontalement le principe directeur du module.

**Retenu : l'app envoie `travelMinutes`.** Défaut à 45, ajusté par l'utilisateur en un tap (−15 / +15) sur l'écran d'interruption. Le serveur applique la valeur reçue, sans la corriger ni la plafonner autrement que par une borne de bon sens (15 à 240 minutes).

Quelqu'un qui quitte une séance sait mieux que n'importe quel algorithme s'il est à dix minutes ou à une heure de chez lui. C'est un curseur au lieu d'une inférence — et c'est la même règle que partout ailleurs ici : **rien ne se déduit dans le dos de quelqu'un.**

---

## 8. Ordre de service souhaité

L'app se construit **derrière des drapeaux éteints**, comme `features/safety` l'a été : les écrans existent, ils ne s'affichent que quand la route répond. Vous n'êtes donc bloqué par rien de notre côté, et nous ne sommes bloqués par rien du vôtre — mais l'ordre ci-dessous minimise le temps où du code écrit attend sans pouvoir être vérifié.

| Priorité | Routes | Débloque |
|---|---|---|
| **1** | `GET|POST|DELETE /guardians`, `POST /guardians/{id}/invite`, les deux routes publiques de consentement | L'armement complet — sans contact accepté, rien ne peut être armé |
| **2** | `POST /watches`, `GET /watches/active`, `GET /watches/{id}`, `DELETE /watches/{id}` | L'écran de veille armée et le miroir |
| **3** | `POST /watches/{id}/arrival` (+ génération du code), `POST /watches/{id}/close` | Le cœur du module |
| **4** | Les minuteurs (§3) et les gabarits ② ③ ④ | La boucle de relance et l'escalade |
| **5** | `still-coming`, `abandon`, gabarit ⑤, notification in-app à l'organisateur | Le trajet aller |
| **6** | `interrupt`, `snooze`, `panic`, `resend-code` | Les sorties |
| **7** | `POST /incidents`, `GET /incidents/me`, **`GET /reports/me`** | Le signalement et son suivi |

La priorité 1 est la seule qui n'a aucune dépendance sur les autres : elle peut partir tout de suite, et elle conditionne tout le reste — une veille sans contact accepté n'est pas armable.

## 9. Ce qui n'est pas demandé

Pour éviter le travail fait en trop, quelques non-besoins explicites :

- **Aucun calcul de position, d'itinéraire ou de plausibilité.** L'arrivée est déclarative. Le serveur enregistre ce que l'app lui envoie et ne le vérifie pas — c'est une décision produit, pas un manque.
- **Aucune adresse postale**, ni pour l'utilisateur, ni pour le contact.
- **Aucune vue « retours de mes participants »** pour l'organisateur, même agrégée. Les arrivées oui, nominatives ; les retours jamais.
- **Aucun champ de texte libre** dans les cinq gabarits.
- **Aucune notification de clôture normale** au contact, sauf option explicite de l'utilisateur, désactivée par défaut. Prévenir Camille chaque soir que tout va bien est le meilleur moyen qu'elle cesse de lire.
