# Votre règle inversée est la bonne, et voici ce qui la rend sûre

**Date :** 2026-09-12
**Module :** [`session/`](.)
**En réponse à :** [`SUITE_CLIENT_2026-09-11.md`](SUITE_CLIENT_2026-09-11.md)

> **Rien ne vous attend, et nous n'ouvrons rien.** Ce document ne porte aucune
> demande : il répond aux quatre points de votre suite où notre code décidait de
> quelque chose, et clôt une question restée en l'air.
>
> - **§1 — vous retournez notre règle du §6, et vous avez raison.** Nous l'avons
>   vérifiée contre l'ensemble de ce que notre API peut émettre, parce qu'elle
>   n'est sûre que si cet ensemble est clos. Il l'est : **quatre codes de 401, et
>   `INVALID_TOKEN` n'est jamais servi par une route protégée ordinaire.**
> - **§2 — `TOKEN_EXPIRED` ne demande aucun outillage pour être rejoué.** Quinze
>   minutes de patience suffisent ; vous n'avez pas à fabriquer de JWT.
> - **§3 — un compte disparu sur une route ordinaire rend `401`, pas `500`.**
>   Nous l'avons mesuré parce que nous attendions l'inverse.
> - **§4 — votre seuil de cinq secondes est le bon, et la branche qui le porte
>   est morte aujourd'hui.** Ce n'est pas une raison de la retirer.
> - **§5 — la révocation, sans date et sans promesse.**

---

## 1. Votre règle par exclusion est la bonne, et elle est sûre

Nous écrivions « ne rafraîchir que si `code == UNAUTHORIZED` ». Vous faites
l'inverse : rafraîchir **sauf** si le code est l'un de ceux qui ne parlent pas de
la session.

Votre raisonnement est meilleur que le nôtre, et nous adoptons le vôtre dans nos
propres documents. Nous avions écrit une règle par liste blanche sans regarder ce
qu'elle coûte quand elle se trompe ; vous l'avez fait. Un rafraîchissement de
trop coûte un appel. Un rafraîchissement manquant laisse quelqu'un devant une
application qui échoue sans pouvoir se réparer, et il n'y a pas d'écran qui
rattrape cela.

**Mais votre règle n'est sûre qu'à une condition**, que vous ne pouviez pas
vérifier de l'extérieur : il ne faut pas qu'un code exclu puisse être servi par
une route ordinaire, sans quoi l'exclusion étoufferait un rafraîchissement
légitime. Nous l'avons donc vérifié pour vous.

**L'ensemble des 401 de cette API est clos. Il y en a quatre, pas un de plus :**

| Code | D'où | Sur quelles routes |
|---|---|---|
| `UNAUTHORIZED` | point d'entrée d'authentification | toute route protégée |
| `TOKEN_EXPIRED` | point d'entrée d'authentification | toute route protégée |
| `INVALID_TOKEN` | `GlobalExceptionHandler` | `/auth/refresh`, `/auth/reset-password`, vérification d'e-mail |
| `INVALID_CREDENTIALS` | `GlobalExceptionHandler` | `/auth/login`, **et `POST /users/me/change-password`** |

**Et `INVALID_TOKEN` n'est jamais émis par une route protégée ordinaire.**
`InvalidTokenException` n'est construite qu'en cinq endroits, tous dans le
domaine `auth` : deux dans la vérification d'e-mail, deux dans `/auth/refresh`,
un dans `/auth/reset-password`. Aucune route de contenu ne peut la lever. Votre
exclusion ne peut donc pas supprimer un rafraîchissement dont vous auriez eu
besoin — c'était le seul risque réel de l'inversion, et il n'existe pas.

**Cette dernière ligne mérite que vous la lisiez deux fois : c'est le cas de
votre §5, et il est réel.** `POST /api/users/me/change-password` est une route
protégée, et elle rend `401 INVALID_CREDENTIALS` quand le mot de passe *actuel*
fourni est faux. Un 401 sur une route authentifiée qui ne parle pas du tout de la
session — exactement ce que votre prompt décrivait comme indiscernable.

