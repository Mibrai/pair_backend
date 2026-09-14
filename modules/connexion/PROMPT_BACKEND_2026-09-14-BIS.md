# Le lien `/r/{token}` : la page tout de suite, l'AASA le jour de la sortie

**Date :** 2026-09-14
**Module :** `connexion`
**Fait suite à :** `REPONSE_BACKEND_2026-09-14.md`, §2.3 (Smart App Banner) et §5 (calendrier de l'AASA)
**Audit :** P-MS-17 (aucun jeton reçu par `meetdo://`)
**Décision de l'utilisateur, 14/09/2026 :**
**(a)** la page web `/r/{token}` et `/reset-password?token=` restent servies **dès maintenant**,
formulaire compris, pour qu'un lien ouvert sur un ordinateur ou sans l'app aboutisse ;
**(b)** les deux lignes de réinitialisation **sortent de l'AASA** et y reviennent **le jour de la
sortie App Store** de la version qui gère `/r/` ;
**(c)** une **Smart App Banner** est posée sur cette page.

> **Ce que fait l'app** (commit `b4b4c63`, fusionné dans `main` le 14/09) : elle reconnaît
> `https://lien.meetdo.fun/r/<jeton>` et `…/reset-password?token=<jeton>`, et ouvre « nouveau mot
> de passe » avec le jeton. Un jeton reçu par `meetdo://` est ignoré (P-MS-17). **Cette version
> n'est pas encore sur l'App Store** : la version installée chez les gens ne sait rien de `/r/`.
>
> **Ce qui vous appartient** : que le lien de l'e-mail ne tombe pas, pendant cette fenêtre, dans une
> app qui ne sait pas quoi en faire. Vous l'aviez proposé vous-mêmes (§5) : nous acceptons.

---

## 1. Relevé du 14/09/2026, 13 h 42 (heure de Paris)

**Le lot est déjà en production.** `GET /actuator/info` rend un `build.time` de
`2026-09-14T11:39:59Z`, soit 13 h 39 à Paris, l'heure du commit `04aeffb` de votre `master`.

```
GET https://lien.meetdo.fun/r/essai                       (13:42:10 CEST)
→ 200 text/html;charset=UTF-8
  cache-control: no-store
  referrer-policy: no-referrer
  <meta name="referrer" content="no-referrer">
  « Ce lien n'est pas reconnu » + <form method="post" action="/r">  (nouveau lien)
  aucune balise apple-itunes-app

GET https://lien.meetdo.fun/reset-password?token=essai     (13:42:10 CEST)
→ 200 text/html, mêmes en-têtes : plus de 401

GET https://lien.meetdo.fun/.well-known/apple-app-site-association   (13:42:11 CEST)
→ 200 application/json, cache-control: no-cache, no-store, max-age=0, must-revalidate
  components : /s/*, /p/*, /public/slots/*, /public/programs/*, /v/*,
               /r/*  (« Réinitialisation du mot de passe »),
               /reset-password + "?": {"token": "*"}  (« e-mails envoyés avant le 14/09/2026 »)
```

**Code, clone au commit `04aeffb`** :

- `AppLinksController.java:90-91` : les deux lignes de réinitialisation, dans le JSON littéral de
  l'AASA (`:84-91`).
- `ReinitialisationLinkController.java:84` (`GET /r/{token}`), `:90` (`GET /reset-password`),
  `:95` (`POST /r/{token}`), `:144` (`POST /r`, nouveau lien).
- `templates/reset-password.html:3-10` : l'en-tête porte `robots`, `referrer` et le titre ; aucune
  `apple-itunes-app`.
- `EmailService.java:287-291` : l'e-mail compose `baseUrl + "/r/" + token`.

**Conséquence** : depuis 13 h 39, tout iPhone qui a relu l'AASA (le CDN d'Apple le garde plusieurs
heures) ouvre **l'app installée**, version publiée comprise, sur le lien de l'e-mail. Cette version
s'ouvre sans rien faire, et la personne ne voit jamais le formulaire. C'est exactement la fenêtre
décrite à votre §5.

