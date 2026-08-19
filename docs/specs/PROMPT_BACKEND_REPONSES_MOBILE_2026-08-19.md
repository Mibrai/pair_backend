# Réponses du client mobile — 19 août 2026

> En regard de `QUESTIONS_EQUIPE_MOBILE_2026-08-19.md`, section par section.
>
> Ce qui est affirmé ici a été **relevé dans le dépôt mobile**, pas de mémoire ;
> les chemins des fichiers sont donnés pour que vous puissiez vérifier. Ce qui
> reste à décider est nommé comme tel plutôt que deviné.
>
> **Trois niveaux :** ✅ répondu et vérifiable · 🔧 demande une action de notre
> côté, déjà engagée · ❓ nous ne pouvons pas trancher seuls.

---

## ⛔ 1. Les valeurs d'association — deux sur trois, et un angle mort

### ✅ Les identités

| Propriété | Valeur | Relevé dans |
|---|---|---|
| `pair.mobile.apple-app-id` | `97727T64DH.com.meetdo.app` | `ios/Runner.xcodeproj/project.pbxproj` (`DEVELOPMENT_TEAM`, `PRODUCT_BUNDLE_IDENTIFIER`) |
| `pair.mobile.android-package` | `com.meetdo.app` | `android/app/build.gradle.kts` (`applicationId`, `namespace`) |
| `pair.mobile.android-sha256` | **à fournir** | ni dans le dépôt ni dérivable : voir ci-dessous |

L'identifiant iOS est confirmé par un build de release passé aujourd'hui sur
appareil physique — la signature automatique a bien retenu cette équipe.

Attention à une variante qui traîne dans le projet Xcode :
`com.meetdo.app.MeetdoNotificationTests` est le bundle de la cible de test des
extensions de notification, **pas** celui de l'application.

### ❓ L'empreinte Android

Elle n'existe nulle part dans le dépôt, et c'est normal : elle dépend du
keystore de release, qui n'y est pas versionné. Vos deux mises en garde sont
exactes et nous ne pouvons pas y répondre sans savoir si la distribution passera
par **Play App Signing** — auquel cas c'est l'empreinte de la console qui fait
foi, celle du keystore local ne servant qu'à l'upload. La décision appartient à
qui tient le compte Play ; nous fournirons la valeur dès qu'elle est arrêtée.

### ✅ Le schéma d'URI personnalisé : il existe

`meetdo://` est déclaré des deux côtés et **déjà utilisé** :

- iOS — `ios/Runner/Info.plist`, `CFBundleURLSchemes: [meetdo]` ;
- Android — `android/app/src/main/AndroidManifest.xml`, hôtes `programs`,
  `activities` et `slot` ;
- routage — `lib/core/router/deep_links.dart`, qui traite `meetdo://programs/42`,
  `meetdo://activities/…` et `meetdo://slot/{jeton}`.

Il est donc utile de le faire figurer. Il ne remplace pas les liens universels :
un `meetdo://` collé dans une conversation n'est pas cliquable, c'est
précisément ce que la page publique corrige.

### 🔧 L'angle mort : servir l'association ne suffira pas

Le document présente les trois valeurs comme le seul obstacle. Il en manque un,
de notre côté, et nous le prenons en charge :

- **iOS** — `ios/Runner/Runner.entitlements` ne contient que `aps-environment`.
  Sans `com.apple.developer.associated-domains` (`applinks:meetdo.fun`), iOS ne
  téléchargera jamais l'AASA, si bien servi soit-il. Cela suppose aussi
  d'activer la capacité *Associated Domains* sur l'identifiant d'app du compte
  développeur, donc de régénérer les profils.
- **Android** — l'intent-filter App Links **est en commentaire** dans le
  manifeste, avec `REMPLACER_PAR_LE_DOMAINE_PUBLIC` en dur à la place du
  domaine. C'était délibéré (le commentaire le dit) : déclarer un domaine
  inexistant ne mène nulle part.

**Ce que nous vous demandons :** confirmez le domaine public définitif —
`meetdo.fun` est celui qui apparaît dans votre document. Les deux moitiés
doivent être posées avant de pouvoir tester quoi que ce soit : une association
servie sans entitlement, ou un entitlement sans association, échouent de la même
façon silencieuse.

### ❓ Les identifiants de magasin

Nous n'en avons aucun : l'application n'est publiée sur aucune des deux
boutiques à ce jour. Le repli de la page publique restera donc sans destination
tant qu'une première soumission n'a pas eu lieu.

---

## ⛔ 2. Les deux routes non authentifiées

### ✅ `/api/map/activities` n'est jamais appelée avant connexion

Le routeur (`lib/core/router/app_router.dart`) n'expose que cinq écrans hors
session : `/login`, `/register`, `/verify-email`, `/forgot-password` et
`/reset-password`. Aucun n'appelle la carte. Toutes les routes qui la
consomment vivent sous le shell authentifié.

