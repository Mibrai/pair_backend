# Spécification frontend — meetDo / Pair
**Version 2.0 — 20 août 2026**

> **Ce que ce document est.** L'architecture des écrans et leur correspondance avec les
> routes du backend. La partie « design » (§ Design Philosophy à §17) date de la version 1.0
> et est conservée telle quelle : elle décrit une intention graphique, pas un contrat.
>
> **Ce qu'il n'est pas.** Ni la liste des routes — elle vit dans
> [`ARCHITECTURE_BACKEND.md`](ARCHITECTURE_BACKEND.md) §4, contrôleur par contrôleur —, ni
> la référence exécutable, qui est `/swagger-ui.html` sur une instance démarrée.
>
> Les deux audits d'alignement du 3 juillet 2026 qui suivaient ce document ont été
> **archivés** dans [`archived/FRONTEND_API_AUDIT_2026-07-03.md`](archived/FRONTEND_API_AUDIT_2026-07-03.md).
> Ils annonçaient 47 % d'alignement et des routes manquantes — `logout`, `join`/`leave`,
> la messagerie — qui existent toutes depuis : les laisser ici donnait une image fausse du
> backend à quiconque ouvrait le fichier.
>
> Ce qui reste à vérifier ou à trancher côté client est réuni dans
> [`specs/VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md`](specs/VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md).

## Design Philosophy

**Athletic Clarity**: Clean, high-contrast interface that balances fitness energy with social trust. The signature element is **schedule rhythm bars** that make time commitments visible at a glance.

**Color System**:
- **Court Green** `#1A4D3A` - Primary
- **Clay** `#C85A3D` - Secondary
- **Track Orange** `#FF7A3D` - Accent
- **Concrete** `#E8E6E1` - Neutral
- **Chalk** `#F9F8F6` - Background
- **Charcoal** `#2A2928` - Text

**Typography**:
- **Display**: Syne Bold (titles, stats)
- **Body**: Inter (UI text, descriptions)
- **Labels**: Inter Semibold (metadata)

**Inspiration**: Strava (activity feeds), Meetup (event discovery), LinkedIn (recommendations), Airbnb (reviews), Bumble (proximity)

---

## 1. AUTHENTICATION & ONBOARDING

### 1.1 Landing Page
**Public page**

**Sections**:
- Hero with search preview
- How it works (4 steps)
- Sample program cards
- CTAs: Login, Sign Up

---

### 1.2 Registration Flow
**Endpoint**: `POST /api/auth/register`

**Multi-step**:
1. **Credentials**: Email, password, confirm
2. **Profile**: Name, DOB, gender (optional), photo
3. **Location**: City search or GPS
4. **Activities**: Select interests (skippable)

**Success**: Email verification notice

---

### 1.6 Parcours d'accueil *(nouveau — août 2026)*

**Routes** : `GET`/`PATCH /api/users/me/onboarding`, `POST /api/users/me/onboarding/skip`

**Quatre écrans, dans cet ordre** — et cet ordre *est* le contrat :

| # | Étape | Question posée |
|---|---|---|
| 1 | `ACTIVITIES` | « Qu'est-ce que tu aimes faire ? » |
| 2 | `LEVELS` | « À quel niveau ? » |
| 3 | `LOCATION` | « Où cherches-tu ? » |
| 4 | `PREVIEW` | « Voilà ce qui se passe autour de toi » |

Le `PATCH` enregistre une étape **franchie** et est idempotent : rejouer une étape déjà
enregistrée, ou en annoncer une antérieure, répond `200` sans rien changer — le réseau
mobile double les requêtes et les livre parfois dans le désordre, et **un parcours ne
recule jamais**.

Il n'y a **pas d'étape « terminé »** : franchir le dernier écran pose
`onboardingCompletedAt`, et c'est cette date qui dit que l'accueil est fait. `skip` referme
le parcours en **conservant** l'étape atteinte — c'est la seule information qu'un abandon
apporte, et l'écraser effacerait où les gens décrochent.

> **Un piège documenté.** La première version de cette énumération décrivait la
> spécification et non l'application : deux valeurs seulement existaient des deux côtés, et
> **dans l'ordre inverse**. L'étape « position » était acceptée puis ignorée en `200` — un
> échec silencieux des deux côtés, visible du seul utilisateur qui reprenait au premier
> écran. L'ancien vocabulaire (`WELCOME`, `DISCOVERY`, `DONE`) reste accepté en entrée et
> traduit, le temps qu'une version publiée cesse de le parler ; il perd en revanche la
> distinction entre les deux premiers écrans, donc **envoyer les vrais noms dès que
> possible**.

L'état voyage aussi sur `GET /api/users/me` (`onboardingStep`, `onboardingCompletedAt`) :
le client n'a pas besoin d'un second appel au lancement pour savoir où atterrir.