Et voici ce que cela dit de votre migration : **votre ancienne liste de chemins
ne couvrait pas cette route.** Elle nommait `/auth/verify-email`, `/auth/login`
et `/auth/reset-password`. Quelqu'un qui se trompait de mot de passe actuel en
changeant son mot de passe déclenchait donc un rafraîchissement chez vous, pour
rien. Votre liste de codes le corrige sans que vous ayez eu à le découvrir —
c'est précisément la faiblesse que vous nommiez en écrivant qu'une liste de
chemins « vieillira mal ».

*Nous notons au passage que `401` est discutable pour ce refus — un mot de passe
actuel erroné n'est pas un défaut d'authentification de la requête, qui est
parfaitement authentifiée. Nous ne le changeons pas : vous branchez désormais
dessus, et le changer casserait ce que nous venons de fiabiliser. Il est mieux
nommé qu'il n'est classé.*

Les quatre chemins terminent, en un appel perdu au maximum :

- `UNAUTHORIZED` → vous rafraîchissez. Soit la session repart, soit
  `/auth/refresh` rend `INVALID_TOKEN`, qui est exclu : vous fermez.
- `TOKEN_EXPIRED` → même chose, et c'est le cas où elle repart.
- `INVALID_TOKEN`, `INVALID_CREDENTIALS` → exclus, vous ne rafraîchissez pas.
  Y compris le mot de passe actuel erroné ci-dessus, qui est le seul des
  quatre à pouvoir tomber sur une route protégée sans rien devoir à la session.

**Un engagement, puisque vous bâtissez dessus :** ajouter un code est additif
chez nous et le restera — `ErrorCode` s'interdit d'en renommer un publié. Si nous
devions un jour servir un nouveau code de 401, il tomberait par défaut du côté
qui rafraîchit, c'est-à-dire du côté qui répare. C'est la bonne moitié.

---

## 2. `TOKEN_EXPIRED` : quinze minutes suffisent

Vous notez l'avoir relevé au contrat sans le rejouer, faute de pouvoir fabriquer
un JWT expiré signé par notre clé — et vous en concluez, à juste titre, qu'il ne
décide rien chez vous aujourd'hui.

**Vous n'avez rien à fabriquer.** Prenez un `accessToken` sur le compte de test,
ne vous en servez pas pendant quinze minutes, puis appelez n'importe quelle route
protégée avec. C'est exactement le chemin qu'il emprunte en production, et le
seul que nous ayons nous-mêmes éprouvé — notre suite le couvre en forgeant un
jeton déjà périmé avec la clé du profil de test, ce qui n'est possible que de
l'intérieur.

Attention à un détail, sans quoi vous obtiendrez `UNAUTHORIZED` et croirez à un
défaut : c'est bien un **jeton d'accès** qu'il faut laisser vieillir. Un jeton de
rafraîchissement périmé rendrait `TOKEN_EXPIRED` lui aussi — l'échéance est
regardée avant le genre — mais il n'y a aucune raison d'en arriver là.

Tant que vous ne l'aurez pas vu passer, votre garde fonctionne sans lui, et
c'est très bien ainsi : elle est construite par exclusion, donc un code qu'elle
ne connaît pas la fait rafraîchir, ce qui est le comportement voulu.

---

## 3. Un compte disparu sur une route ordinaire : `401`, et nous attendions `500`

Votre règle enchaîne un rafraîchissement sur tout 401 non exclu. Encore
faut-il que le cas « ce compte n'existe plus » **soit** un 401 — s'il rendait un
`500`, vous n'auriez pas de chemin de sortie et l'application échouerait sans
jamais se réparer.

Nous avons regardé, et nous nous attendions à une mauvaise nouvelle :
`loadUserById` lève une exception pour un compte désactivé comme pour un compte
effacé, et `JwtAuthFilter` s'exécute avant le filtre de Spring Security qui
traduit ces exceptions — de quoi produire un `500`.

**Relevé, c'est un `401 UNAUTHORIZED`.** Spring Security rattrape bien
l'exception : elle est de la famille qu'il traduit. Mesuré sur la chaîne de
filtres réelle, en test d'intégration, avec un compte désactivé après émission de
son jeton ; un compte effacé suit la même ligne de code.

Votre règle s'y comporte exactement comme il faut : un rafraîchissement, puis
`INVALID_TOKEN` sur `/auth/refresh` — nous l'y avons rendu volontairement, c'est
le changement de statut annoncé au §8 de notre réponse précédente — donc une
déconnexion propre. Un appel dépensé, et l'utilisateur revient à l'écran de
connexion au lieu de rester coincé.

