# Rester connecté : ce que dit le code, et ce que nous corrigeons

**Date :** 2026-09-10
**Module :** [`session/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-10.md`](PROMPT_BACKEND_2026-09-10.md)

> **La réponse à la demande 1, d'abord, parce qu'elle décide du reste :
> c'est l'hypothèse A. La rotation est glissante.**
> `AuthService.refreshToken()` ne fait aucune lecture d'une échéance
> antérieure : il rappelle `buildAuthResponse`, qui rappelle
> `JwtTokenProvider.generateRefreshToken`, qui pose
> `expiration = maintenant + 30 jours`, sans condition. **Une session utilisée
> au moins une fois par mois ne finit jamais.** Le symptôme qui vous remonte
> était donc entièrement le défaut de votre §1, et il n'y avait pas de panne
> serveur à chercher.
>
> **Mais votre document d'août vous a mal renseignés sur deux points, et les
> deux sont de notre fait :**
>
> - **« avec rotation » est un mot de trop.** Il n'y a pas de rotation, il y a
>   une **réémission**. Le jeton de rafraîchissement est un JWT que nous ne
>   stockons nulle part : nous ne pouvons donc ni l'invalider, ni savoir qu'il a
>   servi. L'ancien reste valable jusqu'à sa propre échéance, et `/auth/logout`
>   est un corps de méthode vide. **Se déconnecter ne déconnecte rien** (§2).
> - **`/auth/refresh` n'est limité par rien du tout.** Votre §4 s'appuie sur la
>   ligne « Auth : 5-10 req/min » de notre `/v3/api-docs` : c'est de la prose,
>   écrite à la main dans `OpenApiConfig`, et elle ne décrit aucun code. Le 429
>   que vous redoutiez sur cette route ne peut pas se produire aujourd'hui (§5).
>
> **Et l'audit a trouvé trois défauts que vous ne pouviez pas voir**, dont deux
> sont plus graves que ce que vous nous demandiez — notamment : **votre jeton de
> rafraîchissement de 30 jours est accepté comme jeton d'accès sur toutes les
> routes protégées** (§3).

---

## 1. Demandes 1 et 2 — la rotation, l'échéance, et ce qui manque pour l'inactivité

### La rotation est glissante. Voici la chaîne, en entier.

`AuthController.refresh()` (ligne 71) ne fait rien d'autre que passer le jeton
au service. `AuthService.refreshToken()` (ligne 79) valide la signature, extrait
l'identifiant, charge l'utilisateur, et rend `buildAuthResponse(user)` — la
**même** méthode que `login` et `register` (ligne 148). Et
`generateRefreshToken` (`JwtTokenProvider`, ligne 102) écrit :

```java
.expiration(new Date(System.currentTimeMillis() + refreshTokenExpiryMs))
```

`System.currentTimeMillis()`, pas une échéance héritée. Aucun état n'est lu,
parce qu'aucun état n'existe : rien n'est persisté pour ces jetons. **Votre
hypothèse B est impossible dans ce code.** La demande 1 n'appelle donc aucun
changement — elle appelle la confirmation que voici, et que nous ajoutons au
contrat (§4) pour qu'elle cesse d'être une chose qu'il faut demander.

### La demande 2, en revanche, nous ne pouvons pas encore l'honorer.

Vous demandez que la fenêtre se compte en **inactivité** et non en ancienneté.
C'est déjà le cas *par effet de bord* — chaque rafraîchissement repart de zéro,
donc l'échéance suit bien l'usage — mais nous ne pouvons pas le **garantir**, et
la nuance compte : nous ne mesurons l'inactivité nulle part.

`User.lastActiveAt` n'est écrit qu'à deux endroits : `AuthService.login()`
(ligne 73) et `UserService.updateLocation()`. Un rafraîchissement ne le touche
pas. Quelqu'un qui utilise l'app tous les jours sans jamais bouger ni se
reconnecter est, pour notre base, inactif depuis sa première connexion.