**Premières suggestions** : `GET /api/activities/suggested?lat=&lng=&limit=` — **ne rend
jamais une liste vide** tant que le catalogue n'est pas vide. Le drapeau `fallback`
distingue « près de chez toi » de « populaire sur meetDo » ; une suggestion lointaine
présentée comme voisine est la meilleure façon de faire douter de toutes les autres.

---

### 1.3 Login
**Endpoint**: `POST /api/auth/login`

**Fields**: Email, password, remember me
**Links**: Forgot password
**Error**: Rate limit, unverified email

---

### 1.4 Email Verification
**Endpoint**: `GET /api/auth/verify-email?token={token}`

**States**: Success (checkmark, redirect), Error (resend button)

---

### 1.5 Password Reset
**Endpoints**:
- `POST /api/auth/forgot-password` (email input)
- `POST /api/auth/reset-password` (new password form)

---

## 2. USER PROFILE & SETTINGS

### 2.1 My Profile
**Endpoint**: `GET /api/users/me`

**Sections**:
- Header: Photo, name, age, location, stats
- Activities (manage link)
- Stats: Sessions, partners, rating, streak
- Badges grid
- Programs created
- Reviews received
- Recommendations

**Actions**: Edit, Settings, Message (self-note)

---

### 2.0 Ce qui a changé côté confidentialité *(août 2026)*

**Les réglages sont désormais appliqués.** Ils étaient stockés, réglables, relus — et lus
par aucun code de rendu : un profil « privé » était servi intégralement. Un client qui
supposait que le serveur ne filtrait pas doit cesser de filtrer lui-même.

| `profileVisibility` | Ce que reçoit un tiers |
|---|---|
| `PUBLIC` | tout |
| `FRIENDS` | tout **si abonné**, sinon la fiche masquée |
| `PRIVATE` | fiche masquée |

**Fiche masquée** = `bio`, `badgeCodes`, `subscriberCount`, `reliabilitySignal` et
`isOnline` nuls ou vides. Restent **toujours** visibles `id`, `displayName`, `avatarUrl` et
`verificationStatus` : ce sont les éléments par lesquels une personne est reconnue dans une
conversation ou une liste de participants, et les masquer casserait l'application sans
protéger personne.

`FRIENDS` s'appuie sur l'**abonnement**, faute de notion d'amitié dans le produit.

**`GET /api/users/me/preview`** rend ce qu'un inconnu voit du profil de l'appelant — le même
code, avec la relation d'un inconnu. Sur un profil réglé `FRIENDS`, l'aperçu montre la vue
**la plus restrictive** : c'est celle qui intéresse la personne qui règle.

---

### 2.6 Blocage *(nouveau)*

**Routes** : `POST`/`DELETE /api/users/{userId}/block`, `GET /api/users/me/blocked`

Le masquage est **bilatéral** : peu importe qui a bloqué, les deux personnes cessent de se
voir — profil, programmes, fil de créneaux, carte, recherche, conversations, abonnements.

**Le refus est un `404`, jamais un `403`**, et le client ne doit pas chercher à le rendre
plus explicite : un code nommé apprendrait le blocage à celui qui l'a subi, ce que toute la
règle cherche à éviter. Une ressource qui « n'existe plus » est le message attendu.

Bloquer **rompt les abonnements** dans les deux sens et **supprime la conversation** : ces
effets sont immédiats et non réversibles par un déblocage.

---

### 2.7 Langues et disponibilités *(nouveau)*

**Routes** : `GET`/`PUT /api/users/me/languages`, `GET`/`PUT /api/users/me/availability`

Les deux sont des **remplacements complets** : le `PUT` porte la grille entière, pas un
delta. Cocher deux fois la même case n'est pas une erreur et ne doit pas échouer.

Disponibilités : `dayOfWeek` en numérotation **ISO** (1 = lundi … 7 = dimanche) et
`timeSlot` parmi `MORNING`, `AFTERNOON`, `EVENING`, `NIGHT`.

> **Une disponibilité déclarée pondère, elle ne filtre jamais.** Le fil fait remonter ce qui
> tombe bien au sein d'un même jour, sans jamais bousculer la chronologie et sans jamais
> masquer un créneau hors des cases cochées. L'interface ne doit donc pas la présenter comme
> un filtre : quelqu'un qui a coché « mardi soir » peut très bien vouloir un samedi matin.

---

### 2.8 Heures de silence *(nouveau)*

**Routes** : `GET`/`PUT /api/notifications/quiet-hours`

`start` et `end` en heures pleines (0–23) **dans le fuseau de l'appareil**. Les deux vont
ensemble : deux valeurs nulles retirent le silence, **une seule est refusée** en `400`.
Deux bornes égales aussi — « 22 → 22 » se lit aussi bien « une minute » que « toute la
journée ».

