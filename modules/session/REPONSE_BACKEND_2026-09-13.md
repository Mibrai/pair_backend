# Se déconnecter déconnecte enfin — et personne n'est déconnecté par le déploiement

**Date :** 2026-09-13
**Module :** [`session/`](.)
**Suite de :** [`REPONSE_BACKEND_2026-09-10.md`](REPONSE_BACKEND_2026-09-10.md), §2 (« Ouvert : la révocation »)
**Fiches d'audit :** P-BS-03 (sessions révocables), P-BS-10 et P-BL-12 étape 3 (appareil détaché à la déconnexion)
**Décisions :** P-BS/D4 option B (rotation tolérante), P-BS/D5 option A (pas de plafond absolu)

> **En bref.**
>
> - **§1 — ce qui change pour une app déjà publiée : rien d'obligatoire.** Les jetons que
>   détient l'app 1.1.0+16 continuent de s'échanger ; le premier échange les convertit.
> - **§2 — la déconnexion révoque la session**, et accepte le jeton de l'appareil. Elle répond
>   désormais **`204`** au lieu de `200`, sans corps, dans tous les cas.
> - **§3 — la rotation est désormais réelle et tolérante** : une réponse de rafraîchissement
>   perdue ne déconnecte personne, un rejeu après usage du successeur ferme la session.
> - **§4 — mot de passe** : une réinitialisation ferme **toutes** les sessions ; un changement
>   ferme les **autres**, et l'appareil qui l'a fait reçoit **un `TOKEN_EXPIRED`** qu'il répare
>   par un rafraîchissement ordinaire.
> - **§5 — ce que nous vous suggérons**, sans rien exiger.

Ce document corrige le §2 du 10/09 : « se déconnecter ne déconnecte rien » n'est plus vrai.

---

## 1. Compatibilité : l'app publiée n'a rien à faire

- **Vos jetons en circulation restent valables.** Un jeton de rafraîchissement émis avant ce
  déploiement ne porte pas de session. Au premier `POST /auth/refresh`, le serveur en ouvre une et
  rend un couple neuf, persisté. Ce mécanisme d'adoption reste allumé au moins **30 jours** — la
  durée de vie maximale d'un ancien jeton —, et ne s'éteint que lorsque plus aucune adoption
  n'apparaît dans nos journaux pendant 7 jours.
- **Les codes 401 sont inchangés** : `TOKEN_EXPIRED` se répare par un rafraîchissement,
  `INVALID_TOKEN` renvoie à la connexion. Votre règle du 12/09 (rafraîchir sauf sur les codes
  qui ne parlent pas de session) tient telle quelle.
- **La durée des jetons ne change pas** : 15 minutes pour l'accès, 30 jours glissants pour le
  rafraîchissement, et **toujours aucun plafond absolu** — une session utilisée au moins une fois
  par mois ne finit jamais (D5 option A, le contrat du 10/09).

## 2. `POST /api/auth/logout`

| | Avant | Maintenant |
|---|---|---|
| Effet | aucun | la session du jeton de rafraîchissement présenté est **révoquée** |
| Corps | ignoré | `LogoutRequest { refreshToken?, deviceToken? }`, tout facultatif |
| Jeton d'accès | exigé | **plus exigé** : c'est le jeton de rafraîchissement qui prouve |
| Réponse | `200` | **`204`**, sans corps, **toujours** — jeton inconnu ou corps absent compris |

- Votre `auth_repository.dart` envoie déjà `{"refreshToken": …}` : il en profite sans changement.
  Si votre client traite la déconnexion en « tout 2xx », le passage de `200` à `204` ne change rien.
- **`deviceToken`** : si vous l'envoyez, le jeton de notification de l'appareil est détaché du
  compte, et plus aucune push ne part vers un téléphone déconnecté. Il n'est pris en compte que si
  le jeton de rafraîchissement désigne bien son propriétaire.

## 3. La rotation

Chaque échange émet un nouveau jeton de rafraîchissement et le relie à celui qu'il remplace.

- **Réponse perdue** (coupure au mauvais moment, le cas du 10/09) : votre client réessaie avec
  l'ancien jeton. Tant que le nouveau n'a **jamais servi**, l'ancien reste échangeable et rend un
  autre couple. **Personne n'est déconnecté.**
- **Rejeu** : si l'ancien jeton est présenté **après** que son successeur a servi, deux détenteurs
  existent. La **session entière** est révoquée, et les deux reçoivent `INVALID_TOKEN`.
- **Deux rafraîchissements simultanés** du même jeton se sérialisent côté serveur : les deux
  réussissent, la session n'est pas révoquée. Votre mutualisation (`api_client.dart`) reste la
  meilleure garantie.

## 4. Mot de passe

- **Réinitialisation** (`/auth/reset-password`) : toutes les sessions du compte sont révoquées, et
  tout jeton d'accès en cours est refusé dès la requête suivante.
- **Changement** (`/users/me/change-password`) : les **autres** appareils sont déconnectés.
  L'appareil qui a changé le mot de passe voit son jeton d'accès refusé **une fois**, en
  `TOKEN_EXPIRED` ; son rafraîchissement réussit, et le jeton qu'il reçoit vaut de nouveau.
  C'est le parcours que votre client suit déjà sur un `TOKEN_EXPIRED` : rien à coder.
- **Désactivation du compte** : toutes les sessions sont révoquées.

## 5. Suggestions, sans obligation

- Envoyer **`deviceToken`** avec la déconnexion (P-MS-04).
- **Ne jamais rejouer un jeton de rafraîchissement plus ancien que le dernier rangé** : un rejeu
  ferme la session. Une restauration de trousseau depuis une sauvegarde peut en produire un
  (P-MS-12) : la session échouera proprement en `INVALID_TOKEN`, et l'app renverra à la connexion.
- Un test du scénario « réponse de rafraîchissement perdue » côté client : le serveur le tolère,
  il vaut la peine de s'assurer que le client réessaie bien avec l'ancien jeton.

## Ce que nous n'avons pas fait

- **La liste des appareils connectés** (« vos sessions ») n'existe pas : vous l'aviez écartée.
  Les données le permettraient désormais.
- **Le WebSocket** authentifie encore par la seule signature du jeton d'accès, sans vérifier sa
  version : un jeton d'accès émis avant un changement de mot de passe y reste utilisable jusqu'à
  son échéance, un quart d'heure au plus.

## Vérification

Côté serveur, `SessionRevocationIntegrationTest` rejoue par l'API : déconnexion puis échange
refusé ; rejeu après usage du successeur ; réponse perdue ; deux échanges simultanés ;
réinitialisation et changement de mot de passe ; `204` sans jeton d'accès ; échéance glissante de
30 jours ; purge des sessions inactives ; appareil détaché à la déconnexion. `SessionServiceTest`
couvre l'adoption des anciens jetons, allumée et éteinte.