Ce n'est pas grave tant qu'aucun plafond absolu n'existe — et il n'en existe
pas. Cela le devient dès qu'on veut en poser un, ou révoquer quoi que ce soit,
ce qui est exactement le sujet du §2 ci-dessous. **Nous traitons donc la
demande 2 avec ce §2, pas séparément.**

---

## 2. Il n'y a pas de rotation, et `/auth/logout` ne déconnecte personne

Votre document d'août dit « 30 jours, avec rotation ». Le second membre est
faux, et nous préférons vous le dire franchement que vous laisser bâtir dessus.

**Ce que « rotation » veut dire d'ordinaire :** le jeton présenté est invalidé
au moment où son successeur est émis. Un jeton volé ne vaut donc que jusqu'au
prochain rafraîchissement du propriétaire légitime, et sa réutilisation est
détectable — c'est même le signal qui permet de fermer toute la chaîne.

**Ce que nous faisons :** nous émettons un nouveau JWT et nous ne touchons pas à
l'ancien. Il n'est pas invalidé parce qu'il n'est écrit nulle part — la table
`auth_tokens` ne porte que les jetons d'e-mail (`AuthTokenType` :
`EMAIL_VERIFICATION`, `PASSWORD_RESET`, `EMAIL_CHANGE`). Un jeton de
rafraîchissement vaut donc **30 jours à partir de son émission, quoi qu'il
arrive ensuite**.

Trois conséquences, dans l'ordre où elles vous concernent :

1. **`/auth/logout` est un corps de méthode vide.** `AuthService.logout()`
   (ligne 139) ne contient que des commentaires décrivant ce qu'il pourrait
   faire un jour. Quelqu'un qui se déconnecte sur un téléphone prêté laisse un
   jeton actif pour un mois. Vous écrivez au §6 « il fait ce qu'il faut » : non,
   et c'est notre faute, pas la vôtre — rien dans le contrat ne pouvait vous le
   dire.
2. **Nous ne pouvons révoquer aucune session**, ni à la demande, ni sur
   changement de mot de passe, ni sur désactivation de compte.
3. **Votre demande 2 est bloquée par là.** Compter en inactivité suppose de
   savoir quand un appareil s'est manifesté pour la dernière fois ; le savoir
   suppose de tenir une trace par session.

Nous proposons de persister les jetons de rafraîchissement — une ligne par
session, portant l'utilisateur, une empreinte du jeton, `derniere_utilisation`,
et un éventuel `revoque_le`. C'est ce qui rend possibles, du même coup, la
rotation réelle, le `logout` qui déconnecte, la fenêtre d'inactivité de votre
demande 2, et — le jour où vous le rouvrirez — la liste d'appareils de votre §6.

**Ce n'est pas un petit chantier, et nous ne le livrons pas dans ce lot.** Rien
ne vous attend : la session est déjà perpétuelle à l'usage, ce qui est ce que
vous demandiez. Nous le signalons parce que vous avez raisonné sur une garantie
de sécurité qui n'existe pas.

---

## 3. Trois défauts trouvés en auditant, que vous ne pouviez pas voir

Aucun n'était dans votre liste. Le premier est le plus sérieux de tout ce
document.

### 3.1 Un jeton de rafraîchissement est accepté comme jeton d'accès

`JwtAuthFilter` (ligne 32) accepte **tout JWT que notre clé a signé** :

```java
if (token != null && tokenProvider.validateToken(token)) {
```

`validateToken` (`JwtTokenProvider`, ligne 119) ne vérifie que la signature et
l'échéance. Personne ne lit le `claim("type", "refresh")` que
`generateRefreshToken` prend la peine d'écrire. Conséquence :
`Authorization: Bearer <votre jeton de rafraîchissement>` **fonctionne sur
toutes les routes protégées de l'API**, pendant trente jours.