La fenêtre **traverse minuit** dans le cas courant : « 22 → 7 » est valide et normal.

**Ce que le silence coupe, et ce qu'il ne coupe pas** : il coupe la notification push, pas
la notification elle-même, qui est écrite et attend au réveil. L'interface doit le dire
ainsi — « ne pas être dérangé », pas « ne pas recevoir ».

**Ce qui passe outre** : annulation d'un créneau ou d'un programme, changement d'horaire, et
le rappel de séance — il part deux heures avant quelque chose qu'on a choisi de rejoindre, et
l'étouffer transformerait un réglage de confort en séance manquée. Une **diffusion de
programme** ne passe pas : son contenu est libre, et la laisser passer donnerait à tout
auteur le moyen de réveiller ses participants avec n'importe quel message.

---

### 2.2 Edit Profile
**Endpoint**: `PUT /api/users/me`

**Modal/Full-screen form**:
- Photo upload (`POST /api/media/upload/avatar`)
- Name, bio, DOB, gender
- Location (`PUT /api/users/me/location`)
- Privacy: visibility, show age, show last active

---

### 2.3 Public Profile
**Endpoint**: `GET /api/users/{id}`

**Similar to My Profile but**:
- No edit button
- Message CTA prominent
- Recommend button (if eligible)
- Report option
- Limited data

---

### 2.4 Settings
**List view with sections**:

**Account**: Email, password, language

**Notifications** (`GET /api/notifications/preferences`):
- Per type: In-app, Email, Push
- Frequency: Real-time, Daily, Weekly

**Privacy**: Profile visibility, location, activity map

**Devices** (`GET /api/notifications/devices`):
- List, remove tokens

**Data & Privacy**:
- `GET /api/gdpr/export` - Download data
- `DELETE /api/gdpr/delete-account` - Delete account

---

### 2.5 Activity Management
**Endpoints**:
- `GET /api/categories`
- `GET /api/activities`
- `GET/POST/PUT/DELETE /api/users/me/activities`
- `PATCH /api/users/me/activities/{id}/visibility`

**Modal**: Search/browse activities, select, set skill level, toggle map visibility

---

## 3. PROGRAM MANAGEMENT

### 3.1 Browse Programs
**Endpoint**: `GET /api/programs`

**Feed with filters**:
- Activity type, distance, skill level, schedule, spots
- Sort: Nearest, highest rated, recent

**Program Card**:
- **Schedule rhythm bar** (signature element: visual dots/bars for days)
- Title, activity badge
- Distance, skill level, spots
- Creator avatar, rating
- View button

---

### 3.2 Program Detail
**Endpoint**: `GET /api/programs/{id}`

**Sections**:
- Hero image/icon
- Title, badges, stats (location, difficulty, spots, rating)
- **Schedule rhythm bar** + times
- Description
- Location with mini map
- Organizer card (avatar, rating, member since)
- Reviews summary + list
- Similar programs

**Sticky CTA**: Join or Message

---

### 3.3 Create Program
**Endpoint**: `POST /api/programs`

**Multi-step**:
1. **Activity & Title**: Select activity, title, description, skill level
2. **Schedule** (`POST /api/programs/{id}/schedules`): Days, time, duration (multiple schedules allowed)
3. **Location**: Search, GPS, map preview
4. **Details**: Max participants, cost, what to bring
5. **Preview**: Review before publish

**Save as draft** available

---

### 3.4 Edit Program
**Endpoint**: `PUT /api/programs/{id}`

Same as Create, pre-filled. Additional: Delete, pause

---

### 3.5 My Programs
**Tabs**: Created by me, Joined, Saved
**Actions**: Edit, view participants, delete

---

### 3.6 Créneaux — ce qui s'est ajouté *(août 2026)*

**Créneau rapide** — `POST /api/quick-slots` crée le créneau *et* son programme, en un
appel. La réponse est le **même DTO que le fil** (`SlotFeedItemDto`), pour que le client ne
maintienne pas deux modèles d'un seul objet. Le programme porte `createdVia: "QUICK"` : sans
description ni cadrage, il n'est pas mal rempli, il est **volontairement nu** — l'interface
ne doit pas afficher un vide comme un oubli.

**Liste d'attente** — `GET /api/slots/{id}/waitlist`. Rejoindre un créneau complet place en
file ; une place libérée promeut le premier et notifie (`WAITLIST_PROMOTED`).

> Le conflit d'horaire est revérifié **à la promotion**, pas seulement à l'entrée dans la
> file : celui qui attendait a pu s'inscrire ailleurs entre-temps. Une promotion peut donc
> être refusée, et la file passe au suivant.

**Annulation** — `POST /api/slots/{id}/cancel` `{ reason? }`, réservé à l'organisateur.
Prévient inscrits **et** file d'attente, par notification **et** e-mail. Le motif est
facultatif, mais un fait brut sans explication laisse chacun imaginer le pire.