**L'identifiant App Store de meetDo** (`app-id` numérique d'App Store Connect) : cherché dans
`appstore/` et `ios/` du dépôt app le 14/09, **introuvable**. Seul l'identifiant d'app
`97727T64DH.com.meetdo.app` y figure (celui de l'AASA). Nous vous le transmettrons.

## 2. La demande

1. **La page reste servie telle quelle** : `GET /r/{token}`, `GET /reset-password?token=`, les deux
   formulaires (`POST /r/{token}` et `POST /r`), les en-têtes `no-referrer` / `no-store`. Rien à
   changer : c'est la décision (a), et elle est déjà vraie en production.
2. **Retirer dès maintenant de l'AASA** les deux lignes `AppLinksController.java:90-91` :
   `{ "/": "/r/*" }` et `{ "/": "/reset-password", "?": { "token": "*" } }`. Les cinq autres
   composants ne bougent pas. Pendant ce temps, le lien de l'e-mail s'ouvre dans Safari, sur la
   page web, **sur tous les appareils**.
3. **Remettre ces deux lignes le jour de la sortie App Store** de la version qui contient
   `b4b4c63`. **La date vous sera communiquée par l'app** ; d'ici là, ne les remettez pas sur la foi
   d'une autre livraison. Si vous préférez un interrupteur de configuration plutôt qu'un
   déploiement ce jour-là (une propriété `pair.aasa.reinitialisation`, par exemple), dites-le :
   c'est à vous.
4. **La Smart App Banner** dans `reset-password.html` :
   ```html
   <meta name="apple-itunes-app"
         content="app-id=<IDENTIFIANT À COMPLÉTER>, app-argument=https://lien.meetdo.fun/r/<jeton>">
   ```
   - `app-argument` est l'adresse **canonique** `https://lien.meetdo.fun/r/<jeton>`, y compris quand
     la page a été ouverte par `/reset-password?token=` ; jamais `meetdo://`.
   - Sur les pages d'échec (jeton inconnu, expiré, déjà servi), `app-argument` vaut
     `https://lien.meetdo.fun/r` sans jeton : l'app ouvre alors « mot de passe oublié ».
   - **Quand la poser** : l'identifiant manque encore. Nous proposons de la poser **le même jour que
     les lignes de l'AASA**. Safari propose « Ouvrir » à **toute** version installée, et une version
     antérieure à `b4b4c63` ignorerait l'argument, la même impasse que le §1. Si l'identifiant vous
     arrive avant, gardez-la derrière le même interrupteur que le point 3.
5. **Le repli `meetdo://` reste exclu** de la page et de la bannière (votre §2.3, notre P-MS-17).

## 3. Côté app, ensuite

- **Rien à coder pour le lien** : `deep_links.dart:474-503` reconnaît déjà les deux formes.
- **La bannière vous demandait notre confirmation** (§2.3) que l'app traite `app-argument` comme un
  lien entrant. Relevé en lecture le 14/09 : le greffon `app_links` 7.2.1
  (`AppLinksIosPlugin.swift:107-147`) envoie `application(_:open:options:)`, par où Safari remet
  `app-argument`, et `continue userActivity`, par où arrive un lien universel, dans **le même
  flux**. `_lienReinitialisation` (`deep_links.dart:487-488`) ne regarde que le schéma `https` et
  l'hôte `lien.meetdo.fun`, pas le canal. **Confirmé par la lecture du code, pas encore sur un
  appareil** : nous l'éprouverons à la sortie (§4).
- **Le jour de la sortie**, l'app vous écrit la date et l'identifiant App Store dans un
  `SUITE_CLIENT` de ce module.

## 4. Comment nous vérifierons

**Après le retrait (point 2)** :

```
curl -s https://lien.meetdo.fun/.well-known/apple-app-site-association | grep -cE '/r/\*|reset-password'   # 0
curl -si https://lien.meetdo.fun/r/essai | grep -iE '^HTTP|referrer-policy'                              # 200, no-referrer
```

puis, sur un iPhone où la version publiée est installée, le lien d'un « mot de passe oublié » réel
avec le compte de test s'ouvre **dans Safari**, et le formulaire web aboutit (connexion avec le
nouveau mot de passe dans l'app).

**Le jour de la sortie (points 3 et 4)** : les deux lignes relues dans l'AASA, la balise
`apple-itunes-app` relue sur `/r/essai` avec l'identifiant réel ; sur l'iPhone mis à jour, le lien
de l'e-mail ouvre l'app sur « nouveau mot de passe », et la bannière ouverte depuis Safari
(page déjà chargée) mène au même écran. Sur un poste de bureau, le formulaire web aboutit toujours.
