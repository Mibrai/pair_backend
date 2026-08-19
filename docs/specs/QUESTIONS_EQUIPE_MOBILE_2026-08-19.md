# Ce qui attend l'équipe mobile — 19 août 2026

> Écrit depuis le backend, à l'issue des lots 0, A1→A5, B1→B3, C1→C4 et D1 du
> TODO v2 (migrations V60 → V71, 664 tests verts).
>
> Le dépôt nomme ses échanges `PROMPT_*` (client → backend) et `REPONSE_*`
> (backend → client). Celui-ci va dans le même sens qu'un `REPONSE_` mais ne
> répond à rien : il pose les questions que le backend ne peut pas trancher
> seul, et signale ce qui a été tranché par défaut faute de réponse.
>
> **Trois niveaux :** ⛔ bloque une fonctionnalité livrée · ⚠️ décidé par défaut,
> à confirmer · ℹ️ à savoir pour intégrer.

---

## ⛔ 1. Quatre valeurs sans lesquelles les liens ne rouvrent pas l'application

C'est le seul blocage réel du chantier. La page publique de créneau (lot B1) est
écrite, testée et déployable, mais un lien `https://meetdo.fun/s/{token}` collé
dans une conversation ouvrira un **navigateur** et non l'application tant que les
fichiers d'association ne sont pas servis.

Ils le sont par le backend, sur `/.well-known/apple-app-site-association` et
`/.well-known/assetlinks.json`, et **rendent 404 tant que ces valeurs sont
vides** :

| Propriété | Valeur attendue | Qui la détient |
|---|---|---|
| `pair.mobile.apple-app-id` | `TEAM_ID.BUNDLE_ID` | compte développeur Apple |
| `pair.mobile.android-package` | nom de paquet | projet Android |
| `pair.mobile.android-sha256` | empreinte SHA-256 du certificat | voir ci-dessous |

**Sur l'empreinte Android**, deux pièges classiques : c'est celle du certificat
**de release**, pas de debug ; et si l'application passe par **Play App Signing**,
c'est l'empreinte fournie par la console Play qui compte, pas celle du keystore
local.

Le 404 est délibéré. Publier une association aux valeurs inventées serait pire
que n'en publier aucune : Apple et Google les mettent en cache agressivement, et
une association fausse mémorisée par un appareil est plus longue à corriger
qu'une association absente.

**Questions ouvertes en plus des trois valeurs :**

- Existe-t-il un **schéma d'URI personnalisé** (`meetdo://…`) à faire figurer, ou
  les liens universels suffisent-ils ?
- Quels sont les **identifiants App Store et Play Store** ? La page publique doit
  proposer un repli vers le magasin quand l'application n'est pas installée ;
  elle n'affiche aujourd'hui qu'un bouton vers le lien universel.

---

## ⛔ 2. Deux routes que le blocage ne peut pas atteindre

`GET /api/map/activities` et `GET /api/users/{userId}/programs` ne reçoivent
**aucun `@AuthenticationPrincipal`**. Le filtre de blocage (lot A3) ne peut donc
pas s'y appliquer : un profil rendu introuvable garde des programmes lisibles, ce
qui n'est pas un profil masqué.

Deux issues, et le choix vous appartient parce qu'il touche l'appel client :

1. **Ces routes deviennent authentifiées.** `/api/map/activities` est aujourd'hui
   publique par choix explicite dans la configuration — le client l'appelle-t-il
   avant connexion ? Si non, la fermer coûte une ligne.
2. **Elles restent publiques et n'appliquent pas le blocage**, ce qui est
   documenté comme une limite assumée.

Je n'ai pas tranché seul : changer la signature d'une route publique se voit
côté client.

---

## ⚠️ 3. Décidé par défaut — à confirmer ou à renverser

### 3.1 Le prénom de l'organisateur n'existe pas dans le modèle

`users` ne porte qu'un `display_name` ; il n'y a ni `first_name`, ni dérivation
nulle part. Or la page de sécurité (A4) et la page publique de créneau (B1)
affichent toutes deux « le prénom de l'organisateur ».

**Retenu :** réduction au premier segment avant l'espace, bornée à 40
caractères. « Marie Dupont » → « Marie », « Jean-Pierre Martin » →
« Jean-Pierre », « ChloéB » → « ChloéB » (aucun espace, rien à retirer).

La justification n'est pas grammaticale mais de périmètre : le nom affiché est
déjà public **dans** l'application, et ces deux pages le rendent lisible **sur le
web ouvert**, sans compte. La règle se trompera sur les noms composés sans trait
d'union et sur les cultures où le nom précède le prénom.