L'e-mail part **en plus** du push, jamais à sa place : les canaux sont indépendants, et le
serveur ne sait pas si un push est arrivé. À dire ainsi dans les réglages de notification.

**Lien de sécurité** — `POST /api/slots/{id}/safety-share` rend un lien pour un proche.
La page est lisible **sans compte** : en exiger un viderait la fonctionnalité de son sens.

**Partage public** — `GET /api/slots/{id}/share-link` crée l'adresse à la première demande ;
`PATCH /api/slots/{id}/shareable` `{ isPubliclyShareable }` l'ouvre ou la referme, et
**seul l'organisateur** peut la refermer — cela retire à tous les autres un lien qu'ils ont
peut-être déjà collé quelque part.

> Le jeton n'est **jamais** effacé ni régénéré : rouvrir rend valides les liens déjà
> partagés. L'interface peut donc présenter la bascule comme une pause, pas comme une
> rupture.

**Invitations** — `POST /api/slots/{id}/invitations`, puis
`POST /api/invitations/{code}/accept`. Deux dates distinctes sont suivies : avoir rejoint le
créneau, et avoir créé un compte. L'un peut arriver sans l'autre.

**Agenda** — `GET /api/slots/{id}/calendar.ics`, `GET /api/slots/mine/calendar.ics`, et
`GET /s/{jeton}/calendar.ics` pour la page publique. Alarme de rappel à **−2 h**. Un créneau
rejoint puis oublié est une rencontre qui n'a pas lieu : c'est l'un des rares ajouts qui agit
directement sur la présence réelle.

**Accessibilité** — les créneaux portent des étiquettes (`accessibilityTags`), filtrables sur
le fil. Le filtre est **restrictif** : un créneau qui ne déclare rien est écarté dès qu'on
filtre, parce que rien ne permet d'affirmer son accueil. C'est l'inverse du filtre de langue,
et l'interface doit le dire — « seulement les créneaux qui l'annoncent », pas « accessibles ».

**Signal de fiabilité** — `reliabilitySignal` vaut `"USUALLY_SHOWS_UP"` ou **`null`**, jamais
un libellé négatif ni un pourcentage. Un signal absent n'est **pas** un mauvais signal : il
signifie « pas assez de données », et l'interface ne doit rien afficher plutôt qu'afficher
une réserve.

---

### 3.7 Page publique de programme *(nouveau)*

**Routes** : `GET /api/programs/{id}/share-link`, `PATCH /api/programs/{id}/shareable`

Le partage public existait pour les créneaux et pour eux seuls : un programme partagé
arrivait chez son destinataire en `meetdo://programs/42`, qu'aucune messagerie ne rend
cliquable. Le contrat est le même que celui des créneaux, décliné.

`PublicShareLinkDto` porte `token`, `shortUrl` (`https://lien.meetdo.fun/p/{jeton}`),
`pageUrl` et `shareable`. **`pageUrl` est à lire tel quel**, sans le recomposer.

Deux différences avec les créneaux :

- **Le lien est réservé à l'organisateur**, là où celui d'un créneau s'ouvre à tous ses
  participants. `404` pour quiconque d'autre.
- **Il n'y a pas de péremption dans le temps.** Un programme sans séance à venir garde sa
  page, qui dit « aucune séance annoncée » : son auteur peut en reprogrammer une. Ce qui
  éteint la page, c'est l'archivage, la dépublication, ou `shareable = false`.

> ⚠️ **Un point à vérifier de votre côté.** Le bouton de la page vise
> `meetdo://programs/{jeton}`, alors que `deep_links.dart` traite aujourd'hui
> `meetdo://programs/42` — un **identifiant**, pas un jeton. Le bouton n'ouvrira
> correctement l'application que si cet hôte accepte aussi un jeton. C'est le seul point de
> la livraison qui dépend de vous.

Les motifs `/p/*` et `/public/programs/*` ont été ajoutés à
`apple-app-site-association` **dans le même commit** : iOS ignore en silence ce que ce
fichier ne déclare pas, et livrer la route sans le motif aurait donné une page qui s'affiche
et un lien qui n'ouvre jamais l'application.

---

### 3.8 Page publique de créneau *(nouveau)*

L'adresse partagée est `https://lien.meetdo.fun/s/{jeton}` — **`lien.meetdo.fun`**, pas
`meetdo.fun` : le second est le site vitrine, le premier ce backend.

La page est rendue par le serveur, avec ses métadonnées OpenGraph : c'est ce qui fabrique
l'aperçu dans une messagerie, et un robot d'aperçu n'exécute pas de JavaScript. Elle est
servie en **FR / EN / DE**, et sa langue est celle de la **séance** avant celle de la
requête.

