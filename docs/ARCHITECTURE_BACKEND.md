# Backend MeetDo / Pair — structure, fonctionnalités et base de données

> État du dépôt au **20 août 2026**, branche `master` (dernier commit `identifiant-public`).
> Ce document décrit ce que le code fait *aujourd'hui*, pas ce qui est prévu.
> **77 migrations Flyway** (dernière : `V78__public_program_sharing.sql`).
>
> Relevé précédent : 18 août 2026, arrêté à `V59`. Les phases A à D du TODO v2 et la
> spécification des liens publics ont été livrées entre les deux — six tables, une
> vingtaine de routes, et plusieurs règles de visibilité que ce document doit porter parce
> qu'elles ne se lisent nulle part ailleurs.

---

## 1. Vue d'ensemble

| | |
|---|---|
| Nom Maven | `org.program:Pair` (`0.0.1-SNAPSHOT`) |
| Framework | Spring Boot **4.1.0** |
| Langage | Java (`java.version` = 17 dans le POM ; image de build et d'exécution en **Temurin 21**) |
| Base | PostgreSQL 16 + **PostGIS 3.6** + **pgvector** + `uuid-ossp` + **pg_trgm** |
| Migrations | Flyway (`db/migration`, V1 → V78) |
| Temps réel | WebSocket / STOMP (SockJS en repli) |
| Sécurité | Spring Security stateless + JWT (JJWT 0.12.3), BCrypt (force 12) |
| Recherche | Plein texte PostgreSQL (`tsvector`) + embeddings locaux **DJL / ONNX Runtime** (modèle trilingue FR/EN/DE), plus une taxonomie déterministe |
| Push | Firebase Admin SDK (activable ; implémentation `NoOp` sinon) |
| E-mail | SMTP (`spring-boot-starter-mail`) en local, **Resend** via API en production |
| Docs API | springdoc-openapi (`/swagger-ui.html`, `/v3/api-docs`) |
| Volume | ~35 600 lignes de Java applicatif, ~22 200 lignes de tests, **225 endpoints HTTP** + 2 destinations STOMP, **105 classes de test** (763 tests), **39 tables** |
| Pages web | Thymeleaf — pages publiques de créneau et de programme, lien de sécurité, et les deux fichiers d'association d'applications |

Le backend sert une application mobile/web de mise en relation autour d'activités
pratiquées **au même endroit et au même moment** : un utilisateur déclare ses activités,
publie des *programmes* dotés de *créneaux* géolocalisés, d'autres les rejoignent, se
parlent, confirment leur présence, laissent des souvenirs partagés (*recaps*) et gagnent
des badges de confiance.

---

## 2. Architecture du code

### 2.1 Découpage

Le code est organisé en **tranches verticales par domaine métier**, et non en couches
globales. Chaque paquet sous `org.program.pair.domain.<domaine>` contient ses entités JPA,
ses énumérations, son ou ses services, son ou ses contrôleurs, et un sous-paquet `dto`
(souvent des `record`).

```
src/main/java/org/program/pair/
├── PairApplication.java          point d'entrée
├── config/                       10 classes de configuration Spring
├── controller/                   HomeController, AdminSeedController
├── api/                          GdprController
├── repository/                   30 interfaces Spring Data (dépôt central, transverse)
├── seed/                         amorçage des données de référence et de démo
├── shared/                       socle transverse (sécurité, erreurs, i18n, e-mail, géo…)
└── domain/
    ├── activity/     alert/      attendance/   audit/     auth/
    ├── badge/        chat/       email/        gdpr/      indexation/
    ├── map/          media/      notification/ program/   progression/
    ├── recap/        recommendation/ report/   review/    search/
    ├── subscription/ trust/      user/         websocket/
```

Particularité : les **repositories sont regroupés** dans `org.program.pair.repository`
plutôt que dans chaque domaine — c'est le seul écart notable au découpage vertical.

### 2.2 Le socle transverse (`shared/`)

| Paquet | Contenu |
|---|---|
| `shared/security` | `JwtAuthFilter` (extraction et validation du Bearer), `UserPrincipal`, `UserDetailsServiceImpl`, `RateLimiter` + `RateLimiterService` (Bucket4j) |
| `shared/exception` | `BusinessException` et sa famille (`ValidationException`, `ForbiddenException`, `ConflictException`, `ResourceNotFoundException`, `TooManyRequestsException`, `ScheduleConflictException`…), l'énumération `ErrorCode` (**50 codes**), et `GlobalExceptionHandler` qui les traduit en `ErrorResponse` JSON |
| `shared/i18n` | `Messages`, adossé aux fichiers `messages*.properties` |
| `shared/sanitizer` | `HtmlSanitizer` (OWASP Java HTML Sanitizer) appliqué aux contenus libres |
| `shared/logging` | `RequestIdFilter` : pose `X-Request-Id` dans le MDC, chaque ligne de log porte `[rid:…]`, l'en-tête est renvoyé au client |
| `shared/email` | `EmailService` |
| `shared/dto` | `ErrorResponse`, `ScheduleConflictResponse` |
| `shared/GeoUtils` | calculs géographiques (distances, bounding boxes) |

### 2.3 Configuration Spring (`config/`)

- **`SecurityConfig`** — chaîne stateless, CSRF désactivé, `JwtAuthFilter` avant
  `UsernamePasswordAuthenticationFilter`, `@EnableMethodSecurity`, `AuthenticationEntryPoint`
  personnalisé qui renvoie **401** (et non le 403 par défaut de Spring) en cas de token
  absent ou expiré. CORS explicite (origines Vercel + localhost, en-tête `X-Request-Id`
  autorisé **et** exposé).
- **`WebSocketConfig`** — broker simple sur `/topic` et `/queue`, préfixe applicatif
  `/app`, destinations utilisateur `/user`. Endpoint `/ws/chat` déclaré deux fois : avec
  et sans SockJS. Un `ChannelInterceptor` authentifie le STOMP `CONNECT` via le header
  `Authorization: Bearer …` et attache l'identifiant utilisateur à la session.
- **`JpaConfig`** — auditing JPA.
- **`AsyncConfig`** — pool `indexationExecutor` (2 → 5 threads) pour l'indexation asynchrone.
- **`LocaleConfig`** — trois locales supportées (**fr** par défaut, **en**, **de**),
  résolution par `Accept-Language` avec repli sur la plus proche supportée.
- **`FirebaseConfig`** — initialisation conditionnelle (`firebase.enabled`), credentials
  par fichier **ou** par chaîne base64.
- **`StorageConfig`**, **`WebClientConfig`**, **`OpenApiConfig`**, **`HttpConnectorConfig`**
  (connecteur HTTP additionnel sur `server.http.port`, en plus du port HTTPS principal).

### 2.4 Communication interne

Trois mécanismes coexistent :

1. **Appel direct de service** — le cas courant.
2. **Événements Spring** — `MessageSentEvent` → `ChatPushListener` (push d'un message),
   `UnreadChangedEvent` → synchronisation du badge d'icône, `BadgeSyncListener`,
   `ProgramIndexationListener` / `ActivityIndexationListener` (réindexation après écriture).
3. **`@Async`** — l'indexation (`IndexationService`) et l'envoi de push sortent du chemin
   de la requête.

---

## 3. Sécurité et confidentialité

### 3.1 Authentification

JWT en deux jetons : **access** (15 min) et **refresh** (30 jours), signés HS256 avec
`jwt.secret`. `AuthController` expose `register`, `login`, `refresh`, `verify-email`,
`forgot-password`, `reset-password`, `logout`.

### 3.2 Routes publiques

Tout est authentifié **sauf** : `/`, les six routes d'authentification listées ci-dessus,
`/ws/**`, Swagger, `/actuator/health`, et en lecture seule
`GET /api/categories`, `GET /api/activities`, `GET /api/badges`,
`GET /api/badges/users/**`, `GET /api/recommendations/users/**`,
`GET /api/recommendations/stats/**`, `GET /api/reviews/programs/**`.

**Les pages web ouvertes**, ajoutées avec le partage : `GET /public/safety/**`,
`GET /public/slots/**`, `GET /s/**`, `GET /public/programs/**`, `GET /p/**` et
`GET /.well-known/**` — plus **`HEAD` sur les mêmes**, voir ci-dessous. Elles n'ont pas d'appelant
identifié et n'en veulent pas : le destinataire d'un lien de sécurité est un proche qui n'a
pas de compte meetDo, et lui en demander un viderait la fonctionnalité de son sens. Toute
la confidentialité repose sur le **jeton**, opaque et périssable. Les fichiers
`/.well-known` sont ouverts sans condition parce qu'Apple et Google les lisent sans
identité, et qu'une simple redirection suffirait à faire échouer la validation.

**`HEAD` a été ajouté le 20 août 2026**, et l'omission valait pour toutes ces routes. Les
règles ne nommaient que `GET`, donc `HEAD` retombait sur `anyRequest().authenticated()` et
rendait `401` là où `GET` rend `200`. Rien n'était cassé — Apple et les robots d'aperçu font
des `GET` — mais tout diagnostic mené en `curl -I` concluait que la page était protégée. Le
défaut a été signalé par l'équipe mobile, dont le document en portait lui-même la
conséquence : il déduisait d'un `401` qu'une route était absente, alors qu'elle aurait rendu
`401` même en existant.

**`GET /api/map/activities` a quitté cette liste le 20 août 2026.** Elle était ouverte,
héritée d'un temps où la carte servait de vitrine ; l'équipe mobile a vérifié route par
route qu'aucun écran hors session ne l'appelait. Sans appelant identifié, elle rendait les
organisateurs bloqués comme les autres — un profil bloqué qui garde ses activités sur la
carte est un profil qui n'est pas bloqué. `GET /api/users/{id}/programs` était déjà
authentifiée mais ne consultait pas le blocage, alors que la fiche de profil servie au même
écran refusait déjà.

### 3.3 Limitation de débit (Bucket4j, en mémoire)

| Action | Quota |
|---|---|
| Connexion | 10 / 15 min par IP |
| Inscription | 5 / heure par IP |
| Recherche | 30 / min par utilisateur |
| Upload média | 20 / heure par utilisateur |

Dépassement → `TooManyRequestsException` → HTTP 429 avec le code `RATE_LIMITED`.

### 3.4 Confidentialité par conception

- **Position utilisateur floutée** : `users.blur_radius_m`, plus les drapeaux
  `location_public`, `show_on_map`, `online_status_visible`, `show_age`, `show_last_active`.
- **Visibilité du profil** : `profile_visibility` (`PUBLIC` / `FRIENDS` / `PRIVATE`),
  `allow_messages` (`EVERYONE` / `FRIENDS` / `NONE`), `allow_subscriptions`
  (`OPEN` / `NOBODY`).
- **Adresse d'un créneau** : `SlotAddressVisibility` ne révèle les coordonnées exactes que
  si le lieu est public, ou si l'organisateur l'a explicitement autorisé, ou si le
  demandeur est un participant **confirmé**. Un créneau en ligne ne révèle jamais rien.
- **Recap** : consentement d'identité par participant (`recap_participant_consents`),
  visibilité `PRIVATE` / `PARTICIPANTS` / `PUBLIC`.
- **Statistiques de pratique** : `PracticeStatsService` porte une interdiction explicite
  en commentaire — aucune route de classement ne doit s'en servir pour trier les
  utilisateurs entre eux.
- **Blocage** (`domain/block`) : la ligne est dirigée, la lecture est **bilatérale**. Le
  refus est un `404`, jamais un `403` — un code nommé apprendrait le blocage à celui qui
  l'a subi. Le prédicat vit dans chaque requête et dans son `COUNT` : post-filtrer
  casserait la pagination et ferait annoncer « Programmes (12) » puis en servir 9.
- **Signal de fiabilité** (`domain/attendance/ReliabilitySignal`) : rend
  `USUALLY_SHOWS_UP` ou **rien**, jamais un libellé négatif ni un pourcentage. Exposer les
  deux compteurs bruts laisserait n'importe quel client afficher un taux, ce que le produit
  a promis de ne pas faire.
- **Partage de position** : ponctuel, **30 minutes au maximum**, et c'est un message
  ordinaire du fil — renouveler suppose une nouvelle bulle, donc suivre quelqu'un reste
  visible de celui qu'on suit. Une durée supérieure est refusée, jamais rabotée en silence.
- **Pages publiques** : types fermés `PublicSlotView` et `PublicProgramView`. Ils portent
  l'identifiant de la **ressource partagée** — c'est l'objet même du lien, et sans lui le
  client résout le jeton en une description qu'il ne peut afficher nulle part — mais jamais
  ceux des **tiers**, qui donneraient prise sur des personnes que l'organisateur n'a pas
  partagées. L'identifiant n'apparaît **jamais dans une adresse** : c'est le jeton opaque
  qui adresse, une URL bâtie sur la clé primaire se laissant énumérer. Les conditions de
  refus sont réunies dans `publiclyVisible`, toutes en `404`.

### 3.5 Erreurs

`GlobalExceptionHandler` normalise toute erreur en `{code, message, timestamp}`. Les 50
valeurs de `ErrorCode` couvrent le générique (`VALIDATION_ERROR`, `NOT_FOUND`,
`FORBIDDEN`, `CONFLICT`, `RATE_LIMITED`…) et le métier fin :
`SLOT_FULL`, `SLOT_ALREADY_JOINED`, `SCHEDULE_CONFLICT`, `PROGRAM_MESSAGES_DISABLED`,
`PROGRAM_BROADCAST_READ_ONLY`, `RECAP_WINDOW_CLOSED`, `RECAP_NOT_ATTENDEE`,
`ALREADY_SUBSCRIBED`, `MAP_BOUNDS_INVALID`, etc.

---

## 4. Fonctionnalités par domaine

### 4.1 Utilisateurs (`domain/user`)

Profil, avatar, position, mot de passe, confidentialité, statistiques de pratique.

```
GET    /api/users                       recherche/liste
GET    /api/users/me
PUT    /api/users/me
DELETE /api/users/me
GET    /api/users/{id}
GET    /api/users/me/practice-stats     ·  GET /api/users/{userId}/practice-stats
PUT    /api/users/me/location
POST   /api/users/me/avatar             ·  DELETE /api/users/me/avatar
POST   /api/users/me/change-password
GET    /api/users/{userId}/programs     ← 404 entre deux comptes bloqués
GET    /api/users/me/privacy            ·  PUT /api/users/me/privacy
GET    /api/users/me/preview            ← ce qu'un inconnu voit de moi
GET    /api/users/me/onboarding         ·  PATCH /api/users/me/onboarding
POST   /api/users/me/onboarding/skip
GET    /api/users/me/languages          ·  PUT /api/users/me/languages
GET    /api/users/me/availability       ·  PUT /api/users/me/availability
POST   /api/users/{userId}/block        ·  DELETE /api/users/{userId}/block
GET    /api/users/me/blocked
```

Compteurs dénormalisés recalculés après chaque confirmation de présence :
`distinct_partners_count`, `attendance_count`, `current_streak_weeks`,
`last_attendance_at`, `joined_slots_count`.

**Les réglages de confidentialité sont appliqués** *(depuis le 2026-08-19)*. Ils étaient
stockés, réglables, relus — et lus par aucun code de rendu : un profil « privé » était servi
intégralement. `toPublicDto` masque désormais bio, badges, nombre d'abonnés, signal de
fiabilité et présence en ligne ; restent toujours visibles le nom, l'avatar et le badge de
vérification, par lesquels une personne est reconnue dans une conversation ou une liste de
participants. Faute de notion d'amitié dans le produit, `FRIENDS` s'appuie sur
l'**abonnement**, déjà calculé dans ce DTO.

`GET /api/users/me/preview` appelle **exactement le même code** avec la relation d'un
inconnu : c'est ce qui garantit que l'aperçu dit vrai, et un test compare les deux sorties.

**Le parcours d'accueil décrit les quatre écrans réels** — `ACTIVITIES`, `LEVELS`,
`LOCATION`, `PREVIEW`. La première version décrivait la spécification et non l'application :
deux valeurs seulement existaient des deux côtés, **dans l'ordre inverse**, si bien que
l'étape « position » était acceptée puis ignorée en `200` — un échec silencieux des deux
côtés. L'ancien vocabulaire reste accepté en entrée et traduit, le temps qu'une version
publiée du client cesse de le parler.

### 4.2 Activités et catégories (`domain/activity`)

Catalogue de référence hiérarchique (`activities.parent_id`), catégories avec rampe de
couleur, et le lien `user_activities` qui dit *qui pratique quoi*, avec niveau
(`BEGINNER`…`EXPERT`, `ANY`) et format (`SOLO`, `DUO`, `GROUP`, `ANY`).

`ActivityBrowseService` (« l'Explorer ») remplace une jointure autrefois faite côté client
qui indexait les programmes par *nom d'activité normalisé* — deux « Yoga » de deux
organisateurs fusionnaient, « Yôga » et « Yoga » se séparaient. La clé est désormais la
vraie clé étrangère.

**Les filtres sont passés côté serveur** *(nouveau)*. Ils s'appliquaient jusque-là sur les
pages déjà chargées, ce que les utilisateurs vivaient comme un défaut et non comme une
limite. `myActivitiesOnly` désigne **ce qui se pratique autour de moi dans mes sports**, et
non mes propres annonces : l'Explorer est une surface de découverte, et un filtre qui ne
rendrait que mes trois entrées n'y découvrirait rien. Sans appelant identifié, les deux
filtres personnels ne s'appliquent pas — les appliquer rendrait une liste vide, soit
« rien autour de vous » au lieu de « connectez-vous ».

**Les compteurs ignorent les filtres de même nature.** Leur périmètre est la zone, la
catégorie et l'expiration ; ni le niveau, ni les deux filtres personnels. C'est ce qui fait
qu'un compteur annonce ce qu'on obtiendrait **en cochant** la case : compter à l'intérieur du
filtre courant afficherait zéro à côté de chaque case non cochée, et les ferait toutes
passer pour des impasses. Une seule requête les rend tous, groupée par niveau avec deux
`FILTER` — une par facette aurait fait sept balayages géographiques par ouverture du panneau.

La route est **séparée** plutôt qu'une enveloppe autour de la page : le
`Page<BrowsedActivityDto>` est déjà consommé par une version publiée du client.

```
GET    /api/activities/browse           ← + activityLevels, myActivitiesOnly, subscribedOnly
GET    /api/activities/browse/facets    ← compteurs du panneau de filtres
GET    /api/activities/suggested        ← premières suggestions, jamais vides
GET    /api/categories                  ·  POST /api/categories
GET    /api/activities                  ·  POST /api/activities
GET    /api/users/me/activities         ·  GET /api/users/{id}/activities
POST   /api/users/me/activities
PUT    /api/users/me/activities/{id}    ·  DELETE /api/users/me/activities/{id}
PATCH  /api/users/me/activities/{id}/visibility
PATCH  /api/activities/{id}/icon        ·  POST /api/activities/{id}/icon/upload
DELETE /api/activities/{id}/icon
```

### 4.3 Programmes et créneaux (`domain/program`)

Un **programme** appartient à une `user_activity` (donc à un utilisateur *et* une
activité). Il porte titre, description, objectifs, prérequis, durée en semaines, nombre de
séances par semaine, jours préférés (`integer[]`), moment préféré, capacité, modalité
(`REMOTE`/`ONLINE`/`IN_PERSON`/`HYBRID`), confidentialité, image, et le drapeau
`allow_participant_messages`.

Un **créneau** (`schedules`) est une occurrence géolocalisée : lieu (`PUBLIC`/`PRIVATE`/
`ONLINE`), point PostGIS, ville, adresse publique, début/fin, **règle de récurrence
RFC 5545**, capacité, statut (`OPEN`/`FULL`/`CANCELLED`/`PAST`), compteur de participants,
mot d'accueil.

Trois mécaniques valent d'être signalées :

- **`RecurrenceExpander`** (ical4j) interprète réellement `recurrence_rule` : sans lui, un
  créneau hebdomadaire gardait le `startsAt` de sa première séance et se déclarait
  terminé pour toujours.
- **`RecurringSlotRolloverJob`** (toutes les 10 min) fait avancer les créneaux récurrents
  passés vers leur prochaine occurrence — sans quoi le fil `/api/slots/feed` finissait par
  se vider entièrement.
- **`ScheduleConflictDetector`** refuse côté serveur qu'un utilisateur rejoigne un créneau
  chevauchant un créneau déjà rejoint. La vérification côté client existe aussi, mais deux
  appareils inscrits en parallèle la contourneraient.

```
POST   /api/programs                    ·  GET /api/programs   ·  GET /api/programs/new
GET    /api/programs/{id}/share-link    ·  PATCH /api/programs/{id}/shareable
GET    /api/programs/{id}               ·  PUT/PATCH/DELETE /api/programs/{id}
POST   /api/programs/{id}/duplicate
POST   /api/programs/{id}/image/upload  ·  DELETE /api/programs/{id}/image
POST   /api/programs/{id}/schedules     ·  PUT/DELETE /api/programs/{id}/schedules/{sid}
POST   /api/programs/{id}/report

POST   /api/programs/{id}/join          ·  POST /api/programs/{id}/leave
GET    /api/programs/{id}/participants/count
GET    /api/programs/{id}/enrollment-status
GET    /api/users/me/programs           ·  PATCH/DELETE /api/users/me/programs/{upid}
POST   /api/users/me/programs/{upid}/activities/{aid}/complete
POST   /api/users/me/programs/{upid}/activities/{aid}/skip

GET    /api/slots/feed                  ·  GET /api/slots/mine
GET    /api/slots/{sid}                 ·  GET /api/slots/{sid}/participants
POST   /api/slots/{sid}/join            ·  DELETE /api/slots/{sid}/join
POST   /api/quick-slots                 ← créneau rapide, programme QUICK implicite
POST   /api/slots/{sid}/cancel          ·  GET /api/slots/{sid}/waitlist
POST   /api/slots/{sid}/safety-share    ·  GET /api/slots/{sid}/share-link
PATCH  /api/slots/{sid}/shareable       ← organisateur seul
POST   /api/slots/{sid}/invitations     ·  POST /api/invitations/{code}/accept
GET    /api/slots/{sid}/calendar.ics    ·  GET /api/slots/mine/calendar.ics
```

Quatre mécaniques ajoutées depuis :

- **Créneau rapide** — un programme `created_via = 'QUICK'` est créé implicitement. Sans ce
  drapeau, il s'affichait comme un programme mal rempli ; le client distingue désormais un
  vide assumé d'un oubli. La réponse est le **même DTO** que le fil, pour que le client ne
  maintienne pas deux modèles d'un seul objet.
- **Liste d'attente** — le rang n'existe que dans l'état `WAITLISTED`, garanti par un unique
  partiel. Une place libérée promeut le premier, et le conflit d'horaire est revérifié **à
  la promotion** : celui qui attendait a pu s'inscrire ailleurs entre-temps.
- **Annulation** — prévient les inscrits *et* la file d'attente, par notification **et** par
  e-mail. L'un des rares cas où le double canal se justifie : ne pas recevoir une annulation
  coûte un déplacement pour rien. Un créneau annulé perd aussi sa page publique.
- **Partage et invitations** — jeton base62 opaque de 22 caractères (`ShareToken`), jamais
  l'UUID interne, créé à la première demande et jamais régénéré ensuite.

**Les programmes se partagent aussi** *(V78)*, sur exactement le même contrat : mêmes jetons,
même `PublicShareLinkDto`, même refus en `404`, même page rendue par le serveur. Un programme
partagé arrivait sinon en `meetdo://programs/42`, qu'aucune messagerie ne rend cliquable.

Deux écarts assumés avec les créneaux. Les conditions de visibilité n'ont **pas** de borne de
temps — un programme n'est pas une occurrence, et sans séance à venir sa page dit « aucune
séance annoncée » plutôt que de disparaître. Et le lien est réservé à l'**organisateur**, là
où celui d'un créneau s'ouvre à ses participants : partager une séance qu'on a rejointe est un
geste ordinaire, décider qu'un programme existe sur le web ouvert ne l'est pas.

### 4.4 Présence et statistiques (`domain/attendance`)

Après un créneau, chaque participant confirme sa présence. La confirmation débloque la
recommandation entre pairs (preuve d'interaction `SHARED_ATTENDANCE`), alimente le recap
et met à jour les compteurs de pratique.

```
POST   /api/attendances/{scheduleId}/confirm
GET    /api/attendances/pending
GET    /api/attendances/{scheduleId}/co-participants
```

`AttendancePromptJob` relance **une seule fois**, pour les créneaux terminés entre 1 h et
3 h auparavant — règle produit assumée : jamais de rappel insistant.

**La fenêtre de présence se referme** (`slot_participations.attendance_closed_at`), et c'est
ce qui rend le signal de fiabilité honnête : un silence n'est pas une absence. Passé le
délai, la question ne se pose plus et la ligne **sort du dénominateur** au lieu d'y compter
comme un « non » — compter un silence, c'est décider qu'il veut dire quelque chose.

### 4.5 Recaps de créneau (`domain/recap`)

Un souvenir partagé, créé par occurrence (`uq_recap_occurrence(schedule_id, occurrence_start)`).

Règles codées : fenêtre de contribution de **7 jours** après la fin de l'occurrence,
**2 vibes maximum** par utilisateur parmi 8 (`RELAXED`, `ENERGETIC`, `FRIENDLY`,
`TECHNICAL`, `BEGINNER_FRIENDLY`, `GOOD_LAUGH`, `FOCUSED`, `OUTDOORS`), **3 vibes**
affichées au sommet, **3 photos** maximum, note de l'organisateur limitée à 400 caractères.

```
GET    /api/slots/{sid}/recap
POST   /api/slots/{sid}/recap/vibes     ·  DELETE /api/slots/{sid}/recap/vibes
PATCH  /api/slots/{sid}/recap/consent   ·  PATCH …/photo  ·  PATCH …/note  ·  PATCH …/visibility
GET    /api/recaps/feed                 ·  GET /api/recaps/mine
GET    /api/programs/{id}/recaps  ·  /api/activities/{id}/recaps  ·  /api/users/{id}/recaps
```

### 4.6 Messagerie (`domain/chat`)

Conversations `DIRECT`, `GROUP` ou `PROGRAM_BROADCAST`, avec contexte optionnel
(activité, programme, créneau). Messages éditables (historique conservé dans
`message_edit_history`), supprimables en douceur (`deleted_at`), avec images.

La **diffusion programme** n'ajoute qu'une seule route : le fil créé apparaît ensuite dans
la messagerie normale avec `type: "PROGRAM_BROADCAST"`, et `POST /api/conversations/{id}/messages`
refuse en 403 tout expéditeur autre que l'auteur du programme.

**Confort de messagerie** *(nouveau)* : indicateur de saisie, partage de position ponctuel,
sourdine et archivage par conversation.

L'indicateur de saisie **n'écrit rien nulle part** — un « untel écrit… » ne vaut que dans la
seconde où il est émis. Il vérifie tout de même l'appartenance : sans ce contrôle, n'importe
quel compte connecté ferait apparaître son nom dans le fil de n'importe qui, ce qui suffit à
découvrir qu'une conversation existe. Le serveur ne pose **aucune échéance** et n'émet aucun
rappel : c'est au client d'effacer l'indicateur après quelques secondes, un émetteur qui perd
sa connexion ne pouvant jamais annoncer qu'il s'est arrêté.

Sourdine et archivage vivent sur `conversation_members` : deux personnes d'un même fil n'ont
aucune raison de le classer pareil. La sourdine coupe **l'émission, pas la réception**.
L'archivage ne se défait pas tout seul — un message reçu ne ressort pas le fil, sinon ranger
celui dont on veut se débarrasser n'aurait aucun effet.

Les deux sortent du **total** de `unread-count` sans sortir du décompte par fil. L'invariant
« la somme des fils égale le badge » a donc été redéfini plutôt que rompu en silence :
`ConversationSummaryDto` porte `muted` et `archived`, et la somme des fils qui ne sont ni
l'un ni l'autre retombe exactement sur le total.

```
POST   /api/conversations               ·  GET /api/conversations?archived=
GET    /api/conversations/unread-count
GET    /api/conversations/{id}          ·  DELETE /api/conversations/{id}
POST   /api/conversations/{id}/messages ·  GET /api/conversations/{id}/messages
POST   /api/conversations/{id}/read     ·  POST /api/conversations/{id}/read-all
POST   /api/conversations/{id}/images
POST   /api/conversations/{id}/location      ← partage ponctuel, 30 min au plus
PATCH  /api/conversations/{id}/settings      ← sourdine, archivage
PATCH  /api/messages/{id}               ·  DELETE /api/messages/{id}
POST   /api/programs/{id}/broadcasts
STOMP  /app/chat.send                   ·  STOMP /app/chat.typing
```

### 4.7 Notifications et push (`domain/notification`)

**30 types** de notification (`NEW_MESSAGE`, `PROGRAM_REMINDER`, `SLOT_JOINED`,
`ATTENDANCE_PROMPT`, `BADGE_EARNED`, `AUTHOR_NEW_PROGRAM`, `CATEGORY_NEW_ACTIVITY`,
`ACTIVITY_ALERT_MATCH`, `STREAK_MILESTONE`…), trois canaux (`EMAIL`, `PUSH`, `IN_APP`),
trois fréquences (`IMMEDIATE`, `DAILY_DIGEST`, `WEEKLY`), préférences par type et par
utilisateur.

Le push Firebase est **localisé par appareil** : `device_tokens` porte `locale` et
`timezone`, et `PushNotificationService` regroupe les envois par (locale, variante,
fuseau) avant de composer titre et corps depuis les `messages*.properties`. Les tokens
invalides sont purgés à la réponse de Firebase.

`UnreadCounter` centralise le total non lu : iOS n'offre **qu'un** badge d'icône, un
message non lu y compte donc autant qu'une notification non lue.

**Heures de silence** *(nouveau)* — `users.quiet_hours_start` / `_end`, et `QuietHours` pour
les lire. La fenêtre **traverse minuit** dans le cas courant : « 22 → 7 » n'est pas un
intervalle croissant, et écrite naïvement elle ne contient rien du tout — le réglage le plus
courant du produit n'aurait alors aucun effet, sans erreur ni trace.

Le filtrage descend **jusqu'à l'appareil**, avec le fuseau de chacun : un téléphone à Paris
et une tablette restée à Tokyo ne sont pas dans la nuit au même moment. Ce qui est coupé,
c'est la push ; la notification est écrite dans tous les cas et attend au réveil.

**Deux classifications distinctes, et non une.** `isCritical()` dit ce qui traverse le
silence — annulation de créneau ou de programme, changement d'horaire, et le rappel de
séance, qui part deux heures avant quelque chose qu'on a choisi de rejoindre.
`warrantsEmail()` dit ce qui part aussi par e-mail, et n'y range pas le rappel : il en
partirait un par séance rejointe par chacun, ce qui ferait couper le canal entier — y
compris pour les annulations, qui en sont la raison d'être.

`PROGRAM_BROADCAST` ne traverse **pas** le silence : son contenu est un texte libre que le
serveur ne sait pas lire, et le classer critique donnerait à tout auteur le moyen de
réveiller ses participants avec n'importe quel message.

```
GET    /api/notifications               ·  GET /api/notifications/unread-count
PUT    /api/notifications/{id}/read     ·  PUT /api/notifications/read-all
DELETE /api/notifications/{id}
GET    /api/notifications/preferences   ·  PUT /api/notifications/preferences
GET    /api/notifications/quiet-hours   ·  PUT /api/notifications/quiet-hours
POST   /api/notifications/devices       ·  GET /api/notifications/devices
DELETE /api/notifications/devices/{token}
```

### 4.8 Abonnements (`domain/subscription`)

Trois cibles : un **auteur**, une **user_activity**, une **catégorie**. Trois niveaux
(`ALL`, `NEW_ONLY`, `MUTED`). Une portée géographique optionnelle (`lat`/`lng`/`radius_meters`)
pour les abonnements de catégorie. Unicité garantie par trois index partiels, un par type.

Le service pousse les notifications de fan-out : nouvelle activité d'un auteur suivi,
nouveau programme, mise à jour d'activité, et — cas particulier — **première annonce
localisée d'une catégorie** (`user_activities.category_notified_at` évite le doublon).

```
POST/DELETE/PATCH  /api/users/{id}/subscription
POST/DELETE/PATCH  /api/user-activities/{id}/subscription
POST/DELETE/PATCH  /api/categories/{id}/subscription
GET                /api/users/me/subscriptions   ·  GET /api/users/me/subscribers
```

### 4.9 Recherche (`domain/search`)

Quatre couches, et **leur ordre est le contrat** :

1. **`FullTextSearchService`** — `tsvector` PostgreSQL sur `programs.search_vector`
   (index GIN), entretenu de façon asynchrone par `IndexationService`.
2. **`SemanticSearchService`** — embeddings vectoriels avec index **HNSW cosinus**
   (`m=16, ef_construction=64`) sur `activities`, `programs` et `search_logs`. Le modèle
   tourne **en local** (DJL + ONNX Runtime + tokenizers HuggingFace), téléchargé au premier
   démarrage : plus aucun appel à une API payante. Seuil de similarité configurable,
   défaut **0,25**, volontairement bas pour tolérer l'écart du matching interlingue.
3. **`ActivityTaxonomy`** — table de correspondance canonique EN/DE/FR garantissant le
   matching déterministe sur les activités connues (« Laufen » → slug `running` → tous les
   programmes de course, quelle que soit la langue de stockage).
4. **Similarité trigramme** *(nouveau, `pg_trgm`)* — `searchByTrigramSimilarity`, seuil
   **0,3**, index GIN sur `activities.name` et `programs.title`. Elle ne s'exécute **que si
   les trois précédentes n'ont rien rendu**.

L'ordre de la quatrième n'est pas négociable : la similarité trigramme ne sait pas ce qu'est
un mot — « Yoga » et « Toga » partagent trois trigrammes sur quatre — et la fusionner avec
les autres ferait remonter du vaguement ressemblant au-dessus de l'exact. Placée en dernier,
elle ne peut que transformer une réponse vide en réponse imparfaite, jamais dégrader une
réponse qui fonctionnait. Elle ne rattrape que la **faute de frappe**, dans la langue où elle
a été faite : « Klettern » et « escalade » n'ont aucun trigramme commun, et c'est la
taxonomie qui les rapproche.

Le seuil est écrit dans la requête plutôt que laissé au réglage de session
`pg_trgm.similarity_threshold`, qui est global et modifiable par n'importe quelle autre
requête.

`RuleBasedIntentExtractor` remplace l'appel LLM d'origine par un pipeline de règles et
mots-clés FR/EN/DE : il ne comprend pas le langage naturel libre, mais il est gratuit,
local et ne lève jamais d'exception. `TimeHintParser` traduit « demain soir », « ce
week-end » en fenêtre `[from, to]`, avec repli sur *maintenant → +7 jours*.

```
POST   /api/search
GET    /api/search/popular              ·  GET /api/search/recent
DELETE /api/search/recent               ·  DELETE /api/search/recent/{id}
GET    /api/search/tags                 ·  GET /api/search/tags/popular
```

### 4.10 Carte (`domain/map`)

Utilisateurs, activités et programmes affichés par emprise (*bounds*), par rayon, ou
agrégés en **clusters** dont la taille de grille dépend du zoom. Le service applique le
floutage de position et les drapeaux de confidentialité avant de rendre quoi que ce soit.
Géocodage et géocodage inverse inclus.

```
GET    /api/map/users      ·  /api/map/clusters  ·  /api/map/bounds
GET    /api/map/nearby/{type}  ·  /api/map/activities   ← authentifiée depuis le 2026-08-19
GET    /api/map/geocode    ·  /api/map/reverse-geocode
POST   /api/map/location
```

`/map/activities` filtre les organisateurs bloqués **en mémoire**, à contre-courant du reste
du domaine qui pousse le prédicat dans le SQL. Ce n'est pas une facilité : un marqueur agrège
plusieurs organisateurs sur une même activité, et `totalInBounds`, les `count` de clusters et
`truncated` dérivent tous de la liste après agrégation. Écarter les créneaux **avant** de
construire les marqueurs les laisse exacts ; un post-filtrage les aurait tous faussés.

### 4.11 Alertes de proximité (`domain/alert`)

Un utilisateur pose une alerte « préviens-moi si une séance de *X* apparaît dans *N* mètres
autour d'ici ». Point PostGIS + rayon, index GIST, une alerte par (utilisateur, activité),
horodatage du dernier déclenchement.

```
GET/POST  /api/alerts   ·  PATCH/DELETE /api/alerts/{id}
```

### 4.12 Confiance : avis, recommandations, badges (`domain/review`, `recommendation`, `badge`, `trust`)

- **Avis** sur un programme : un par (programme, relecteur), score global plus sous-scores
  par critère (`review_criteria`), et une **preuve d'interaction** obligatoire.
- **Recommandations entre pairs** : une par (recommandeur, recommandé), avec preuve
  `CONVERSATION` **ou** `SHARED_ATTENDANCE` (double confirmation de présence sur le même
  créneau — une preuve au moins aussi forte qu'une conversation).
- **Badges** : évaluation automatique par `BadgeConditionType` (21 conditions —
  `ATTENDANCE_COUNT`, `DISTINCT_PARTNERS`, `WEEKLY_STREAK`, `PROGRAM_COUNT`,
  `ACTIVITY_DIVERSITY`, `RECOMMENDATION_COUNT`, `SLOT_HOSTED_COUNT`…). Les badges `MANUAL`
  ne peuvent jamais être attribués automatiquement.

```
GET    /api/reviews/programs/{id}  ·  /api/reviews/programs/{id}/summary
POST   /api/reviews  ·  GET /api/reviews/me  ·  GET /api/reviews/can-review/{programId}
POST   /api/recommendations  ·  GET /api/recommendations/received | given
GET    /api/recommendations/users/{id} | stats/{id} | can-recommend/{id} | me/stats
GET    /api/badges  ·  /api/badges/me  ·  /api/badges/users/{id}  ·  /api/badges/me/count
POST   /api/badges/me/evaluate
```

### 4.13 Progression (`domain/progression`)

Journal de progression d'un participant sur un programme (titre, contenu, métriques
libellées, public ou privé), avec séries (*streaks*) et statistiques.

```
POST/GET/PUT/DELETE  /api/progressions[/{id}]
GET    /api/progressions/program/{id}  ·  /user/{id}  ·  /my  ·  /my/streak  ·  /my/stats
```

### 4.14 Médias (`domain/media`)

Stockage local sur disque (`storage.location`, volume monté en production).
`MediaValidator` contrôle le type réel via **Apache Tika**, `ImageProcessor` redimensionne,
ré-encode (par sécurité) et compresse via **Thumbnailator**. Limite : 10 Mo par fichier.

`StoredImageResolver` vérifie l'existence du fichier avant de rendre l'URL et retourne
`null` s'il a disparu — séquelle de l'incident du 11 août 2026 qui a laissé en base des
références orphelines.

```
POST   /api/media/upload/image  ·  POST /api/media/upload/avatar
GET    /api/media/files/{*path} ·  DELETE /api/media/files/{*path}
```

### 4.15 Signalements et modération (`domain/report`)

Signalement d'un `USER`, `PROGRAM` ou `MESSAGE`, sept motifs (`SPAM`, `HARASSMENT`,
`INAPPROPRIATE_CONTENT`, `FAKE_PROFILE`, `VIOLENCE`, `HATE_SPEECH`, `OTHER`), cycle
`PENDING` → `REVIEWED` / `ACTIONED` / `DISMISSED`.

```
POST   /api/reports  ·  GET /api/reports/me  ·  GET /api/reports/pending
PUT    /api/reports/{id}/review
```

### 4.16 RGPD et audit (`api/GdprController`, `domain/gdpr`, `domain/audit`)

Export complet des données d'un utilisateur, suppression de compte avec anonymisation, et
`audit_logs` traçant chaque action sensible (acteur, type, entité, ancienne et nouvelle
valeur, IP, user-agent).

```
GET    /api/gdpr/export  ·  DELETE /api/gdpr/delete-account
```

### 4.17 Administration et indexation

```
POST   /api/admin/seed/demo/reset  ·  POST /api/admin/seed/status
GET    /api/indexation/stats
POST   /api/indexation/reindex/programs | reindex/activities | reindex/all
POST   /api/indexation/backfill-embeddings
```

---

## 5. Traitements planifiés

| Job | Cadence | Rôle |
|---|---|---|
| `RecurringSlotRolloverJob` | `0 */10 * * * *` | avance les créneaux récurrents passés vers l'occurrence suivante |
| `ProgramReminderJob` | `0 */5 * * * *` | rappel « votre séance commence bientôt » à **T‑2 h** |
| `AttendancePromptJob.promptAttendanceConfirmation` | horaire (`:00`) | invite à confirmer la présence, une seule fois, pour les créneaux terminés depuis 1 à 3 h |
| `AttendancePromptJob.closeElapsedSlots` | horaire (`:15`) | ferme les créneaux écoulés |
| `GdprPurgeJob.purgeInactiveAccounts` | quotidien 03 h 00 | purge des comptes inactifs |
| `GdprPurgeJob.purgeOldAuditLogs` | mensuel, le 1er à 04 h 00 | purge des journaux d'audit anciens |
| `ExpiredLocationSweepJob` | toutes les 10 min | efface les coordonnées des partages de position échus |

`ExpiredLocationSweepJob` **ne fait pas expirer** les partages : c'est la lecture qui décide,
et elle ne sert jamais un point échu, y compris entre l'échéance et le passage suivant.
Le job empêche la base d'accumuler l'historique des déplacements de chacun — sans lui, le
garde-fou ne serait vrai que du côté de l'API.

---

## 6. Base de données

### 6.1 Extensions

`uuid-ossp` (identifiants), **PostGIS** (colonnes `geometry`, index GIST),
**pgvector** (colonnes `vector`, index HNSW cosinus), **pg_trgm** *(V77 — similarité
trigramme, index GIN, quatrième couche de recherche)*.

### 6.2 Inventaire des tables (39 tables applicatives)

| Domaine | Tables |
|---|---|
| Identité | `users`, `audit_logs` |
| Catalogue | `categories`, `activities`, `user_activities` |
| Programmes | `programs`, `schedules`, `program_media`, `user_programs`, `program_activities` |
| Créneaux | `slot_participations`, `attendances` |
| Recaps | `slot_recaps`, `recap_vibe_votes`, `recap_participant_consents` |
| Messagerie | `conversations`, `conversation_members`, `messages`, `message_edit_history` |
| Confiance | `reviews`, `review_criteria`, `peer_recommendations`, `badges`, `badge_awards` |
| Notifications | `notifications`, `notification_prefs`, `device_tokens` |
| Abonnements | `subscriptions` |
| Alertes | `activity_alerts` |
| Progression | `progressions`, `progression_entries` |
| Recherche | `search_logs` |
| Modération | `reports`, `user_blocks` |
| Partage | `slot_safety_shares`, `slot_invitations` |
| Préférences | `user_languages`, `user_availability`, `schedule_accessibility_tags` |

> **Doublon connu** : `progressions` et `progression_entries` coexistent ; seule
> `progressions` est mappée par l'entité `Progression` (elle ajoute `metric_labels` et
> `updated_at`). `progression_entries` est un vestige de `V9`.

### 6.3 Colonnes, table par table

Ce document ne les répète plus. Le détail colonne par colonne — types, contraintes,
valeurs par défaut, et surtout les **règles que la DDL ne dit pas** — vit dans
[`DATABASE_SCHEMA.md`](DATABASE_SCHEMA.md), qui est un relevé par **introspection** et non
une rédaction à la main.

Les deux listes ont coexisté jusqu'au 20 août 2026, et c'est ainsi que l'une d'elles est
restée figée à `V59` pendant dix-huit migrations : deux endroits pour une même vérité en
font toujours un qui se périme, et rien ne dit lequel. Une seule source, refaite par
introspection à chaque campagne de migrations, coûte dix minutes et ne se trompe pas.


### 6.4 Politique des clés étrangères

La règle générale est `ON DELETE CASCADE` : supprimer un utilisateur emporte ses activités,
programmes, messages, abonnements, appareils, alertes, présences et badges.

Trois exceptions délibérées :

| Contrainte | Politique | Raison |
|---|---|---|
| `activities.category_id → categories.id` | **RESTRICT** | on ne supprime pas une catégorie encore utilisée |
| `reviews.interaction_proof_id → conversations.id` | **RESTRICT** | la preuve d'interaction ne doit pas disparaître sous un avis |
| `peer_recommendations.conversation_id → conversations.id` | **RESTRICT** | idem pour une recommandation |

Six `SET NULL`, où la référence n'est qu'un contexte optionnel :
`user_programs.schedule_id`, `conversations.program_id` / `schedule_id` /
`activity_context_id`, `search_logs.user_id`, `audit_logs.user_id`.

Et trois FK **sans action** (`NO ACTION`), qui bloquent donc la suppression :
`attendances.user_id`, `slot_participations.user_id`, `activity_alerts.activity_id`.

### 6.5 Index remarquables

**Spatiaux (GIST)** — `users(location)`, `schedules(location)`, `activity_alerts(location)`.

**Vectoriels (HNSW, cosinus, `m=16 / ef_construction=64`)** —
`activities(embedding)`, `programs(embedding)`, `search_logs(query_embedding)`,
tous en **`vector(384)`** depuis le passage au modèle local (`V48`).

**Plein texte (GIN)** — `programs(search_vector)`.

**Trigrammes (GIN, `gin_trgm_ops`, V77)** — `activities(name)`, `programs(title)`. Sans eux,
chaque requête approximative devient un balayage complet des deux tables.

**Partiels** — trois index d'unicité sur `subscriptions`, un par type d'abonnement ;
`uq_conversations_program_broadcast` sur `conversations(program_id) WHERE type = 'PROGRAM_BROADCAST'` ;
`slot_participations(schedule_id, waitlist_position) WHERE status = 'WAITLISTED'`, le rang
n'ayant de sens que dans la file ; `idx_schedules_reminder_sweep` sur
`schedules(starts_at) WHERE status IN ('OPEN','FULL')`, taillé pour le balayage du job de
rappel ; `idx_messages_location_expires` sur `messages(location_expires_at) WHERE
location_expires_at IS NOT NULL`, pour celui de l'effacement des positions.

### 6.6 Historique des migrations

77 fichiers appliqués. Quatre périodes se lisent dans la numérotation :

- **V1 → V15** — mise en place du schéma (extensions, utilisateurs, catalogue, programmes,
  messagerie, confiance, notifications, modération, audit).
- **V16 → V39** — corrections et alignements : décalages entité/schéma (`V18`), colonnes de
  compatibilité (`V19`), et une série peu flatteuse de **six migrations successives**
  (`V29` → `V34`) pour réparer des hachages de mots de passe de données de démo.
- **V40 → V59** — les fonctionnalités récentes : créneaux et participations (`V40`),
  présences (`V41`), alertes (`V42`), abonnements (`V36`, `V58`), embeddings locaux (`V48`),
  contexte programme des conversations (`V51`), diffusion programme (`V53`), recaps
  (`V54`, `V57`), localisation des appareils (`V49`, `V56`).
- **V60 → V77** — les phases A à D du TODO v2, puis la spécification des liens publics :
  parcours d'accueil (`V60`, réaligné sur les écrans réels en `V74`), créneau rapide
  (`V61`), blocage (`V62`), lien de sécurité (`V63`), règles de communauté (`V64`), partage
  public (`V65`), invitations (`V66`), liste d'attente (`V67`), annulation (`V68`), signal de
  fiabilité (`V69`), fermeture de la fenêtre de présence (`V70`), langues (`V71`),
  accessibilité (`V72`), disponibilités (`V73`), confort de messagerie (`V75`), heures de
  silence (`V76`), trigrammes (`V77`), partage public de programme (`V78`).

Configuration Flyway notable : `baseline-on-migrate=true`, `validate-on-migrate=false` et
`repair-on-migrate=true` — tolérant, au prix d'une détection plus faible des dérives.

### 6.7 Amorçage des données

- **`ReferenceDataSeeder`** (`pair.seed.reference-data.enabled`, actif par défaut) charge
  catégories, activités et badges depuis `resources/seed/data/*.json`, en ignorant ce qui
  existe déjà, puis génère les embeddings manquants.
- **`DemoDataSeeder`** (`pair.seed.demo-data.enabled`, désactivé en local, **activé** sur
  Railway) et `ResetDemoDataCommand`, pilotable par `POST /api/admin/seed/demo/reset`.

---

## 7. Internationalisation

Trois langues : **français** (défaut et repli), **anglais**, **allemand** —
`messages.properties` (145 lignes), `messages_en.properties` (129),
`messages_de.properties` (131). La résolution se fait sur `Accept-Language`, avec
rapprochement vers la locale supportée la plus proche. Le push utilise en priorité la
`locale` enregistrée sur l'appareil.

> Les fichiers `en` et `de` comptent une quinzaine de clés de moins que le français : des
> traductions manquent.

**La page publique de créneau** est servie en FR / EN / DE, et sa langue n'est **pas** celle
de la requête : c'est celle de la séance (`schedules.primary_language`) qui prime — la langue
dans laquelle elle se tiendra, donc celle du lecteur visé, mieux que l'`Accept-Language` d'un
appareil qui n'appartient peut-être pas à quelqu'un du coin. À défaut seulement, l'en-tête,
puis le français.

Ses libellés sont donc résolus **dans le contrôleur** et non par `#{...}` dans le gabarit :
Thymeleaf les résoudrait d'après l'en-tête, ce qui donnerait une page dont le texte et la
date ne parlent pas la même langue. Les **motifs de date** eux-mêmes viennent du catalogue
(`public.slot.datePattern`) : l'ordre des éléments et le séparateur diffèrent d'une langue à
l'autre. Le décompte passe par un choix de catalogue et non par un ternaire — à zéro il dit
« Personne encore, soyez le premier » plutôt que « 0 inscrit », qui se lit comme un aveu
d'échec sur la seule page censée donner envie.

---

## 8. Tests

**105 classes de test**, ~22 400 lignes, **763 tests**.

- **Tests unitaires** — un par service dans `domain/<domaine>/`, sur Mockito.
- **Tests d'intégration** — `src/test/.../integration/`, adossés à
  `AbstractIntegrationTest` et **Testcontainers PostgreSQL** (image PostGIS), donc contre
  une vraie base avec les vraies migrations. Ils couvrent les parcours transverses :
  authentification, chat WebSocket, carte, recherche sémantique et multilingue,
  pagination, contrat OpenAPI, injections, RGPD, codes d'erreur métier, conflits d'agenda,
  recaps, abonnements, diffusion programme, service de fichiers médias, blocage, partage
  public, liste d'attente, annulation, heures de silence, tolérance aux fautes de frappe.
- Un faux `com.google.firebase.messaging.FcmResponses` permet de tester le push sans
  Firebase réel.

---

## 9. Configuration et déploiement

### 9.1 Profils

| Profil | Particularités |
|---|---|
| *(défaut)* | PostgreSQL local `localhost:5432/pair_db`, **HTTPS sur 8090** (keystore PKCS12 embarqué) + HTTP sur 8091, seed de démo désactivé |
| `dev` | développement local |
| `staging` | pré-production |
| `prod` | production |
| `railway` | SSL désactivé (terminaison en amont), port `${PORT:8080}`, connexion via `PGHOST/PGPORT/PGDATABASE/PGUSER/PGPASSWORD`, **Resend** à la place de SMTP, seed de démo **activé**, modèle d'embeddings sur volume persistant `/app/models/embedding` |

### 9.2 Variables d'environnement principales

`DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `SMTP_*` / `RESEND_*`, `EMAIL_FROM`,
`APP_BASE_URL` / `FRONTEND_URL`, `STORAGE_PATH`, `FIREBASE_ENABLED` +
`FIREBASE_CREDENTIALS_PATH` ou `FIREBASE_CREDENTIALS_BASE64`, `REDIS_ENABLED`,
`MEETDO_EMBEDDING_ENABLED`, `MODEL_PATH`, `MODEL_BASE_URL`,
`SEARCH_EMBEDDING_MIN_SIMILARITY`.

### 9.3 Image Docker

Build multi-étapes : `eclipse-temurin:21-jdk-alpine` pour compiler (`mvnw clean package
-DskipTests`), `eclipse-temurin:21-jre-jammy` pour exécuter. `STORAGE_PATH=/app/uploads`
est écrit **en absolu** pour coïncider visiblement avec le point de montage du volume
Railway — précaution née de l'incident du 11 août 2026, où tous les médias de production
étaient devenus illisibles.

### 9.4 Supervision

Actuator expose `health`, `info`, `metrics`. `/actuator/health` est public ; les détails ne
sont visibles qu'aux appelants autorisés. Chaque ligne de journal porte le
`X-Request-Id` de la requête en cours, renvoyé au client dans la réponse.

---

## 10. Points d'attention

Relevés à la lecture du code, sans jugement sur leur priorité :

1. **Secrets par défaut en clair** dans `application.properties` : `jwt.secret` a une
   valeur de repli en dur, `server.ssl.key-store-password=pair2026` aussi, et
   `spring.datasource.password` retombe sur `Pair2026!`. Acceptable en local, à couvrir par
   les variables d'environnement partout ailleurs.
2. **`progressions` / `progression_entries`** — deux tables pour un seul concept, une seule
   mappée.
3. **Flyway permissif** — `validate-on-migrate=false` et `repair-on-migrate=true` masquent
   les dérives de checksum.
4. **Traductions incomplètes** en anglais et en allemand.
5. **Rate limiting en mémoire** — les quotas Bucket4j ne sont pas partagés entre instances ;
   Redis est déjà en dépendance mais désactivé par défaut.
6. **Racine du dépôt encombrée** — une dizaine de fichiers `.log`, des scripts de test et
   des documents de mise en production y sont versionnés.
7. **Six migrations consécutives** (`V29`–`V34`) pour un même correctif de hachage : le
   coût d'une correction de données passée par le mécanisme de migration.
8. **L'empreinte SHA-256 Android manque**, donc `/.well-known/assetlinks.json` répond `404`.
   C'est délibéré : Apple et Google mettent ces fichiers en cache agressivement, et une
   association fausse mémorisée par un appareil est plus longue à corriger qu'une absente.
   Elle dépend d'une décision non prise — signature locale ou Play App Signing.
9. **Le domaine public est `lien.meetdo.fun`**, et l'infrastructure n'existe pas encore :
   `PUBLIC_BASE_URL` doit être posé sur Railway et le sous-domaine créé côté DNS.
   `meetdo.fun` reste le site vitrine. Ni proxy ni redirection ne permettaient de servir les
   deux depuis le même nom — `mod_proxy` est absent de l'offre d'hébergement, et une
   redirection `302` aurait cassé l'aperçu, plusieurs robots ne la suivant pas.
10. **Le domaine de l'UID iCalendar ne suit pas `PUBLIC_BASE_URL`**, et ne doit pas le
    suivre. Un UID est une identité, pas une adresse : c'est par lui qu'un agenda reconnaît
    un événement déjà importé et le met à jour au lieu de le dupliquer. Le faire dépendre de
    l'URL publique transformerait un changement de domaine en duplication silencieuse de
    tous les événements déjà présents dans les agendas — sans qu'aucun test ne s'en
    aperçoive, les deux fichiers étant valides.
11. **Deux points restent à arbitrer avec l'équipe mobile** : la lecture de « Mes
    activités » dans l'Explorer, et les réglages e-mail, qui laissent croire à un choix par
    type que le serveur ne tient pas — `warrantsEmail()` ne poste que les faits rendant un
    déplacement inutile.

---

## 11. Pour aller plus loin

| Sujet | Document |
|---|---|
| Schéma détaillé colonne par colonne | `docs/DATABASE_SCHEMA.md` — **seule source**, relevée par introspection |
| Feuille de route livrée | `docs/specs/backend-todo-v2.md` (phases A à D) |
| Ce qui a été livré les 19–20 août, et **ce qui a été trouvé** | [`JOURNAL_2026-08-19_20.md`](JOURNAL_2026-08-19_20.md) |
| Liens publics et Universal Links | `docs/specs/meetdo-public-links-backend-spec.md` |
| Contrats et échanges avec le client | `docs/specs/PROMPT_*.md`, `docs/specs/REPONSE_*.md` |
| Guide frontend | `docs/FRONTEND_SPEC.md`, `docs/FRONTEND_DATABASE_GUIDE.md` |
| Déploiement Railway | `RAILWAY_ENV_VARS.md`, `QUICK_START_RAILWAY.md`, `docs/deployment/` |
| Push Firebase | `docs/specs/meetdo-firebase-push-activation.md` |
| E-mail Resend | `RESEND_SETUP.md`, `RESEND_QUICKSTART.md` |
| API interactive | `/swagger-ui.html` sur une instance démarrée |