Toute la logique des 15 minutes est donc facultative en pratique : le trousseau
contient, à côté du jeton court, un jeton long qui ouvre exactement les mêmes
portes. Ce n'est pas exploitable par un tiers sans accès au trousseau — mais
c'est précisément l'écart entre « la fuite d'un jeton coûte un quart d'heure »
et « elle coûte un mois ».

**Nous corrigeons dans les deux sens :** le filtre refusera un jeton portant
`type=refresh`, et `/auth/refresh` refusera un jeton qui ne le porte pas
(aujourd'hui, un jeton d'accès y est accepté et rend une session complète).

### 3.2 `refreshToken()` ne vérifie pas que le compte est actif

`login()` filtre sur `isActive` (ligne 55). `refreshToken()` ne le fait pas : il
charge l'utilisateur par identifiant et émet. **Un compte désactivé continue de
renouveler sa session indéfiniment** — il perd seulement la possibilité de se
reconnecter, ce qu'il n'a aucune raison de faire. Corrigé avec le reste.

### 3.3 `/auth/refresh` n'a pas de test

Ni de test unitaire, ni de test d'intégration : `AuthServiceTest` ne fait que
poser un `when(...generateRefreshToken...)`, et `AuthFlowIntegrationTest`
vérifie seulement que le champ n'est pas vide. La route la plus critique du
parcours — celle qui décide si vos utilisateurs restent connectés — n'était
couverte par rien. C'est aussi pour cela que 3.1 et 3.2 ont pu s'installer.

---

## 4. Demande 3 — les durées : accordée, et le relevé exact en attendant

Confirmé, `AuthResponse` porte cinq champs et aucune durée. Voici les valeurs de
production, tirées non pas d'une mesure mais de la source :

```properties
# src/main/resources/application.properties, lignes 128-129
jwt.access-token-expiry-ms=900000
jwt.refresh-token-expiry-ms=2592000000
```

**Deux précisions qui valent mieux qu'un chiffre.** Ces lignes ne sont pas
`${VAR:défaut}` : elles ne sont pas surchargeables par variable d'environnement,
et aucun des profils `prod`, `railway` ni `staging` ne les redéfinit. La
production applique donc **exactement 900 s et 2 592 000 s**, et le seul moyen de
les changer est un déploiement — ce qui rend votre demande 3 d'autant plus
raisonnable : le jour où nous les changerons, c'est un commit chez nous et rien
chez vous.

**Vos deux constantes Dart sont donc justes aujourd'hui.** Nous ajoutons
`expiresIn` et `refreshExpiresIn`, en **secondes relatives** comme vous le
demandez et pour la raison que vous donnez — l'horloge du téléphone n'est pas
une référence —, sur `/auth/login`, `/auth/register` et `/auth/refresh`. Deux
champs ajoutés à un record existant : additif, aucune rupture.

---

## 5. Demande 4 — le limiteur : la route n'est pas limitée, et notre contrat ment

**`/auth/refresh` n'appelle aucun limiteur.** `AuthController.refresh()`
(ligne 71) fait deux choses : valider le corps, appeler le service. À comparer
avec ses voisines, qui ouvrent toutes par un `rateLimiter.check...`.

Et `RateLimiter` n'expose que quatre portes : `checkLogin`, `checkRegister`,
`checkResendVerification`, `checkPasswordReset`. Il n'y a pas de
`checkRefresh`, pas de filtre global, pas d'intercepteur. **Le 429 que vous
décrivez au §4 ne peut pas être émis par cette route.**

D'où venait votre information ? De nous. `OpenApiConfig` (ligne 56) publie dans
la description de l'API :

```
## Rate Limiting
- Recherche: 20 req/min
- Upload: 10 req/min
- Auth: 5-10 req/min
```

C'est une chaîne de caractères écrite à la main. Elle ne décrit **aucun** des
trois plafonds correctement, et vous avez eu parfaitement raison de la lire
comme un contrat — c'est ce qu'un `/v3/api-docs` est censé être.
**Nous la corrigeons pour qu'elle dise ce que le code fait**, ou nous la
retirons : une ligne fausse à cet endroit est pire qu'une ligne absente.