Son bouton vise `meetdo://slot/{jeton}` — le schéma d'URI de l'application, qui fonctionne
sans attendre les liens universels. Ceux-ci demandent, en plus des fichiers `/.well-known`
servis par le backend, l'entitlement `associated-domains` côté iOS et l'intent-filter App
Links côté Android : **deux moitiés qui échouent silencieusement l'une sans l'autre**.

---

## 4. DISCOVERY

### 4.1 Search
**Endpoint**: `POST /api/search`

**Natural language input**: "morning yoga near me"
**Location aware**: Use current location + radius
**Response types**:
- `results`: Programs/users
- `clarification`: Needs more details
- `empty`: No results, suggestions

---

### 4.2 Map View
**Endpoint**: `GET /api/map/users`

**Full-screen map**:
- User markers (color-coded by activity)
- Clustering
- Current location pin
- Filter pills (floating)
- Bottom sheet: User card on marker tap

**Privacy**: Approximate locations only

> **`GET /api/map/activities` exige désormais un jeton** *(depuis le 2026-08-19)*. Elle était
> ouverte sans authentification ; aucun écran hors session ne l'appelait, et sans appelant
> identifié elle rendait les organisateurs bloqués comme les autres. Un `401` sur cet appel
> signifie que le jeton manque, pas que la carte est vide.

---

### 4.3 L'Explorer — filtres serveur *(nouveau)*

**Routes** : `GET /api/activities/browse`, `GET /api/activities/browse/facets`

Les trois filtres appliqués jusqu'ici **sur les pages déjà chargées** sont passés en
paramètres de requête : `activityLevels`, `myActivitiesOnly`, `subscribedOnly`. Filtrer sur
les pages chargées donnait des listes qui rétrécissaient en défilant — vécu comme un défaut,
pas comme une limite.

| Paramètre | Sens |
|---|---|
| `activityLevels` | niveaux retenus ; vide ou absent : tous |
| `myActivitiesOnly` | ce qui se pratique **dans mes sports**, pas mes propres annonces |
| `subscribedOnly` | ce à quoi je suis abonné |

> **`myActivitiesOnly` mérite d'être confirmé.** La lecture retenue est « ce qui se pratique
> autour de moi dans les activités que j'ai déclarées » — l'Explorer étant une surface de
> découverte, un filtre ne rendant que mes trois entrées n'y découvrirait rien. Si le libellé
> « Mes activités » désigne chez vous *mes propres entrées*, dites-le : c'est une ligne de
> `WHERE` à changer, pas une refonte.

Sans appelant identifié, les deux filtres personnels **ne s'appliquent pas** : les appliquer
rendrait une liste vide, soit « rien autour de vous » au lieu de « connectez-vous ».

**Les compteurs** (`/facets`) annoncent ce qu'une case rendrait **si on la cochait** : ils
ignorent délibérément les filtres de même nature. Compter à l'intérieur du filtre courant
afficherait zéro à côté de chaque case non cochée et les ferait passer pour des impasses.

```json
{ "total": 42, "byLevel": { "BEGINNER": 12, "UNSPECIFIED": 8 },
  "myActivities": 4, "subscribed": 2 }
```

`UNSPECIFIED` regroupe les entrées sans niveau déclaré ; elles comptent dans le total, et les
ranger sous « ANY » inventerait une déclaration que personne n'a faite.

---

### 4.4 Recherche tolérante aux fautes *(nouveau)*

`POST /api/search` gagne une **quatrième couche**, en repli seulement : si la taxonomie, le
sémantique et le plein texte ne rendent rien, une similarité trigramme rattrape la faute de
frappe — « yoag », « escallade ».

Deux conséquences pour l'interface : une réponse vide est désormais **plus rarement** une
absence réelle, et il n'y a **pas** de « vouliez-vous dire… ? » à afficher — le serveur ne
propose pas une correction, il rend directement ce qui ressemble. Le repli ne rattrape que la
faute dans la langue où elle a été faite ; « Klettern » → « escalade » reste l'affaire de la
taxonomie.

---

## 5. SOCIAL FEATURES

### 5.1 Messages List
**Endpoint**: `GET /api/conversations`

**Chat list**:
- Avatar, name, last message, timestamp
- Unread indicator (dot + count)
- Search conversations
- Swipe: Delete, archive

---

### 5.2 Chat Thread
**Endpoints**:
- `GET /api/conversations/{id}/messages`
- `POST /api/conversations/{id}/messages`
- `POST /api/conversations/{id}/read`
- WebSocket: `/ws/chat`

**Standard messaging UI**:
- Real-time via WebSocket
- Read receipts
- Date dividers
- Image sharing
- Typing indicator

**Safety**: Report, block

---

### 5.2 bis Confort de messagerie *(nouveau)*

