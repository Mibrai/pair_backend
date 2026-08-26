# Le lien de vérification n'ouvre jamais l'app — et sa page est un cul-de-sac

> Suite de `REPONSE_BACKEND_VERIFICATION_EMAIL_2026-08-25.md`, dont le §5 nous
> laissait la décision : « le lien doit-il un jour ouvrir l'app plutôt qu'un
> navigateur ? »
>
> **Nous l'avons prise : oui, quand l'app est là.** Et nous avons livré côté
> app tout ce qui ne dépendait que de nous. Il reste deux choses chez vous,
> dont une qui tient en quatre lignes de JSON.
>
> Votre livraison du 25 fonctionne : nous l'avons vérifiée maillon par maillon
> avant d'écrire ce qui suit.

---

## 1. Ce que nous avons vérifié chez vous — tout est bon

Relevé le 26 août 2026, sur `production`.

| Ce que nous avons appelé | Réponse | Verdict |
|---|---|---|
| `GET https://lien.meetdo.fun/api/auth/verify-email?token=x` · `Accept: text/html` | **200**, `text/html` | la page existe, et le 200 des quatre états est bien là |
| Le même · `Accept: application/json` | **401** `{"code":"INVALID_TOKEN"}` | **le contrat de l'app n'a pas bougé** — c'était votre §7 point 3 |
| `GET https://pairbackend-…/api/auth/register` (e-mail déjà pris) | **409** `{"code":"EMAIL_EXISTS"}` | correct, message en français |

L'arbitrage sur l'en-tête `Accept` fait exactement ce que vous annonciez. Rien
de ce qui suit n'est un reproche sur cette livraison.

---

## 2. Ce que nous avons corrigé de notre côté

Le symptôme signalé par nos testeurs était : *« je clique sur le lien, le
navigateur dit que mon compte est vérifié, je reviens dans meetDo et je suis
toujours devant l'écran qui réclame un code. »*

Trois défauts, tous chez nous :

1. **L'écran réclamait un « code ».** Il n'y en a pas — vous envoyez un lien.
   Cet écran avait été écrit contre un parcours à six chiffres qui n'a jamais
   existé.
2. **L'app n'apprenait jamais que le compte venait d'être activé.** La
   vérification se joue hors de l'app ; nous n'avions aucun appel pour
   redemander le statut.
3. **`verificationStatus` n'était pas persisté.** Il valait donc `null` au
   redémarrage suivant, et notre garde testait `== 'UNVERIFIED'`. Notre mur
   bloquait les comptes vérifiés et laissait passer les non vérifiés.

Ce qui est livré côté app :

- la vérification **n'est plus un mur** — un bandeau permanent la rappelle, et
  seuls les gestes qui engagent des tiers (proposer un créneau, écrire un
  premier message) la réclament ;
- l'app **redemande le statut** (`GET /users/me`, champ `verificationStatus`) au
  retour au premier plan, sur une minuterie tant que l'écran de vérification est
  ouvert, et sur un bouton « j'ai cliqué sur le lien » ;
- un repli **« coller le lien »**, qui accepte l'URL entière et en extrait le
  `token` pour appeler votre route en JSON.

Conséquence pour vous : **`GET /users/me` est désormais sur un chemin tiède.**
Il est appelé à chaque retour au premier plan d'un compte non vérifié — donc
quelques appels par compte, pendant les quelques minutes où le compte n'est pas
encore validé, et zéro ensuite. Nous avons volontairement choisi cette route
plutôt que de vous en demander une dédiée : elle porte déjà le champ. Dites-nous
si vous préférez l'inverse.

---

## 3. Ce que nous vous demandons — n°1 : quatre lignes dans l'AASA

C'est le seul point bloquant.

Le fichier que vous servez aujourd'hui :

```
GET https://lien.meetdo.fun/.well-known/apple-app-site-association   → 200
```

```json
{
  "applinks": {
    "details": [{
      "appIDs": ["97727T64DH.com.meetdo.app"],
      "components": [
        { "/": "/s/*" },
        { "/": "/p/*" },
        { "/": "/public/slots/*" },
        { "/": "/public/programs/*" }
      ]
    }]
  }
}
```

Le chemin de vérification n'y figure pas. iOS remet donc **toujours** le lien à
Safari, quoi que fasse l'app : l'entitlement `applinks:lien.meetdo.fun` est en
place chez nous depuis le lot Partage, mais un domaine associé ne vaut que pour
les motifs que le fichier déclare.

**Ce que nous demandons** : un chemin court dédié, et son motif dans l'AASA.

```
https://lien.meetdo.fun/v/{token}
```