**Notre avis : fermez-la.** Le client y perdra une ligne de configuration et
rien d'autre, et un profil bloqué qui garde ses activités visibles est un profil
qui n'est pas bloqué. Nous n'avons pas d'objection non plus pour
`/api/users/{id}/programs`, appelée depuis la fiche de profil, donc toujours
authentifiée.

Une réserve pour la forme : si un jour une page web publique — la page de
créneau du lot B1, par exemple — devait afficher une carte, elle aurait besoin
d'une route publique. Ce serait alors une route dédiée à la page publique, pas
la route de l'application.

---

## ⚠️ 3. Les décisions prises par défaut

### 3.1 Le prénom réduit — ✅ nous confirmons, avec la même réserve

La réduction au premier segment nous convient pour la page publique de créneau.
Elle nous convient **moins** pour la page de sécurité, et pour la raison que
vous nommez vous-même : un proche qui ouvre ce lien cherche à identifier
quelqu'un, et « Marie » identifie mal.

Nous ne demandons pas de changement immédiat — la colonne `first_name` est la
seule vraie réponse, et elle suppose une interface pour la remplir, donc un
écran de plus dans un parcours d'inscription que nous venons de raccourcir. À
mettre en attente ensemble plutôt qu'à trancher maintenant.

### 3.2 `POST /api/quick-slots` rend un `SlotFeedItemDto` — ✅ oui, c'est le bon modèle

C'est exactement celui que l'application manipule : `SlotFeedItem`
(`lib/models/slot_models.dart`) est le type du fil, de la fiche de créneau et
des résultats de recherche. Rendre le même objet que `GET /api/slots/{id}` nous
évite un aller-retour après publication.

### 3.3 Les règles de communauté embarquées — ✅ pris en compte

Le texte vit chez nous (`lib/core/legal/legal_guidelines_content.dart`, à côté
de `legal_terms_content.dart` que vous citez), la version et l'acceptation chez
vous : c'est le bon partage. Le `400 GUIDELINES_VERSION_MISMATCH` est compris,
et le réflexe que vous décrivez — relire `GET /api/users/me/guidelines` puis
réafficher — est celui que nous implémenterons.

Une conséquence qui vous concerne : le texte étant livré **avec l'application**,
une version poussée côté serveur avant la mise à jour du magasin rendrait
l'acceptation impossible pour tout le parc. Il faut donc que le changement de
version en vigueur suive la publication, jamais l'inverse.

### 3.4 `reliabilitySignal` — ✅ conforme à ce que fait l'app

Rien à changer : nous n'affichons rien quand il est nul, ne reconstituons aucun
pourcentage, ne trions pas dessus. Le principe « aucune valeur négative » est
même devenu un texte visible : la visite guidée livrée aujourd'hui l'énonce à
l'utilisateur — « c'est facultatif, et toujours positif : il n'existe aucune
note négative dans l'app ». Si cela devait changer un jour, ce serait une
promesse rompue, pas seulement un champ de plus.

### 3.5 Liste d'attente sans conflit d'agenda — ✅ compris

La vérification à la promotion est le bon moment. Nous affichons déjà
`myWaitlistPosition`.

---

## ℹ️ 4. Vos trois vérifications

### 4.1 `activityLevels` — ✅ nous l'envoyons déjà

Relevé dans `lib/features/programs/data/program_repository.dart`, méthode
`browseActivities` : le paramètre part en clair dans la requête, aux côtés de
`categoryIds`, `lat`, `lng` et `radiusMeters`.

Il n'y a donc rien à écrire pour D8, ni chez vous ni chez nous. Si un filtrage
local subsiste quelque part, ce serait un doublon à retirer, pas un manque à
combler — un test du dépôt (`browse_no_double_filter_test.dart`) garde ce point.

### 4.2 La position avant le fil — ✅ le cas ne peut pas se produire

Plus précisément que « l'écran dispose de la position » : le quatrième écran
**n'appelle pas** `/api/slots/feed` sans point. `onboardingPreviewProvider`
(`lib/features/onboarding/data/onboarding_providers.dart`) cherche d'abord le
point saisi, puis le GPS ; s'il n'obtient ni l'un ni l'autre, il rend un aperçu
marqué `hasLocation: false` et l'écran dit autre chose que « rien autour de toi »
— qui serait faux. Le `400` n'est donc jamais atteint.

Nous vous le signalons parce que l'inverse mérite d'être connu de votre côté :
un aperçu vide n'implique pas que la zone est vide, il peut n'y avoir eu aucune
requête.

Merci pour la précision sur `activities/suggested` et son drapeau `fallback` :
nous ne l'exploitions pas. Nous distinguerons les deux formulations — « près de
chez toi » et « populaire sur meetDo » —, une suggestion lointaine présentée
comme voisine étant la meilleure façon de faire douter de toutes les autres.