**Tension à connaître :** une page de sécurité sert à ce qu'un proche puisse
*identifier* quelqu'un en cas de problème, et « Marie » identifie moins bien que
« Marie Dupont ». La spécification a tranché en faveur de la discrétion ; si le
produit préfère l'inverse, c'est une ligne à changer. La réponse durable, si la
justesse compte un jour, est une colonne `first_name` peuplée par l'interface —
pas une heuristique plus fine.

### 3.2 Le DTO de réponse du chemin court

La spécification demandait « le même DTO que `POST /api/slots` ». **Cette route
n'existe pas** — la création passait par `POST /api/programs/{id}/schedules`.

**Retenu :** `POST /api/quick-slots` rend un `SlotFeedItemDto`, c'est-à-dire
exactement ce que rend `GET /api/slots/{id}`. C'est le seul modèle de « créneau »
que le client possède déjà de ce côté. La réponse passe par le même code, donc
elle est identique par construction et non par recopie.

**À confirmer :** est-ce bien ce modèle-là que l'application manipule ?

### 3.3 Le texte des règles de communauté reste embarqué

**Retenu :** le serveur porte la **version en vigueur** et la trace
d'acceptation ; le texte reste dans l'application, comme
`legal_terms_content.dart` aujourd'hui.

Le servir demanderait un pipeline de contenu multilingue que le projet n'a pas —
les fichiers `messages*.properties` conviennent aux libellés courts, pas à
plusieurs paragraphes en trois langues.

**Conséquence pour vous :** `POST /api/users/me/guidelines/accept` exige la
version que vous venez d'afficher, et la refuse si ce n'est pas celle en
vigueur (`400 GUIDELINES_VERSION_MISMATCH`). C'est délibéré : sans cela, une
application restée sur un texte ancien ferait enregistrer l'acceptation d'un
texte que personne n'a lu. Le bon réflexe en cas de refus est de relire
`GET /api/users/me/guidelines` et de réafficher.

### 3.4 Le signal de fiabilité est un libellé, jamais un chiffre

`UserPublicDto.reliabilitySignal` vaut `USUALLY_SHOWS_UP` ou `null`. **Il
n'existe aucune valeur négative et il n'en existera pas.**

- Ne rien afficher quand il est nul. L'absence de signal n'est pas un mauvais
  signal : c'est l'état de quiconque vient d'arriver.
- Ne pas tenter de reconstituer un pourcentage : les compteurs ne sortent pas de
  l'API, et `practice-stats` ne porte pas le dénominateur — c'est verrouillé par
  un test pour que tout ajout se voie.
- Ne pas trier ni filtrer sur ce champ.

### 3.5 Être en liste d'attente ne crée pas de conflit d'agenda

On peut patienter sur deux créneaux qui se chevauchent — c'est l'usage même
d'une liste d'attente. Le conflit est vérifié **au moment de la promotion** : si
la personne s'est engagée ailleurs entre-temps, elle n'est pas promue de force et
reste dans la file, la place allant au suivant.

---

## ℹ️ 4. À vérifier de votre côté

### 4.1 `activityLevels` est **déjà** un filtre serveur

L'audit a montré que `GET /api/activities/browse` accepte `activityLevels` et
l'applique en SQL, requête principale **et** compteur, depuis un lot antérieur.

Le TODO v2 le listait comme un filtre à porter côté serveur (D8). Si l'Explorer
filtre encore les niveaux localement sur les pages déjà chargées, **c'est que le
paramètre n'est pas envoyé**, pas qu'il manque. À vérifier avant que nous
n'écrivions quoi que ce soit pour D8.

### 4.2 L'écran de découverte a besoin de la position

`GET /api/slots/feed` exige `lat`, `lng` et `radiusMeters` : sans eux, la réponse
est un **400**, pas une liste vide. Le dernier écran de l'onboarding doit donc
disposer de la position avant d'appeler.

`GET /api/activities/suggested?lat=&lng=&limit=12`, en revanche, **ne rend jamais
une liste vide** tant que la base contient des activités : à défaut de voisinage
il propose les plus pratiquées ailleurs et le dit par un drapeau `fallback`. Le
client peut vouloir formuler différemment — « populaire sur meetDo » plutôt que
« près de chez vous ».

### 4.3 Les étapes d'onboarding

`OnboardingStep` vaut `WELCOME`, `LOCATION`, `ACTIVITIES`, `DISCOVERY`, `DONE`.
L'ordre est un contrat : `PATCH /api/users/me/onboarding` **ne fait jamais
reculer** un parcours, et rejouer une étape déjà franchie répond `200` sans rien
changer. Une étape inconnue doit être traitée comme « en cours », jamais comme
une erreur.

