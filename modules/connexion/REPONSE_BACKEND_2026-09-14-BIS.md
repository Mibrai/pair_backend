# Réponse du 14/09 (BIS) — `/r/*` sort de l'AASA, et un interrupteur l'y remettra le jour de la sortie, bannière comprise

**Date :** 2026-09-14
**Module :** `connexion`
**Fait suite à :** `PROMPT_BACKEND_2026-09-14-BIS.md`

> **Les quatre points sont livrés tels que vous les décrivez.** Nous avons retenu l'interrupteur de
> configuration que vous proposiez : le jour de la sortie App Store, **aucun déploiement de code**.
> Poser deux variables dans Railway suffira.
>
> - **(a)** La page `/r/{token}`, `/reset-password?token=` et les deux formulaires restent servis,
>   inchangés.
> - **(b)** `/r/*` et `/reset-password?token=*` **sortent de l'AASA** dès ce déploiement.
> - **(c)** La Smart App Banner est écrite, mais **ne s'affiche pas encore**. Elle attend le même
>   interrupteur **et** l'identifiant App Store.
>
> **Votre relevé du §1 est exact.** C'est le risque que notre réponse précédente annonçait à son §5,
> et nous l'avions laissé partir en production. La fenêtre a duré de 13 h 39 à ce déploiement.

---

## 1. L'interrupteur

Deux propriétés, lues depuis l'environnement :

| Variable Railway | Propriété | Défaut | Rôle |
|---|---|---|---|
| `REINITIALISATION_DANS_APP` | `meetdo.links.reinitialisation-dans-app` | `false` | ajoute les deux motifs à l'AASA, et autorise la bannière |
| `APP_STORE_ID` | `meetdo.links.app-store-id` | vide | identifiant numérique App Store Connect de meetDo |

**Le jour de la sortie** de la version qui contient `b4b4c63`, et **seulement sur votre date**, comme
vous le demandez : `REINITIALISATION_DANS_APP=true` et `APP_STORE_ID=<identifiant>`. Railway
redémarre le service quand une variable change. Comptez environ deux minutes de coupure, comme à
chaque déploiement, puis le temps que le CDN d'Apple relise le fichier.

**Retour arrière**, si la version publiée ne tient pas ce qu'elle promet : remettre
`REINITIALISATION_DANS_APP=false`. Les deux lignes et la bannière disparaissent ensemble.

## 2. L'AASA

**Interrupteur éteint**, c'est-à-dire l'état de ce déploiement : cinq composants, ceux d'avant le
14/09.

```json
"components": [ "/s/*", "/p/*", "/public/slots/*", "/public/programs/*", "/v/*" ]
```

**Interrupteur allumé** : les deux motifs reviennent en sixième et septième position, à l'identique
de ce que vous avez relevé à 13 h 42.

```json
{ "/": "/r/*", "comment": "Réinitialisation du mot de passe" },
{ "/": "/reset-password", "?": { "token": "*" }, "comment": "Réinitialisation, e-mails envoyés avant le 14/09/2026" }
```

Le fichier reste servi sans cache de notre côté. **Le CDN d'Apple, lui, garde sa copie plusieurs
heures** : un iPhone qui a relu la version à sept composants peut encore ouvrir l'app pendant ce
délai, après le retrait.

## 3. La Smart App Banner

Dans `reset-password.html`, `<meta name="apple-itunes-app">`, posée **seulement si** l'interrupteur
est allumé **et** `APP_STORE_ID` est un nombre. Une valeur provisoire comme « À COMPLÉTER » ne pose
rien.

| Page | `app-argument` |
|---|---|
| formulaire proposé, ouvert par `/r/<jeton>` **ou** `/reset-password?token=<jeton>` | `https://lien.meetdo.fun/r/<jeton>`, l'adresse canonique |
| formulaire renvoyé avec une erreur de saisie | `https://lien.meetdo.fun/r/<jeton>` |
| jeton inconnu, expiré, déjà servi ; nouveau lien demandé ; mot de passe changé ; trop de tentatives | `https://lien.meetdo.fun/r`, sans jeton |

L'hôte vient de `pair.public.base-url`, le même que celui des pages publiques. **Jamais
`meetdo://`** (P-MS-17).

## 4. Ce qui ne bouge pas

- Les routes, les formulaires, les en-têtes `Referrer-Policy: no-referrer` et `Cache-Control: no-store`.
- L'e-mail, qui compose toujours `https://lien.meetdo.fun/r/<jeton>`. Interrupteur éteint, ce lien
  s'ouvre dans Safari, sur la page web.
- `assetlinks.json` : toujours `404` tant que P-MS-08 n'a pas produit les empreintes. Le jour où
  elles arrivent, le filtre Android de `/r/` devra suivre le même calendrier que l'AASA : dites-le-nous
  si la version Android sort à une autre date que la version iOS.

## 5. Vérification

**Tests** :

- `AppLinksControllerTest` : interrupteur éteint, cinq composants et aucun motif de
  réinitialisation ; allumé, sept, avec `"?": {"token": "*"}`. Dans les deux cas, le JSON est relu
  par un analyseur.
- `BanniereReinitialisationTest` : aucune bannière interrupteur éteint, ni sans identifiant, ni avec
  un identifiant non numérique ; l'adresse canonique avec jeton sur le formulaire ; `/r` sans jeton
  sur une page d'échec ; jamais `meetdo://`.
- `ReinitialisationLienIntegrationTest` : en configuration par défaut, aucune `apple-itunes-app` sur
  la page, formulaire ou échec.
- `PublicSlotPageIntegrationTest` : l'AASA servi ne contient plus `/r/*` ni `reset-password`.

**Votre protocole du §4 tient tel quel** après ce déploiement :

```
curl -s https://lien.meetdo.fun/.well-known/apple-app-site-association | grep -cE '/r/\*|reset-password'   # 0
curl -si https://lien.meetdo.fun/r/essai | grep -iE '^HTTP|referrer-policy'                              # 200, no-referrer
```

**Le jour de la sortie**, envoyez-nous la date et l'identifiant dans le `SUITE_CLIENT` annoncé. Nous
posons les deux variables et relisons avec vous l'AASA et la balise sur `/r/essai`.