**Sur le fond, votre demande 4 est accordée par avance.** Votre argument —
« celui qui appelle `/auth/refresh` présente déjà un secret valide » — est le
bon, et c'est celui qui explique que la route n'ait jamais été limitée. Si nous
en posons un un jour, ce sera un seuil propre, généreux, et non le seau
« auth ». Nous notons votre suggestion de ~30/min par jeton.

**Sur le `Retry-After` : accordé et nécessaire, indépendamment.** Aucun de nos
429 ne le sert aujourd'hui — `GlobalExceptionHandler.handleRateLimit` rend un
corps `ErrorResponse` et rien d'autre. Or nos vraies fenêtres sont longues
(15 minutes sur la connexion, **une heure** sur l'inscription et les envois
d'e-mail) : sans en-tête, un client qui réessaie « dans quelques minutes »,
comme notre message l'y invite, se fait refuser autant de fois. Nous l'ajoutons
sur tous les 429.

### La question annexe : par IP ou par compte ?

**Les deux, avec deux budgets différents, et pour exactement la raison que vous
soupçonniez.** Le raisonnement est écrit en tête de `RateLimiter` : une adresse
IP ne désigne pas une personne. Le budget serré est donc sur le **compte**, qui
est ce qu'une attaque par mot de passe vise ; l'adresse garde un plafond large,
qui n'existe que pour borner un balayage.

| Route | Par compte / adresse visée | Par IP | Fenêtre |
|---|---|---|---|
| `/auth/login` | 10 **échecs** | 50 **échecs** | 15 min, glissante |
| `/auth/register` | 5 | 30 | 1 h |
| `/auth/resend-verification` | 3 | 20 | 1 h |
| `/auth/forgot-password` | 3 | 20 | 1 h |
| `/auth/refresh` | — | — | — |

Deux propriétés que votre mesure du 22/08 ne pouvait pas voir, et qui rendent ce
limiteur beaucoup moins hostile que celui que vous avez mesuré :

- **Seuls les échecs comptent sur la connexion**, et une connexion réussie remet
  le compteur du compte à zéro. Se connecter cent fois de suite avec le bon mot
  de passe ne consomme rien.
- **La fenêtre glisse vraiment.** Votre « ~8 tentatives puis blocage 15 min »
  décrit l'ancien compteur, qui était un entier ne redescendant jamais : le
  budget était en réalité épuisé pour la durée de vie du processus. C'est
  corrigé depuis le 1er septembre, et le couple adresse+compte a été propagé aux
  quatre routes le 07/09. **Votre mesure du 22/08 est périmée.**

---

## 6. Demande 5 — les codes 401 : accordée, mais vous pouvez agir dès aujourd'hui

**D'abord un fait qui vous débloque tout de suite.** Il n'existe que **deux**
sources de 401 dans tout `src/main/java`, et aucune n'émet un corps vide :

| D'où | Code | Quand |
|---|---|---|
| `SecurityConfig.authenticationEntryPoint` | `UNAUTHORIZED` | route protégée, jeton absent / illisible / **expiré** |
| `GlobalExceptionHandler` | `INVALID_TOKEN` | `/auth/refresh`, `/auth/reset-password` — un jeton *d'e-mail* ou de rafraîchissement refusé |
| `GlobalExceptionHandler` | `INVALID_CREDENTIALS` | `/auth/login` — mot de passe faux |

**Et surtout : un refus de droit n'est pas un 401 ici, c'est un 403.**
`ForbiddenException` rend `HttpStatus.FORBIDDEN`, et les refus de
`@EnableMethodSecurity` aussi. Le cas que vous décrivez au §5 — « ce jeton est
valide mais ce compte n'a pas le droit d'aller là » — ne vous arrive donc
jamais en 401.

Ce qui signifie que **votre liste codée en dur peut disparaître maintenant**,
sans rien attendre de nous : les trois routes qu'elle nomme
(`/auth/verify-email`, `/auth/login`, `/auth/reset-password`) sont exactement
celles qui rendent `INVALID_TOKEN` ou `INVALID_CREDENTIALS`. La règle « ne
rafraîchir que si `code == "UNAUTHORIZED"` » les couvre toutes les trois, et
couvrira aussi celles que nous ajouterons — elle ne vieillira pas, là où une
liste de chemins vieillit à chaque route nouvelle.

**Le « 401 nu » que vous observez ne vient pas de ce code.** Nous n'avons pas de
chemin qui produise un 401 sans corps. Les candidats sont l'arête Railway (502
et 401 d'infrastructure), ou un préflux CORS. **Envoyez-nous un `X-Request-Id`**
sur une occurrence : nous le posons en écho sur chaque réponse
(`RequestIdFilter`) et il est exposé au client, ce qui nous permettra de dire en
une minute si la requête nous a seulement atteints.

**La demande elle-même est accordée.** `UNAUTHORIZED` confond aujourd'hui « pas
de jeton », « jeton illisible » et « jeton expiré » — et c'est le troisième qui
vous intéresse, parce que c'est le seul qui vaille un rafraîchissement
silencieux. Nous ajouterons **`TOKEN_EXPIRED`**, distinct, à l'entrée
d'authentification. C'est un code nouveau, donc additif : `ErrorCode` s'engage à
ne jamais renommer un code publié, et `UNAUTHORIZED` continuera de désigner les
deux autres cas.

---

## 7. Le corps de `/auth/logout`

Vous demandez si `{"refreshToken": "…"}` est attendu. **Non.** La signature est
`logout(HttpServletRequest)` : il n'y a pas de `@RequestBody`, et Spring ignore
purement et simplement ce que vous envoyez. Le schéma ne le publie pas parce
qu'il n'existe pas.

Vous pouvez donc cesser de l'envoyer — **mais gardez-le encore.** C'est
exactement le corps dont nous aurons besoin le jour où `logout` révoquera
réellement quelque chose (§2), et il sera alors déclaré au contrat. Le retirer
maintenant pour le remettre ensuite serait deux changements pour rien.

---

## 8. Ce qui est livré, et ce que vous pouvez adopter

Tout ce qui suit est dans le même lot, suite complète au vert (1307 tests).

**Les deux durées.** `AuthResponse` porte `expiresIn` et `refreshExpiresIn`, en
secondes, sur `/auth/login`, `/auth/register` et `/auth/refresh`. Additif :
aucun champ existant n'a bougé. Vous pouvez retirer vos deux constantes Dart.

**Les deux jetons cessent d'être interchangeables.** Le claim `type` est lu, dans
les deux sens (§3.1). Concrètement, pour vous : un `refreshToken` envoyé par
mégarde en `Authorization: Bearer` était accepté et ne l'est plus. Nous ne
pensons pas que votre client le fasse — mais si un chemin le faisait, il
fonctionnait silencieusement jusqu'ici et rendra désormais un `401 UNAUTHORIZED`.
C'est le seul changement de ce lot qui puisse casser quelque chose chez vous, et
c'est pour cela que nous le nommons en premier.

**`TOKEN_EXPIRED`.** Sur les routes protégées, un jeton d'accès simplement périmé
rend maintenant `{"code":"TOKEN_EXPIRED"}` — c'est celui-là, et lui seul, qui vaut
un rafraîchissement silencieux. `UNAUTHORIZED` garde son nom et couvre le reste :
jeton absent, illisible, mal signé, ou du mauvais genre. Un client qui ignore le
nouveau code se comporte exactement comme avant.

*Au passage :* le message de ce 401 partait jusqu'ici dans l'encodage par défaut
de la spécification servlet, l'ISO-8859-1. Aucun message d'alors ne portait
d'accent, si bien que rien ne l'avait révélé ; « Le jeton d'accès a expiré. » en
porte deux. C'est corrigé, et tenu par un test.

**`Retry-After` sur tous les `429`.** En secondes, arrondi vers le haut, jamais
zéro. Ce n'est pas une constante : c'est l'instant où la plus ancienne tentative
retenue sort de la fenêtre glissante, pour la clé qui a effectivement refusé.
Deux appelants refusés à la même seconde n'attendent donc pas la même chose.

**Un compte désactivé ne renouvelle plus.** Et, avec lui, **un changement de code
de statut que nous vous devons** : un jeton de rafraîchissement dont le compte a
disparu rendait un `404`, il rend désormais `401 INVALID_TOKEN`. C'est
volontaire, et c'est votre propre règle qui l'impose — vous ne fermez une session
que sur 401 ou 403, si bien qu'un compte effacé (la suppression RGPD efface
réellement la ligne) vous laissait réessayer sans fin avec un jeton que rien ne
ranimera. « Cette session est finie » est exactement ce qu'il fallait vous dire.