Cette liste vous convient-elle, ou le parcours réel a-t-il d'autres écrans ?

---

## ℹ️ 5. Ce qui a changé dans les réponses existantes

Tous ces ajouts sont **additifs** : un client ancien les ignore sans casser.

**`GET /api/users/me` (`UserPrivateDto`)**

| Champ | Pour quoi faire |
|---|---|
| `onboardingCompletedAt`, `onboardingStep` | décider où atterrir au démarrage |
| `guidelinesVersion`, `guidelinesAcceptanceRequired` | présenter les règles si besoin |

Ces quatre champs sont là **précisément** pour éviter un second appel réseau au
lancement, qui se voit à l'œil nu.

**`GET /api/users/{id}` (`UserPublicDto`)** — `reliabilitySignal` (voir 3.4).

**Créneaux (`SlotFeedItemDto`)** — `myWaitlistPosition` (rang dans la file, nul
si absent) et `primaryLanguage` (langue de la séance, nulle dans le cas normal).

**Programmes (`ProgramDto`)** — `createdVia` vaut `FULL` ou `QUICK`. Un programme
`QUICK` n'a ni description ni objectifs **parce qu'on ne les lui a jamais
demandés**, et non parce que son auteur les a laissés vides.

**Deux nouveaux types de notification à gérer :**

- `WAITLIST_PROMOTED` — une place s'est libérée, la personne y entre
- `SLOT_CANCELLED` — existait déjà, mais part désormais aussi **par e-mail**,
  seul cas où le double canal est justifié

---

## ℹ️ 6. Les routes ajoutées

```
POST   /api/quick-slots                      publier un créneau en un appel
GET    /api/activities/suggested             suggestions d'accueil

GET    /api/users/me/onboarding              état du parcours
PATCH  /api/users/me/onboarding              franchir une étape (idempotent)
POST   /api/users/me/onboarding/skip         passer (autorisé, tracé)

GET    /api/users/me/guidelines              version en vigueur et acceptation
POST   /api/users/me/guidelines/accept       accepter (version exigée)

POST   /api/users/{userId}/block             bloquer (idempotent)
DELETE /api/users/{userId}/block             débloquer
GET    /api/users/me/blocked                 la liste, paginée

GET    /api/users/me/languages               mes langues
PUT    /api/users/me/languages               remplacer la liste entière

POST   /api/slots/{id}/safety-share          lien de sécurité temporaire
GET    /api/slots/{id}/share-link            adresse publique du créneau
POST   /api/slots/{id}/invite                lien d'invitation traçable
POST   /api/invitations/{code}/accept        accepter et rejoindre
GET    /api/invitations/me                   mes invitations et leur statut

POST   /api/slots/{id}/waitlist              se mettre en liste d'attente
DELETE /api/slots/{id}/waitlist              en sortir
GET    /api/slots/{id}/waitlist              la file (organisateur seul)
POST   /api/slots/{id}/cancel                annuler et prévenir tout le monde

GET    /api/slots/{id}/calendar.ics          ce créneau, pour un agenda
GET    /api/slots/mine/calendar.ics          tous mes créneaux à venir

/public/safety/{token}                       page de sécurité, sans compte
/public/slots/{token}                        créneau public en JSON
/public/slots/{token}/page                   page publique
/public/slots/{token}/calendar.ics           version agenda
/s/{token}                                   adresse courte à partager
```

**Une règle transverse à connaître :** toutes les nouvelles routes rendent
**404 et jamais 403** sur une ressource inaccessible. Un 403 dirait « elle
existe, mais » — ce qu'un blocage, un lien expiré ou un créneau privé ne doivent
pas laisser déduire. Le refus est même **asymétrique** sur le blocage :
`USER_BLOCKED` n'est rendu qu'à la personne qui a bloqué ; l'autre reçoit un 404
ordinaire.

---

## Ce qui n'attend personne

Pour mémoire, ces points ont été tranchés côté backend et ne demandent rien :

- le canal e-mail existe désormais, borné aux notifications critiques ;
- le créneau `ONLINE` était impossible à créer (contrainte `NOT NULL` sur la
  position) — c'était la cause des créneaux enregistrés en `0,0` signalés par le
  terrain ;
- l'ordre `x=lng` / `y=lat` était correct : sur ce point la note du terrain
  était périmée ;
- `addressPublic` est requis **exactement quand `placeType == PUBLIC`**, ce que
  la spécification OpenAPI ne sait pas exprimer.