### 4.3 Les étapes d'onboarding — ❓ **non, les deux listes divergent**

C'est le point le plus important de cette réponse. Le parcours réel a **quatre
écrans**, dans cet ordre :

| # | Écran | Ce qu'on y demande |
|---|---|---|
| 1 | `ACTIVITIES` | « Qu'est-ce que tu aimes faire ? » |
| 2 | `LEVELS` | « À quel niveau ? » |
| 3 | `LOCATION` | « Où cherches-tu ? » |
| 4 | `PREVIEW` | « Voilà ce qui se passe autour de toi » |

Face à votre `WELCOME, LOCATION, ACTIVITIES, DISCOVERY, DONE` :

- **deux valeurs seulement coïncident** — `ACTIVITIES` et `LOCATION` ;
- `LEVELS` et `PREVIEW` n'existent pas chez vous ;
- `WELCOME`, `DISCOVERY` et `DONE` n'ont pas d'écran chez nous ;
- **l'ordre des deux valeurs communes est inversé** : votre contrat place
  `LOCATION` avant `ACTIVITIES`, le parcours demande les activités d'abord.

Ce dernier point est le plus gênant, à cause de la règle « un `PATCH` ne fait
jamais reculer » : l'étape « position » vous parviendrait puis serait ignorée en
`200`. L'échec serait donc **silencieux des deux côtés**, et sa conséquence
visible pour l'utilisateur seul — quitter l'app entre la position et l'aperçu,
puis reprendre au premier écran et tout refaire.

**Ce que nous avons fait en attendant** (commit du jour, `onboarding_step.dart`) :
chaque étape porte désormais deux noms, le sien et celui que nous vous
rapportons. `LEVELS` se replie sur `ACTIVITIES` — le niveau est, pour vous, un
attribut du choix d'activité — et `PREVIEW` se rapporte en `DISCOVERY`. Nous ne
vous enverrons donc jamais une valeur que vous rejetez. En lecture, nous
comprenons désormais vos cinq valeurs : `DISCOVERY` ramenait quelqu'un du
dernier écran au premier.

**Ce que nous vous demandons :** que `PATCH /api/users/me/onboarding` accepte les
quatre étapes réelles, dans cet ordre. Deux conséquences si vous le faites :

1. la reprise redevient exacte — c'est le seul moyen, la traduction perdant par
   construction la distinction entre le premier et le deuxième écran ;
2. `WELCOME` et `DONE` peuvent disparaître de l'énumération : le premier n'a
   jamais eu d'écran, et la fin se lit déjà sur `onboardingCompletedAt`.

Si vous préférez garder votre vocabulaire, dites-le : nous conserverons la
traduction et documenterons la reprise imparfaite comme une limite assumée. Ce
que nous ne pouvons pas faire, c'est deviner lequel des deux fait autorité.

---

## ℹ️ 5. Les champs additifs — ✅ pris en compte

`onboardingCompletedAt`, `onboardingStep`, `guidelinesVersion` et
`guidelinesAcceptanceRequired` sur `GET /api/users/me` : nous les lisons déjà,
et l'économie d'un second appel au lancement est réelle.

`createdVia` nous manquait : un programme `QUICK` sans description s'affichait
comme un programme mal rempli. Nous l'utiliserons pour ne pas montrer un vide
qui n'en est pas un.

`WAITLIST_PROMOTED` et l'envoi par e-mail de `SLOT_CANCELLED` sont notés. Sur ce
dernier, une question : l'e-mail part-il **en plus** de la notification push, ou
**à la place** quand le push échoue ? Nous n'affichons rien de différent selon
le cas, mais la réponse change ce que nous dirons dans les réglages de
notification, où l'utilisateur choisit ses canaux.

---

## Ce que nous livrons de notre côté aujourd'hui

Pour information, sans rien attendre de vous :

- une **visite guidée** en sept chapitres, contextuelle — chaque chapitre se
  déclenche sur son écran, au moment où il a quelque chose à expliquer. Elle
  s'appuie sur les notions de votre contrat (créneau contre programme, signal de
  fiabilité, liste d'attente) et les explique dans les mots de l'utilisateur ;
- l'alignement des étapes décrit en 4.3.

---

## Récapitulatif de ce qui vous revient

| # | Attendu | De qui |
|---|---|---|
| 1 | Empreinte SHA-256 de release (ou de Play App Signing) | qui tient le compte Play |
| 1 | Confirmation du domaine public définitif | vous |
| 2 | Fermer `/api/map/activities` et `/api/users/{id}/programs` | vous — nous n'y voyons pas d'obstacle |
| 4.3 | Accepter les quatre étapes réelles, ou confirmer le contraire | vous |
| 5 | E-mail d'annulation : en plus du push, ou en remplacement ? | vous |

Et de notre côté : entitlement `associated-domains` (iOS), intent-filter App
Links (Android), dès que le domaine est confirmé.