**Indicateur de saisie** — `STOMP /app/chat.typing`, corps
`{ "conversationId": "…", "typing": true }`. Ce qui revient aux autres membres arrive sur
`/user/queue/typing` : `{ conversationId, userId, typing }`.

> **Le serveur ne pose aucune échéance et n'émet aucun rappel.** C'est au client d'effacer
> l'indicateur après quelques secondes sans nouvelle : un émetteur qui perd sa connexion
> juste après avoir annoncé qu'il écrivait ne pourra jamais annoncer le contraire, et
> l'indicateur resterait allumé pour toujours.
>
> Rien n'est conservé, et l'auteur ne se reçoit jamais lui-même. Une trame envoyée sur un fil
> auquel on n'appartient pas produit un **silence** — le protocole n'a pas de réponse à une
> trame entrante, et un refus nommé apprendrait que la conversation existe.

**Partage de position ponctuel** — `POST /api/conversations/{id}/location`
`{ lat, lng, expiresInMinutes?, note? }` → un `MessageDto` ordinaire.

C'est **un message du fil**, et c'est toute la protection : renouveler suppose une nouvelle
bulle, donc suivre quelqu'un reste visible de celui qu'on suit. Il n'existe **pas** de suivi
continu, pas de dernière position connue, pas de mise à jour.

`expiresInMinutes` vaut 30 par défaut et **30 au maximum** ; au-delà la requête est
**refusée** en `400`, jamais rabotée en silence.

Un partage échu rend `locationLat`, `locationLng` et `locationExpiresAt` **nuls**, y compris
sur un message qui en portait un — le message reste dans le fil, sa position n'y est plus. Le
client n'a donc pas à comparer une échéance à l'heure courante pour savoir s'il doit
afficher le point ; il lui reste à le faire disparaître de lui-même à l'échéance, sur un fil
resté ouvert.

**Sourdine et archivage** — `PATCH /api/conversations/{id}/settings`
`{ muted?, archived? }`. Un champ absent reste **inchangé** : les deux commandes vivent sur
deux écrans différents.

- La sourdine coupe **l'émission, pas la réception** : le message arrive, s'affiche et compte
  dans le décompte du fil ; il ne sonne plus.
- L'archivage sort le fil de `GET /api/conversations`, qui accepte `?archived=true` pour la
  seconde liste. **Un message reçu ne désarchive pas** — ranger le fil dont on veut se
  débarrasser n'aurait sinon aucun effet, puisque c'est justement celui qui reçoit.

> **Un invariant a changé de forme.** Le total de `GET /api/conversations/unread-count`
> **exclut** désormais les fils en sourdine et archivés, alors que leur `unreadCount`
> individuel reste exact. Un client qui vérifiait « la somme des fils = le badge » doit
> désormais sommer les fils **ni `muted` ni `archived`** — les deux drapeaux sont sur
> `ConversationSummaryDto` précisément pour cela.

---

### 5.3 Create Conversation
**Endpoint**: `POST /api/conversations`

**Flow**: Search user → Select → Template message → Send

---

### 5.4 Notifications
**Endpoints**:
- `GET /api/notifications`
- `GET /api/notifications/unread-count`
- `PUT /api/notifications/{id}/read`
- `PUT /api/notifications/read-all`
- `DELETE /api/notifications/{id}`

**List view**:
- Types: Badge, message, program update, review, recommendation
- Icon, title, preview, timestamp
- Primary action button
- Swipe to delete

**Badge**: Unread count on bell icon

---

## 6. PROGRESSION & GAMIFICATION

### 6.1 Progression Tracking
**Endpoints**:
- `GET /api/progressions/my`
- `POST /api/progressions`
- `GET /api/progressions/my/streak`
- `GET /api/progressions/my/stats`

**View**:
- Streak counter
- Session stats
- Activity log (edit, delete)
- Log activity button
- Heatmap calendar

---

### 6.2 Badges
**Endpoints**:
- `GET /api/badges`
- `GET /api/badges/me`
- `POST /api/badges/me/evaluate`

**Grid view**: All/Earned/Locked tabs

**Badge types**: First session, streaks, social, variety, program creator, top rated

**Earn animation**: Full-screen confetti reveal

---

## 7. REVIEWS & RECOMMENDATIONS

### 7.1 Write Review
**Endpoints**:
- `GET /api/reviews/can-review/{programId}`
- `POST /api/reviews`

**5 Criteria** (1-5 stars each):
1. Organization
2. Communication
3. Friendliness
4. Skill level match
5. Would recommend

**Auto-calculated**: Overall average
**Optional**: Text review (250 chars)

---

### 7.2 Reviews List
**Endpoints**:
- `GET /api/reviews/programs/{programId}`
- `GET /api/reviews/programs/{programId}/summary`
- `GET /api/reviews/me`

**Summary header**: Overall rating + per-criterion breakdown
**List**: Sort by recent/rating

---

