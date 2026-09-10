# Rester connecté : ce que le serveur doit dire pour que l'app cesse de deviner

**Date :** 2026-09-10
**Module :** [`session/`](.)

> **Le symptôme, tel qu'il nous remonte :** « la session expire tout le temps,
> il faut se reconnecter ». Les utilisateurs ne veulent pas d'une durée plus
> longue : ils veulent que **ça ne se produise jamais**.
>
> **La plus grosse part était chez nous, et elle est corrigée** — commit
> `81bc9be` du 10/09. Notre intercepteur effaçait le trousseau dès qu'un
> rafraîchissement ratait, quelle qu'en soit la cause : un réveil sans réseau,
> un 502 pendant un déploiement, un 429 de votre limiteur. Nous détaillons ce
> défaut en §1 parce qu'il explique la fréquence du symptôme, et parce que deux
> de ses causes vous concernent.
>
> **Ce qui reste ne peut se lever que chez vous**, et tient en une question et
> quatre demandes :
>
> - **§2 — la question qui décide de tout :** votre rotation est-elle
>   *glissante* ? Un jeton de rafraîchissement émis lors d'une rotation vaut-il
>   30 jours de plus, ou hérite-t-il de l'échéance de son prédécesseur ? Dans le
>   second cas, **toute session meurt au bout de 30 jours quoi qu'on fasse**, et
>   c'est exactement le symptôme signalé.
> - **§3 — les durées ne sont écrites nulle part.** `AuthResponse` ne porte ni
>   `expiresIn` ni `refreshExpiresIn` : nos 15 minutes et nos 30 jours sont des
>   constantes recopiées d'un document d'août. Nous demandons qu'elles nous
>   soient dites.
> - **§4 — votre limiteur couvre `/auth/refresh`** (« Auth: 5-10 req/min », votre
>   propre `/v3/api-docs`). Un rafraîchissement limité renvoie un 429 qui, jusqu'à
>   hier, déconnectait. Nous demandons que cette route en sorte, ou qu'elle ait
>   son propre seuil.
> - **§5 — un `401` de session ne se distingue pas d'un `401` de droit.** Nous
>   demandons un `code` stable sur les 401 des routes ordinaires.
> - **§6 — ce que nous ne demandons pas**, et pourquoi.

---

## 1. Ce que faisait l'app, et pourquoi nous vous l'écrivons quand même

`AuthInterceptor` (`lib/core/network/api_client.dart`) n'avait qu'un `catch`
pour tout ce que le rafraîchissement pouvait jeter, et il se terminait par un
effacement du trousseau. Autrement dit : **« je n'ai pas pu vous joindre » était
traité exactement comme « vous m'avez dit non »**.

Le scénario ordinaire n'a rien d'exotique — c'est celui de quelqu'un qui ressort
son téléphone de sa poche. Notre `refresh_on_resume.dart` invalide onze
providers de contenu au retour au premier plan ; la rafale part à la milliseconde
où iOS nous rend la main, c'est-à-dire **avant que la radio n'ait réassocié le
Wi-Fi**. Les onze reviennent en 401 (votre jeton d'accès vit 900 s), le
rafraîchissement part dans le vide, le trousseau est effacé. Rien ne se voit sur
le moment : l'utilisateur le découvre au lancement suivant, sur l'écran de
connexion. **Un aller-retour raté détruisait une session de trente jours.**

Depuis le 10/09, seule une réponse **authentifiée** de votre part ferme une
session. Relevé de ce jour, sur la production :

```
$ curl -X POST https://…/api/auth/refresh \
       -H 'Content-Type: application/json' \
       -d '{"refreshToken":"jeton-bidon"}'
401 {"code":"INVALID_TOKEN","message":"Refresh token invalide ou expiré.",
     "timestamp":"2026-09-10T11:35:47Z"}
```

`401` et `403` ferment la session. Tout le reste — pas de réponse, `429`, `5xx`,
corps illisible — laisse les jetons en place, remonte la panne telle qu'elle est,
et la requête suivante repart du même trousseau. Nous avons ajouté trois
tentatives étalées sur ~1,6 s, le temps qu'il faut à une interface réseau pour
revenir, et nous rafraîchissons désormais **avant** d'émettre quand le jeton
approche de son échéance : un réveil vous coûte un appel d'authentification au
lieu de vingt-trois.

Nous vous l'écrivons pour deux raisons. La première est d'honnêteté : le gros du
défaut était chez nous, et il ne faut pas que vous cherchiez une panne serveur
qui n'existe pas. La seconde est que **deux des déclencheurs de ce défaut sont
des réponses que vous émettez** — le 429 du limiteur (§4) et tout `5xx`
transitoire —, et qu'un client qui ne se protège plus n'est pas une raison pour
que la route de rafraîchissement soit la plus fragile de l'API.

---

## 2. La question qui décide de tout : votre rotation est-elle glissante ?

C'est la seule chose qui sépare « la session ne finit jamais » de « la session
finit au bout d'un mois, quoi que fasse l'utilisateur ».

Ce que nous savons, et d'où nous le tenons :

| Fait | Source | Daté |
|---|---|---|
| Jeton d'accès : 900 s | mesure `load/` | 22/08/2026 |
| Rafraîchissement : 30 jours, **avec rotation** | `ios/docs/ETAT_IMPLEMENTATION_PRODUCTION_2026-08-25.md` | 25/08/2026 |
| Un jeton invalide rend `401 INVALID_TOKEN` | relevé HTTP | 10/09/2026 |

Ce que nous **ne** savons pas, et que le contrat ne dit pas : quand `POST
/auth/refresh` émet un nouveau jeton de rafraîchissement, quelle est **son**
échéance ?

- **Hypothèse A — glissante.** Le nouveau jeton vaut 30 jours à compter de son
  émission. Alors quelqu'un qui ouvre l'app une fois par mois ne se reconnecte
  jamais, et le symptôme signalé était **entièrement** le défaut du §1. Rien à
  faire de votre côté sur ce point.
- **Hypothèse B — héritée.** Le nouveau jeton porte l'échéance de la chaîne
  d'origine. Alors **toute session meurt 30 jours après la connexion**,
  rigoureusement, quel que soit l'usage — et l'app ne peut rien y faire.

Nous ne pouvons pas trancher de l'extérieur : il faudrait attendre trente jours
avec un compte de test, ou lire votre code.

**Demande 1 — dites-nous laquelle des deux, et si c'est B, faites-en A.**
Une session activement utilisée ne devrait pas avoir de fin. Si un plafond
absolu vous paraît nécessaire pour des raisons de sécurité, nous préférons de
loin qu'il soit **long et explicite** (un an d'inactivité, par exemple) plutôt
qu'un mois qui se déclenche sur un compte utilisé tous les jours.