```json
{ "/": "/v/*", "comment": "Vérification d'adresse e-mail" }
```

`/v/{token}` plutôt qu'un motif sur `/api/auth/verify-email`, pour deux raisons
que nous préférons exposer :

- **un motif sur `/api/*` ferait passer par l'app des URL qui n'ont rien à y
  faire.** L'AASA ne raisonne que sur le chemin ; déclarer un préfixe d'API,
  c'est offrir à iOS de détourner vers l'app tout ce qui lui ressemblera un
  jour ;
- un chemin court se lit dans un e-mail, se colle dans une conversation et
  survit aux messageries qui tronquent — c'est déjà l'argument qui a donné
  `/s/` et `/p/`.

**Côté app, c'est déjà fait et testé.** `https://lien.meetdo.fun/v/{jeton}` et
`meetdo://verify[/{jeton}]` sont reconnus et routés depuis aujourd'hui ; ils
n'attendent que votre motif dans l'AASA pour que le système nous les remette.
Rien n'est à synchroniser entre nous : posez le motif quand vous voulez, l'app
en place suivra.

Le comportement attendu de `/v/{token}` est **exactement celui de la route
actuelle**, mêmes quatre états et même arbitrage sur `Accept` : la page HTML
pour un navigateur, et pour l'app rien du tout — elle interceptera l'URL avant
que la requête ne parte, et appellera `GET /api/auth/verify-email?token=…` en
JSON, dont le contrat ne bouge pas. Une redirection `301` de `/v/{token}` vers
la route existante nous conviendrait tout aussi bien.

Ce que ça donne, une fois posé :

| Où le lien est ouvert | Ce qui se passe |
|---|---|
| Téléphone, app installée | meetDo s'ouvre, vérifie, et affiche la carte |
| Téléphone sans l'app, ordinateur, tablette | votre page — inchangée, et c'est bien son rôle |

C'est votre préférence du §5, tenue : la page reste le repli permanent. Nous ne
lui enlevons rien, nous ajoutons un chemin plus court quand il existe.

---

## 4. Ce que nous vous demandons — n°2 : un bouton sur la page

Moins urgent, mais c'est aujourd'hui une vraie impasse.

La page rendue pour `Accept: text/html` ne contient **aucun lien de retour**.
Nous l'avons vérifié sur les quatre états : pas de `href`, pas de bouton, rien.
Quelqu'un qui vient de valider son compte lit « c'est bon » dans Safari et se
retrouve sans aucune indication de ce qu'il doit faire ensuite — le geste
attendu (revenir à l'app à la main) n'est écrit nulle part.

**Ce que nous demandons** : sur les deux états où le compte est actif — jeton
valide, et jeton déjà utilisé — un bouton vers

```
meetdo://verify
```

Sans jeton : à ce stade il ne sert plus à rien, et une URL qui en porte un
traîne ensuite dans l'historique du navigateur. L'app sait déjà quoi faire de
ce lien nu — elle redemandera le statut.

Un texte de repli sous le bouton (« Vous n'avez pas l'application ? … ») est le
bienvenu, mais nous ne cherchons pas à vous dicter la page.

---

## 5. Ce que nous vérifierons après votre livraison

1. `GET https://lien.meetdo.fun/.well-known/apple-app-site-association` contient
   le motif `/v/*` ;
2. sur un iPhone où meetDo est installée, un lien `https://lien.meetdo.fun/v/…`
   **tapé depuis Mail** ouvre l'app et non Safari ;
3. le même lien, sur un appareil sans l'app, ouvre votre page — inchangée ;
4. `GET /api/auth/verify-email?token=…` en `Accept: application/json` rend
   toujours 200 / 401 `INVALID_TOKEN`. **C'est le point qui nous importe le
   plus**, comme au lot précédent : la page est nouvelle et ne peut rien casser,
   le contrat JSON est ancien et porte l'application.

Un délai de propagation est à prévoir sur le point 2 : iOS met l'AASA en cache,
et une réinstallation de l'app est parfois nécessaire pour le revalider. Nous en
tiendrons compte avant de conclure quoi que ce soit.

---

*Une note pour finir, parce qu'elle vous concerne. Votre §3 racontait les jetons
gardés en mémoire, que nos mesures ne pouvaient pas voir. Le nôtre est le
symétrique exact : notre statut de vérification vivait en mémoire lui aussi, et
disparaissait à chaque redémarrage de l'app. Le vôtre invalidait les liens à
chaque redéploiement ; le nôtre faisait marcher notre propre garde à l'envers.
Aucun des deux n'était visible depuis l'autre côté.*