### 7.3 Give Recommendation
**Endpoints**:
- `GET /api/recommendations/can-recommend/{userId}`
- `POST /api/recommendations`

**Form**: Select activity, rating (1-5), text (500 chars)

---

### 7.4 Recommendations Display
**Endpoints**:
- `GET /api/recommendations/received`
- `GET /api/recommendations/given`
- `GET /api/recommendations/users/{userId}`
- `GET /api/recommendations/stats/{userId}`

**Cards**: Avatar, activity, rating, text, date

---

## 8. MODERATION & REPORTS

### 8.1 Report Content
**Endpoint**: `POST /api/reports`

**Modal**: Select reason (inappropriate, spam, false info, safety, other), details, submit

**Report types**: User, program, message, review, recommendation

---

### 8.2 My Reports
**Endpoint**: `GET /api/reports/me`

**List**: Status (pending, under review, resolved, dismissed)

---

### 8.3 Moderator Dashboard (Admin)
**Endpoint**: `GET /api/reports/pending`

**Queue**: Review reports, take action (dismiss, warn, suspend, ban)

---

## 9. GDPR & PRIVACY

### 9.1 Data Export
**Endpoint**: `GET /api/gdpr/export`

**Flow**: Request → Processing → Download link (email + UI), expires 7 days

---

### 9.2 Delete Account
**Endpoint**: `DELETE /api/gdpr/delete-account`

**Flow**: Warning → Type "DELETE" → Re-auth → Confirm → Deleted → Logout

**Anonymization**: Personal data removed, reviews/programs anonymized

---

## 10. RESPONSIVE DESIGN

### Breakpoints
- Mobile: 0-767px (default)
- Tablet: 768-1023px
- Desktop: 1024-1439px
- Wide: 1440px+

### Adaptive Layouts
**Mobile**: Single column, bottom nav, full-screen modals
**Tablet**: Two columns, side sheets, persistent top nav
**Desktop**: Side nav, multi-column grids, map + list side-by-side, hover states

### Touch Gestures
- Swipe: Back, delete
- Pull to refresh
- Long press: Context menu
- Pinch: Map zoom
- Drag: Reorder

---

## 11. NAVIGATION

### Primary (Bottom on Mobile, Side on Desktop)
1. **Browse**: Programs feed, filters, search
2. **Map**: User map, activity filters
3. **Messages**: Conversations, chat
4. **Profile**: My profile, programs, progress, badges, settings

### Secondary (Top Bar)
- Logo (home)
- Notifications (bell + badge)
- Settings (cog)
- Search (global)

---

## 12. COMPONENT LIBRARY

### Core Components
- **Buttons**: Primary, secondary, danger, ghost (sizes: sm/md/lg)
- **Cards**: Program, user, badge, review, notification
- **Forms**: Input, textarea, select, multi-select, radio, checkbox, toggle, slider, date/time picker, location search
- **Navigation**: Tab bar, side nav, top bar, breadcrumbs
- **Feedback**: Toast, loading, progress, skeleton, empty state, error state
- **Media**: Avatar (sizes), image upload, carousel, icons
- **Data Display**: **Schedule rhythm bar** (signature), stats, rating stars, badges, activity tags, heatmap
- **Overlays**: Modal, bottom sheet, drawer, tooltip, popover, confirmation

---

## 13. ANIMATIONS

### Page Transitions
- Slide (navigation), fade (modals), scale + fade (success)

### Component Animations
- **Schedule bars**: Stagger in (50ms delay per day) ⭐
- **Badge earn**: Scale + rotate + confetti
- **Card hover**: Lift (translateY -2px)
- **Button press**: Scale 0.98
- **Star rating**: Fill on tap
- **Notifications**: Slide in from top

### Micro-interactions
- Heart fill, checkmark draw, shake (error), ripple, pull to refresh stretch

---

## 14. ACCESSIBILITY

- WCAG 2.1 Level AA
- ARIA labels
- Semantic HTML
- Keyboard navigation
- Screen reader optimized
- Focus indicators
- Skip navigation
- Alt text
- Color contrast 4.5:1 (body), 3:1 (large text)
- Respect prefers-reduced-motion
- Relative font units

---

## 15. PERFORMANCE

### Targets
- First Contentful Paint: < 1.5s
- Time to Interactive: < 3s
- Lighthouse: > 90

### Optimization
- Image lazy loading
- Code splitting
- API caching (React Query)
- Infinite scroll
- WebSocket for real-time
- Service worker
- Optimistic UI

---

## 16. ERROR HANDLING

### Network
- Retry (3 attempts)
- Offline indicator
- Try again button
- Cached fallback

### Validation
- Inline errors
- Summary at top
- Scroll to first error

### Permission
- 401: Redirect to login
- 403: Permission denied
- 404: Not found page
- 500: Support contact

---

## 17. TECH STACK