---

## 4. `Retry-After` : votre seuil est le bon, et la branche est morte aujourd'hui

Cinq secondes pour attendre, au-delà on renonce : c'est le bon partage, et il
tombe du bon côté par construction. Nos fenêtres sont de quinze minutes et d'une
heure, si bien qu'un `Retry-After` servi par le limiteur sera **toujours** très
au-dessus de votre seuil. Votre règle revient donc à « renoncer », ce qui est
précisément ce qu'il faut faire contre un plafond qui tient une heure.

**Sachez seulement où cette branche s'exécute, ou plutôt où elle ne s'exécute
pas.** Vous lisez l'en-tête dans les reprises du rafraîchissement — et
`/auth/refresh` n'est pas limitée. Aucun 429 de notre limiteur ne peut donc
l'atteindre : la branche est morte, au sens strict, pour tout ce qui vient de
notre code.

Nous vous conseillons de la garder quand même, pour deux raisons. Un 429 peut
vous parvenir sans venir de nous — l'arête Railway est devant nous et a ses
propres refus. Et si nous devions un jour poser un plafond sur cette route, nous
le ferions généreux et assorti d'un `Retry-After`, exactement ce que votre code
sait déjà lire ; le retirer aujourd'hui pour le remettre alors serait deux
changements pour rien, ce que vous dites vous-même du corps de `/auth/logout`.

Sur le fait de ne lire que la forme « nombre de secondes » : c'est la seule que
nous servons, et nous nous engageons à ne pas servir l'autre. Une date HTTP
absolue se heurterait à l'horloge de l'appareil, ce dont ce module a assez
souffert.

---

## 5. La révocation : sans date, et sans promesse

Vous tenez la note plutôt que d'en faire une demande, et vous avez raison de
distinguer les deux. Nous ne vous donnerons pas de date, parce que nous n'en
avons pas.

L'état exact : `AuthService.logout()` est un corps de méthode vide, les jetons de
rafraîchissement ne sont persistés nulle part, et rien n'est donc révocable —
ni à la déconnexion, ni au changement de mot de passe, ni à la désactivation d'un
compte. Ce que nous avons livré ce mois-ci ne touche pas à cela d'un millimètre.

Ce qu'il faudra, quand ce sera fait : une ligne par session, portant le compte,
une empreinte du jeton, sa dernière utilisation, et une éventuelle révocation.
C'est la même ligne qui rendra possibles la rotation réelle, un `logout` qui
déconnecte, votre fenêtre comptée en inactivité, et l'écran « mes appareils » le
jour où vous le voudrez — vous l'avez vu, les trois sont le même chantier.

Gardez le corps `{"refreshToken": …}` sur `/auth/logout`. Il est ignoré
aujourd'hui, il sera exactement ce qu'il faut alors.

---

## 6. Le « 401 nu » : nous clôturons

Notre réponse précédente vous demandait un `X-Request-Id` sur une occurrence de
401 sans corps, n'ayant aucun chemin qui en produise. Votre suite n'en parle
plus, et votre relevé du 10/09 montre des corps partout.

Nous le considérons donc comme non reproduit et nous refermons la question. Si
elle revient, un seul identifiant suffira : nous le posons en écho sur chaque
réponse, et il dira en une minute si la requête nous a seulement atteints.

---

## Récapitulatif

| Ce que vous avez fait | Ce que nous en disons |
|---|---|
| Règle des 401 par exclusion | **La bonne.** Vérifiée sûre : quatre codes, ensemble clos. Et elle referme un défaut que votre ancienne liste laissait ouvert sur `/users/me/change-password` |
| `TOKEN_EXPIRED` non rejoué | Quinze minutes sur un jeton d'accès suffisent — aucun outillage |
| `Retry-After` ≤ 5 s | Bon seuil ; la branche ne peut pas s'exécuter sur nos 429, gardez-la quand même |
| Durées serveur avec repli | La bonne façon : une app en vol pendant un déploiement doit continuer |
| Note tenue sur `logout` | Exact, et toujours ouvert chez nous. Sans date |

**Nous n'attendons rien de vous.** Le seul point où nous pourrions vous
surprendre à l'avenir est un nouveau code de 401 ; il tomberait du côté qui
rafraîchit, et nous vous le dirions avant.
