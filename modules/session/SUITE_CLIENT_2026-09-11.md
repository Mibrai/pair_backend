# Ce que leur réponse change chez nous

**Date :** 2026-09-11 (l'échange a eu lieu dans la nuit du 10)
**Module :** [`session/`](.)
**En réponse à :** [`REPONSE_BACKEND_2026-09-10.md`](REPONSE_BACKEND_2026-09-10.md)

> **Trois adoptions, livrées.** Les durées viennent désormais du serveur, la
> garde des 401 s'appuie sur des codes au lieu d'une liste de chemins, et un
> `Retry-After` court est honoré. 3 775 tests verts (3 766 avant), dont 9
> nouveaux.
>
> **Un point de rupture annoncé, vérifié nul.** Leur durcissement refuse un
> `refreshToken` présenté en `Authorization: Bearer`. Aucun chemin de l'app
> n'en envoyait — §1.
>
> **Une chose qu'on croyait vraie et qui ne l'est pas**, et elle nous concerne
> plus qu'eux : `POST /auth/logout` ne révoque rien. Se déconnecter d'un
> téléphone prêté y laisse un jeton actif pour trente jours — §5.
>
> **Deux relevés qui rendent périmées deux de nos notes** — §6.

---

## 1. Le seul risque de rupture : vérifié, il est nul

Leur §8 nomme un changement qui pouvait nous casser : un `refreshToken` envoyé
en `Authorization: Bearer` était accepté et ne l'est plus.

Recherche exhaustive des porteurs d'`Authorization` dans `lib/` :

| Endroit | Source du jeton |
|---|---|
| `api_client.dart:408` — `AuthInterceptor.onRequest` | `getAccessToken()` |
| `api_client.dart:525,537` — les rejeux après rafraîchissement | `newTokens.accessToken` |
| `user_avatar.dart:27` — `authedMediaHeadersProvider` | `getAccessToken()` |

Le jeton de rafraîchissement ne quitte le trousseau qu'à deux endroits, et les
deux sont des **corps de requête** : `{"refreshToken": …}` vers `/auth/refresh`
et vers `/auth/logout`. Aucun WebSocket n'est branché (`AppConfig.wsBaseUrl`
n'est référencé nulle part). **Rien à corriger.**

---

## 2. Les durées viennent du serveur

`AuthResponse` porte `expiresIn` et `refreshExpiresIn` — vérifié sur
`/v3/api-docs` le 10/09 à 23 h 50, les deux champs sont bien déployés
(`integer/int64`).

Ce qui change chez nous :

- `AuthResponse` (`lib/models/auth_models.dart`) expose `accessTokenTtl` et
  `refreshTokenTtl`, lues par `dureeDepuisSecondes()` — tolérante par
  construction : une valeur absente, nulle, négative ou textuelle rend `null`.
  Ce n'est pas de la prudence gratuite. Ce champ décide du moment où l'app
  renouvelle sa session : une durée nulle produirait une échéance déjà dépassée,
  donc un rafraîchissement d'avance **à chaque requête**.
- `SecureStorageService.saveTokens` prend `accessTtl` / `refreshTtl`
  facultatives. Ses deux constantes deviennent des **replis** — pour une réponse
  d'un serveur pas encore déployé, ou une session restaurée d'avant la bascule.
  Nous ne les supprimons pas : une app en vol au moment d'un déploiement doit
  continuer de fonctionner.
- Les deux chemins qui rangent des jetons les transmettent :
  `AuthNotifier._persistSession` (connexion, inscription) et
  `AuthInterceptor._rangerLesJetons` (rafraîchissement — le plus important des
  deux, puisque c'est lui qui repousse l'échéance).

Nous gardons donc `900` et `2 592 000` écrits en dur, mais **plus personne ne
s'en sert quand le serveur parle**. C'est exactement ce que nous demandions au
§3 du prompt.

---

## 3. La garde des 401 s'appuie sur des codes

Nous avions une liste de trois chemins (`/auth/verify-email`, `/auth/login`,
`/auth/reset-password`) dont un 401 ne devait pas déclencher de
rafraîchissement. Leur §6 note qu'elle peut disparaître. Nous l'avons remplacée
par une liste de **codes**, après les avoir tous rejoués sur la production le
10/09 :

```
GET  /auth/verify-email?token=bidon    → 401 INVALID_TOKEN
POST /auth/reset-password  (bidon)     → 401 INVALID_TOKEN
POST /auth/login           (faux mdp)  → 401 INVALID_CREDENTIALS
GET  /users/me             (sans jeton)→ 401 UNAUTHORIZED
POST /auth/refresh         (bidon)     → 401 INVALID_TOKEN
POST /auth/refresh         (corps vide)→ 400 VALIDATION_ERROR
```

Les trois routes de l'ancienne liste sont couvertes, et celles qui s'ajouteront
le seront aussi.

**Une nuance que nous ne prenons pas comme ils l'écrivent.** Leur §6 propose la
règle « ne rafraîchir que si `code == UNAUTHORIZED` ». Nous faisons l'inverse :
nous rafraîchissons **sauf** si le code est explicitement l'un de ceux qui ne
parlent pas de la session. La différence ne se voit que sur un 401 dont le code
est absent ou inconnu — et là, l'arbitrage n'est pas symétrique : un
rafraîchissement de trop coûte un appel, un rafraîchissement manquant laisse
l'utilisateur devant une app qui échoue sans pouvoir se réparer. Ils écrivent
eux-mêmes n'avoir aucun chemin qui produise un 401 nu ; c'est une raison de plus
pour que ce cas retombe du côté qui répare. Un code inconnu est journalisé
(`auth.401.code_inconnu`) plutôt que subi en silence.

**`TOKEN_EXPIRED` : relevé au contrat, pas rejoué.** Il est documenté dans la
description déployée de l'API, mais le provoquer demanderait un JWT expiré signé
par leur clé, que nous ne pouvons pas fabriquer. Il ne décide donc **rien** chez
nous : notre garde fonctionne par exclusion, et si ce code n'était jamais servi,
rien ne changerait de comportement. Nous le noterons vérifié le jour où un
jeton expirera pour de bon sur le compte de test.

---

## 4. `Retry-After` : honoré court, abandonné long

Ils le servent désormais sur tous les 429, calculé sur la fenêtre glissante
réelle. Nous le lisons dans les reprises du rafraîchissement :

- **≤ 5 s** — c'est l'ordre de grandeur d'un redémarrage de conteneur : on
  attend ce que le serveur demande, plutôt que nos 400 ms arbitraires ;
- **> 5 s** — on renonce immédiatement. Leurs fenêtres vont jusqu'à une heure :
  insister trois fois contre un plafond qui tient une heure ne fait que trois
  refus de plus, et faire patienter un écran sur un rafraîchissement qu'on sait
  perdu est pire que de lui rendre la main.

Seule la forme « nombre de secondes » est lue, pas la date HTTP que la RFC
autorise aussi : c'est celle qu'ils servent, et une date absolue se heurterait à
l'horloge du téléphone — dont ce module a précisément appris à se méfier.

**Ce que nous n'avons pas fait :** afficher « réessaie dans X minutes ». Cela
demanderait de porter le délai jusqu'à l'écran (un champ sur `ApiException`,
donc trois fragments l10n et une traversée de tout `resolveApiErrorMessage`),
alors que leur message de refus est déjà traduit par leurs soins via
`Accept-Language` et s'affiche tel quel. À rouvrir si le message serveur se
révèle trop vague à l'usage.

---

## 5. Ce qu'il faut savoir et qui n'est pas corrigé : `logout` ne révoque rien

Leur §2 est la partie de leur réponse qui nous concerne le plus, et ce n'est pas
une de nos demandes.

Nous écrivions au §6 du prompt « `/auth/logout` fait ce qu'il faut ». C'est
faux : `AuthService.logout()` est un corps de méthode vide. Les jetons ne sont
persistés nulle part, donc rien n'est révocable — ni à la déconnexion, ni au
changement de mot de passe, ni à la désactivation d'un compte. **Quelqu'un qui
se déconnecte sur un téléphone prêté y laisse un jeton de rafraîchissement
valable trente jours.**

Ce que cela change chez nous, concrètement : **rien à écrire, mais une note à
tenir.** Notre `logout` fait déjà ce qu'il peut faire seul — il détache le jeton
de push *avant* d'effacer le trousseau (`auth_providers.dart:105`, le
commentaire y explique pourquoi l'ordre compte), puis vide les clés de session,
puis remet l'état à « déconnecté ». Localement, l'appareil est propre. Ce qui
manque est côté serveur, et eux seuls peuvent le livrer.

Nous appelons toujours `POST /auth/logout` avec `{"refreshToken": …}`, bien que
Spring ignore ce corps : ils demandent de le garder, puisque c'est exactement
celui dont ils auront besoin le jour où la route révoquera quelque chose. Le
retirer pour le remettre ensuite serait deux changements pour rien.

**À ne pas oublier si nous ouvrons un jour l'écran « mes appareils connectés » :**
il attend le même chantier de persistance des sessions que la révocation, et que
notre demande 2 sur l'inactivité. Les trois sont la même ligne.

---

## 6. Deux de nos notes sont périmées

**Le limiteur.** Notre mesure du 22/08 (« ~8 tentatives puis blocage 15 min »)
décrivait un compteur qui ne redescendait jamais — le budget était en réalité
épuisé pour la durée de vie du processus. Corrigé chez eux le 01/09, et le
couple adresse+compte propagé aux quatre routes le 07/09. Ce qui vaut
aujourd'hui :

| Route | Par compte / adresse visée | Par IP | Fenêtre |
|---|---|---|---|
| `/auth/login` | 10 **échecs** | 50 **échecs** | 15 min, glissante |
| `/auth/register` | 5 | 30 | 1 h |
| `/auth/resend-verification` | 3 | 20 | 1 h |
| `/auth/forgot-password` | 3 | 20 | 1 h |
| `/auth/refresh` | — | — | **pas limitée** |

Seuls les **échecs** comptent sur la connexion, et une connexion réussie remet le
compteur du compte à zéro : se connecter cent fois avec le bon mot de passe ne
consomme rien.

**La « rotation ».** Notre `ETAT_IMPLEMENTATION_PRODUCTION_2026-08-25.md` dit
« 30 jours avec rotation ». Le second membre est faux : il y a **réémission**,
pas rotation. L'ancien jeton reste valable jusqu'à sa propre échéance. Ne pas
bâtir sur une garantie de révocation qui n'existe pas — c'est écrit dans le
code, sur `SecureStorageService.refreshTokenLifetime`.

En revanche l'essentiel tient : **la rotation est glissante**, le jeton rendu
vaut sa durée pleine à compter de son émission. Une session utilisée au moins
une fois par mois ne finit jamais. C'était la question du §2 de notre prompt, et
c'est celle qui décidait du symptôme signalé.

---

## Récapitulatif

| Ce qu'ils ont livré | Ce que nous en faisons | État |
|---|---|---|
| `expiresIn` / `refreshExpiresIn` | Lues et rangées ; nos constantes deviennent des replis | **Adopté** |
| `TOKEN_EXPIRED` | Garde par codes, par exclusion — ce code ne décide de rien chez nous | **Adopté**, non rejoué en HTTP |
| `Retry-After` sur les 429 | Honoré ≤ 5 s, abandon au-delà ; pas affiché | **Adopté** |
| Jetons non interchangeables | Rien à faire, vérifié : nous n'envoyions pas le refresh en `Bearer` | **Sans objet** |
| Compte supprimé → `401 INVALID_TOKEN` | Ferme la session, comme prévu par notre règle | **Sans objet** |
| `/v3/api-docs` réécrit | Nos deux notes périmées corrigées | **Pris en compte** |
| `logout` ne révoque rien | Note tenue, rien à écrire ; le corps est conservé | **Chez eux** |

**Rien ne nous attend plus côté serveur**, sauf la révocation — qui est leur
chantier du §2, et qui porte aussi notre demande 2 et l'écran « mes appareils »
du jour où nous le voudrons.