### Recommended
- **Framework**: React + TypeScript, Next.js (SSR), React Native (mobile)
- **State**: React Query (server), Zustand/Jotai (client), WebSocket (real-time)
- **UI**: Tailwind CSS, Radix UI/Headless UI, Framer Motion
- **Maps**: Mapbox / Google Maps / Leaflet
- **Forms**: React Hook Form + Zod
- **Testing**: Vitest, Testing Library, Playwright

---

## 18. LAUNCH CHECKLIST

### MVP (Priority 1)
✅ Authentication (register, login, verify)
✅ Profile (edit, view)
✅ Activities
✅ Programs (CRUD)
✅ Browse + filters
✅ Map view
✅ Search (semantic)
✅ Messaging
✅ Notifications (in-app)
✅ Progression
✅ Badges
✅ Reviews

### Post-Launch (Priority 2)
- Recommendations
- Email/push notifications
- GDPR export/delete
- Reports + moderation
- Saved programs
- Group chat

### Future (Priority 3)
- Social login
- Payments
- Video chat
- Calendar sync
- Public API
- Referral system
- Challenges
- Fitness tracker integration

---

## APPENDIX: VIEW-TO-ENDPOINT MAPPING

| View | Endpoints |
|------|-----------|
| Register | `POST /api/auth/register` |
| Login | `POST /api/auth/login` |
| Verify Email | `GET /api/auth/verify-email` |
| Forgot Password | `POST /api/auth/forgot-password` |
| Reset Password | `POST /api/auth/reset-password` |
| My Profile | `GET /api/users/me` |
| Edit Profile | `PUT /api/users/me`, `PUT /api/users/me/location`, `POST /api/media/upload/avatar` |
| Public Profile | `GET /api/users/{id}` |
| Settings | Various notification/device endpoints |
| Activities | `GET /api/categories`, `GET /api/activities`, `/api/users/me/activities` CRUD |
| Browse Programs | `GET /api/programs` |
| Program Detail | `GET /api/programs/{id}` |
| Create/Edit Program | `POST/PUT /api/programs`, program schedules endpoints |
| Search | `POST /api/search` |
| Map | `GET /api/map/users` |
| Messages | Conversations + messages endpoints, WebSocket `/ws/chat` |
| Notifications | `/api/notifications/*` endpoints |
| Progression | `/api/progressions/*` endpoints |
| Badges | `/api/badges/*` endpoints |
| Reviews | `/api/reviews/*` endpoints |
| Recommendations | `/api/recommendations/*` endpoints |
| Reports | `/api/reports/*` endpoints |
| GDPR | `GET /api/gdpr/export`, `DELETE /api/gdpr/delete-account` |
| **Onboarding** | `GET`/`PATCH /api/users/me/onboarding`, `POST /api/users/me/onboarding/skip`, `GET /api/activities/suggested` |
| **Aperçu de mon profil** | `GET /api/users/me/preview` |
| **Blocage** | `POST`/`DELETE /api/users/{userId}/block`, `GET /api/users/me/blocked` |
| **Langues / disponibilités** | `GET`/`PUT /api/users/me/languages`, `GET`/`PUT /api/users/me/availability` |
| **Heures de silence** | `GET`/`PUT /api/notifications/quiet-hours` |
| **Explorer** | `GET /api/activities/browse`, `GET /api/activities/browse/facets` |
| **Créneau rapide** | `POST /api/quick-slots` |
| **Liste d'attente** | `GET /api/slots/{id}/waitlist` |
| **Annulation** | `POST /api/slots/{id}/cancel` |
| **Partage** | `GET /api/slots/{id}/share-link`, `PATCH /api/slots/{id}/shareable`, `POST /api/slots/{id}/safety-share` |
| **Invitations** | `POST /api/slots/{id}/invitations`, `POST /api/invitations/{code}/accept` |
| **Agenda** | `GET /api/slots/{id}/calendar.ics`, `GET /api/slots/mine/calendar.ics` |
| **Messagerie (confort)** | `PATCH /api/conversations/{id}/settings`, `POST /api/conversations/{id}/location`, STOMP `/app/chat.typing` |
| **Page publique** | `GET /s/{jeton}`, `GET /p/{jeton}` *(web, sans compte)* |
| **Partage de programme** | `GET /api/programs/{id}/share-link`, `PATCH /api/programs/{id}/shareable` |

---

**Signature Design Element**: Schedule rhythm bars make time commitments visible at a glance—the one distinctive element that makes Pair memorable while keeping everything else disciplined and familiar.

---

## Ce qui reste à vérifier ou à trancher

Rassemblé dans un seul document, plutôt que dispersé en notes :
[`specs/VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md`](specs/VERIFICATIONS_CLIENT_MOBILE_2026-08-20.md).

**FIN DE LA SPÉCIFICATION**

---