**Demande 2 — la fenêtre se compte en inactivité, pas en ancienneté.** Ce qui
doit fermer une session, c'est « cet appareil ne s'est pas manifesté depuis N »,
et non « ce jeton a été créé il y a N ».

---

## 3. Les durées ne sont écrites nulle part

`AuthResponse`, tel que votre `/v3/api-docs` le publie aujourd'hui :

```json
{ "accessToken": "…", "refreshToken": "…", "userId": "…",
  "displayName": "…", "verificationStatus": "…" }
```

Aucune durée. Nos deux constantes viennent donc d'un document d'août, recopiées
à la main :

```dart
static const Duration accessTokenLifetime  = Duration(minutes: 15);
static const Duration refreshTokenLifetime = Duration(days: 30);
```

C'est exactement le genre de valeur que ce dépôt s'interdit d'affirmer sans
relevé daté, et pour une bonne raison : le jour où vous passerez le jeton
d'accès à 5 ou à 60 minutes, **rien ne nous le dira**. Notre rafraîchissement
anticipé partirait trop tard (donc des 401 en rafale) ou beaucoup trop tôt (donc
un rafraîchissement par requête, droit dans votre limiteur).

**Demande 3 — ajoutez les échéances à `AuthResponse`**, sur `/auth/login`,
`/auth/register` **et** `/auth/refresh` :

```json
{ "accessToken": "…",
  "refreshToken": "…",
  "expiresIn": 900,                    // secondes, jeton d'accès
  "refreshExpiresIn": 2592000,         // secondes, jeton de rafraîchissement
  … }
```

Des **durées relatives en secondes**, et non des dates absolues : l'horloge du
téléphone peut être fausse de plusieurs heures, et c'est précisément ce qui nous
a fait fermer des sessions valides (nous lisions une échéance que nous avions
calculée nous-mêmes, comparée à l'heure de l'appareil ; nous ne le faisons plus).
Deux champs facultatifs, ajoutés à un DTO existant : aucune rupture pour
personne, et nous les préférerons à nos constantes dès qu'ils seront là.

---

## 4. Votre limiteur couvre la route qui répare les sessions

Votre propre description d'API, dans `/v3/api-docs` :

> **Rate Limiting** — Recherche : 20 req/min · Upload : 10 req/min ·
> **Auth : 5-10 req/min**

Et le contrat de `POST /auth/refresh` annonce bien un `429` parmi ses réponses.

Un rafraîchissement qui se fait limiter renvoie donc un `429` — que nous
traitions, jusqu'à hier, comme une session finie. **Déconnecter quelqu'un parce
qu'il a parlé trop vite est la punition la plus absurde qui soit**, et c'est ce
que faisait l'app. C'est corrigé.