**Notre `/v3/api-docs` disait faux, il est réécrit.** Le bloc « Rate Limiting »
donne les budgets réels, route par route, et dit explicitement que
`/auth/refresh` n'est pas limité. La section « Authentification » porte désormais
la garantie de rotation glissante, les deux durées, et ce qui ferme une session —
pour que la question de votre §2 n'ait plus à être posée.

**Et les tests qui manquaient.** `/auth/refresh` en a maintenant, unitaires et de
bout en bout : rotation glissante prouvée sur l'écart émission/échéance du jeton
rendu, refus croisés des deux genres de jetons, compte désactivé, présence des
durées, et les codes de chaque 401.

---

## Récapitulatif

| # | Votre demande | Réponse | Livré ? |
|---|---|---|---|
| 1 | La rotation est-elle glissante ? | **Oui, hypothèse A.** Aucune échéance héritée n'est lisible dans ce code | Rien à faire |
| 2 | Fenêtre en inactivité, pas en ancienneté | Vrai par effet de bord, **non garanti** : nous ne mesurons pas l'inactivité. Suppose de persister les sessions | Non — §2 |
| 3 | `expiresIn` / `refreshExpiresIn` | **Accordée.** 900 et 2 592 000, désormais dans chaque réponse | **Livré** |
| 4 | Sortir `/auth/refresh` du limiteur + `Retry-After` | **Sans objet** : la route n'est limitée par rien. `Retry-After` sur tous les 429, et le contrat corrigé | **Livré** |
| 5 | Un `code` stable sur les 401 | **Accordée** : `TOKEN_EXPIRED` est servi. Votre liste de routes peut disparaître | **Livré** |

Et ce que nous ajoutons de notre côté, par ordre de gravité :

| Trouvé | Gravité | État |
|---|---|---|
| Un jeton de rafraîchissement ouvrait toutes les routes protégées | **Haute** — 30 jours là où 15 minutes étaient prévues | **Corrigé** |
| Un compte désactivé continuait de renouveler sa session | Moyenne | **Corrigé** |
| Un jeton d'accès était accepté à `/auth/refresh` | Faible | **Corrigé** |
| `/auth/refresh` n'avait aucun test | — | **Corrigé** |
| `/auth/logout` ne révoque rien, et rien n'est révocable | Haute | **Ouvert** — chantier du §2 |

La seule ligne encore ouverte est la révocation, et c'est la même que votre
demande 2 : les deux attendent que les sessions soient persistées. Nous ne la
livrons pas dans ce lot et nous ne vous laissons pas croire qu'elle l'est.

**Rien de tout cela n'exige un changement chez vous**, à une réserve près, celle
du §8 : un `refreshToken` envoyé en `Bearer` ne passe plus. Votre correctif du
§1 est le bon, et il le reste — nous ne fermerons jamais une session autrement
que par un refus authentifié.