Il reste que cette route n'a pas la même nature que ses voisines. `/auth/login`
et `/auth/register` méritent un limiteur strict : ce sont les portes où l'on
essaie des mots de passe. `/auth/refresh`, non — celui qui l'appelle **présente
déjà un secret valide**. La limiter ne protège de rien et casse la seule
mécanique qui maintient les gens connectés. Un appareil un peu bavard (deux
onglets, une extension de notification, un `logout` suivi d'un `login`) peut
atteindre 5 appels/min sans rien faire d'anormal.

**Demande 4 — sortez `/auth/refresh` du seau « auth », ou donnez-lui un seuil
propre** (nous suggérons ~30/min par jeton, ce qui laisse toute la marge utile
tout en gardant un plafond contre une boucle folle). Et **si un `429` doit
subsister sur cette route, servez un en-tête `Retry-After`** : nous saurons
attendre le bon délai au lieu de deviner.

*Question annexe :* le limiteur de connexion (~8 tentatives puis blocage 15 min,
mesuré le 22/08) compte-t-il par IP ou par compte ? Sur un réseau d'entreprise
ou un partage de connexion, un blocage par IP touche des gens qui n'ont rien
demandé.

---

## 5. Un `401` de session ne se distingue pas d'un `401` de droit

Sur les routes ordinaires, un `401` nous arrive parfois nu. Nous ne pouvons donc
pas distinguer :

- « ton jeton d'accès a expiré » → il faut rafraîchir, silencieusement ;
- « ce jeton est valide mais ce compte n'a pas le droit d'aller là » → il ne faut
  **pas** rafraîchir, et surtout pas conclure à une session perdue.

Aujourd'hui nous rafraîchissons dans les deux cas, et le second nous fait
dépenser un appel pour rien — puis afficher un message qui parle de session
alors qu'il s'agit d'un droit. Nous avons dû coder en dur une liste de routes
« dont le 401 ne parle pas de la session » (`/auth/verify-email`, `/auth/login`,
`/auth/reset-password`) : c'est une liste qui vieillira mal.

**Demande 5 — un `code` stable sur les 401**, dans le corps `ErrorResponse` que
vous servez déjà : `TOKEN_EXPIRED` quand le jeton d'accès est simplement périmé,
et autre chose (`FORBIDDEN_SCOPE`, `EMAIL_NOT_VERIFIED`, …) sinon. Vous le faites
déjà très bien sur `/auth/refresh` (`INVALID_TOKEN`) ; c'est la même chose,
étendue aux routes ordinaires.

---

## 6. Ce que nous ne vous demandons pas

Pour que la liste ci-dessus se lise comme ce qu'elle est — courte et fermée :

- **Pas de jeton d'accès plus long.** 15 minutes est un bon réglage. Ce n'est
  pas lui qui déconnecte les gens ; c'est la façon dont son renouvellement
  échouait.
- **Pas de « se souvenir de moi ».** Ce serait un réglage utilisateur là où il
  faut une garantie. Personne ne devrait avoir à cocher quelque chose pour
  rester connecté.
- **Pas de liste d'appareils connectés, ni de révocation à distance.** Ce serait
  utile un jour, ce n'est pas ce qui bloque aujourd'hui, et cela ouvrirait un
  écran à concevoir. À noter cependant, si vous allongez la fenêtre du §2 : plus
  une session dure, plus « déconnecter mes autres appareils » finira par manquer.
  Nous le rouvrirons alors, pas avant.
- **Pas de changement sur `/auth/logout`.** Il fait ce qu'il faut. Nous relevons
  seulement que le contrat ne publie **aucun corps de requête** pour lui, alors
  que nous y envoyons `{"refreshToken": "…"}` — si ce corps est bien attendu, il
  manque au schéma ; s'il ne l'est pas, dites-le-nous et nous cesserons de
  l'envoyer.

---

## Récapitulatif

| # | Demande | §  | Effort supposé |
|---|---|---|---|
| 1 | Dire si la rotation est glissante — et la rendre glissante si elle ne l'est pas | §2 | à évaluer |
| 2 | Compter la fenêtre en **inactivité**, pas en ancienneté du jeton | §2 | à évaluer |
| 3 | `expiresIn` et `refreshExpiresIn` (secondes) dans `AuthResponse` | §3 | faible |
| 4 | Sortir `/auth/refresh` du limiteur « auth », ou seuil propre + `Retry-After` | §4 | faible |
| 5 | Un `code` stable sur les 401 des routes ordinaires (`TOKEN_EXPIRED`) | §5 | moyen |

La demande 1 est la seule qui décide du symptôme signalé. Les quatre autres sont
ce qui empêchera qu'il revienne sous une autre forme — et, pour la 3, ce qui
nous évitera de continuer à deviner vos durées.

**Côté app, rien n'attend ces réponses** : le correctif du §1 est livré, testé
(16 tests dans `test/resilience_session_perenne_test.dart`) et ne dépend
d'aucune d'elles. Nous adopterons `expiresIn` dès qu'il existera, et nous
retirerons la liste de routes du §5 dès que les codes seront stables.
