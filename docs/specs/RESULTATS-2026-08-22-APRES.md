# Smoke après correctifs — production, 22 août 2026, 06h44

Contre-mesure de `RESULTATS-2026-08-22.md` (01h30), après livraison des §1 à §6
décrits dans `REPONSE_BACKEND_CORRECTIFS_PERFORMANCE_2026-08-22.md`.

**Verdict : le N+1 est mort.** Le temps ne suit plus le nombre d'éléments rendus.
Mais la campagne déplace le problème plutôt qu'elle ne le referme : le coût
dominant est désormais un **plancher de ~750 ms payé par toute requête
authentifiée, avant le moindre travail métier**. Il n'était pas visible tant que
le mapping en coûtait cinq fois plus.

## §0 — réglé

`PGHOST = postgresdb.railway.internal`. Le service parle à la base par le réseau
privé Railway. L'hypothèse du proxy public à ~100 ms l'aller-retour tombe : le
plancher mesuré ci-dessous n'est pas un artefact de réseau.

## Temps par route

Smoke, 1 VU, aucune concurrence (ms).

| Route | avant (méd. 4 passages) | après (1 passage) | écart |
|---|---:|---:|---:|
| `GET /programs?lat&lng&radius_km` | 5 260 | **1 581** | −70 % |
| `GET /slots/feed` | 4 684 | **2 003** | −57 % |
| `GET /map/users` | 2 737 | **1 314** | −52 % |
| `POST /search` | 2 328 | 2 342 | = |
| `GET /conversations` | 1 799 | 1 752 | = |
| `POST /slots/{id}/join` | 1 605 | 1 563 | = |
| `GET /map/activities` | 1 197 | 1 443 | (n=1) |
| `DELETE /slots/{id}/join` | 1 249 | 1 266 | = |
| `GET /activities/browse` | 1 133 | 1 163 | = |
| `GET /attendances/pending` | 1 095 | 1 101 | = |
| `GET /slots/mine` | 982 | 953 | = |
| `POST /auth/login` | 943 | 1 177 | (n=1) |
| `GET /users/me` | 927 | 955 | = |
| `GET /users/me/programs` | 842 | 799 | = |
| `GET /notifications` | 837 | 790 | = |
| `GET /notifications/unread-count` | 654 | 646 | = |

Ouverture à froid complète : **6,3 s → 3,46 s.**

La lecture qui compte n'est pas le classement mais sa forme : **seules les trois
routes qui rendent des listes d'objets composés ont bougé.** Toutes les autres
sont à l'identique, au bruit près. C'est exactement la signature attendue d'une
correction de mapping — et c'est aussi ce qui rend le reste du tableau
interprétable : ces 800 ms à 1,2 s partout ailleurs ne sont pas du travail, ce
sont des frais fixes.

## La pente, qui était le vrai critère

`GET /slots/feed` autour de Berlin, rayon croissant :

| rayon | éléments | avant | après |
|---:|---:|---:|---:|
| 500 m | 0 | 1 011 | 862 |
| 2 000 m | 0 | 996 | 1 020 |
| 4 000 m | 2 | 5 003 | **2 030** |
| 6 000 m | 3 | 5 627 | **2 209** |
| 8 000 m | 4 | 7 149 | **2 037** |
| 12 000 m | 4 | 7 103 | 1 926 |
| 30 000 m | 4 | 7 158 | 1 930 |
| 50 000 m | 4 | — | 1 896 |

Avant : **~1,0 s fixe + ~1,5 s par élément**, droite nette.

Après : **plus de droite du tout.** De 2 à 4 éléments le temps ne bouge pas
(2 030 / 2 209 / 2 037 ms) — l'écart entre ces trois points est inférieur au bruit
d'un même point répété. Ce qui subsiste est **une marche, pas une pente** :
~1,0 s de plus dès que le fil n'est pas vide, puis plat.

Une régression linéaire naïve sur ces points rendrait « ~240 ms par élément ».
Le chiffre est un artefact : il naît entièrement du saut entre 0 et 2 éléments,
et la lecture point par point le contredit. Nous ne le retenons pas.

Même forme sur `GET /programs` : 1 programme → 1 660 ms, 4 → 1 812 ms,
5 → 1 937 ms, puis plat jusqu'au plafond de 100 km. Contre 5 260 ms avant.

## Ce que la campagne ne prouve pas

À dire avant toute conclusion, parce que la limite est sévère :

- **Le jeu de données plafonne à 4 créneaux et 5 programmes.** La constance n'est
  vérifiée que sur cet intervalle. La projection à 20 créneaux reste une
  projection.
- **Les 4 créneaux appartiennent au même hôte** (`00000000-…-0001`, Seyd Njoya).
  Donc H = 1 sur toute la mesure : **le terme `4 × H` de votre formule n'a pas
  été exercé une seule fois.** Votre réserve sur les hôtes distincts reste
  entièrement non testée — ni confirmée, ni infirmée. Ne lisez pas ce rapport
  comme un feu vert sur ce point.
- Une seule passe, donc n = 1 par route hors `slots_feed`. Seuls les écarts d'un
  facteur 2 et plus sont lisibles ; le reste du tableau ne sert qu'à établir
  qu'il n'a *pas* bougé.
- 1 VU, aucune concurrence. `default_batch_fetch_size` et le pool à 20 ne sont
  pas éprouvés ici : ils se jugeront sous `stress`.

## Le nouveau terme dominant : ~750 ms par requête authentifiée

Mesuré au `curl`, décomposition temporelle, sur `GET /notifications/unread-count`
— une route dont tout le travail est un `COUNT` :

| cas | code | TTFB | total |
|---|---:|---:|---:|
| sans jeton | 401 | 38 ms après TLS | **97 ms** |
| jeton invalide | 401 | 39 ms après TLS | **100 ms** |
| **jeton valide** | 200 | 786 ms après TLS | **849 ms** |
| jeton valide, connexion TLS réutilisée | 200 | — | **793–830 ms** |
| route publique `/v3/api-docs` (2ᵉ tir, à chaud) | 200 | 128 ms | **250 ms** |

DNS + TCP + TLS coûtent 62 ms, stables partout. Une requête rejetée avant
authentification traverse le serveur en **~35 ms**. La même requête, authentifiée,
met **~790 ms** — sur connexion déjà ouverte, donc sans rien de réseau à ajouter.

**Le delta d'environ 750 ms se paie dans la chaîne de filtres authentifiée, pas
dans le point d'entrée.** Il est indépendant de la route : c'est lui qui explique
les ~800 ms à 1,2 s uniformes du premier tableau, et il constitue désormais
**40 % du temps du fil corrigé** (0,8 s sur 2,0 s).

Nous ne savons pas ce qu'il contient — c'est de la mesure de l'extérieur. Ce que
la mesure établit : ce n'est ni le réseau, ni TLS, ni le démarrage à froid (il
tient sur cinq tirs consécutifs), ni la route appelée. Ce que nous suggérons de
regarder en premier : ce que fait le filtre JWT après avoir validé la signature —
chargement de l'utilisateur, écriture éventuelle d'une trace de dernière activité,
ouverture de transaction. Une signature RS256 se vérifie en moins d'une
milliseconde ; 750 ms ne sont pas de la cryptographie.

## Suites proposées

1. **Le plancher authentifié d'abord.** Il vaut maintenant plus que tout ce qui
   reste de N+1 : il frappe *toutes* les routes, pas seulement celles qui rendent
   des listes, et il ne se dilue pas avec la croissance des données.
2. **Le `4 × H` : ne rien faire pour l'instant.** Vous proposez de le ramener à
   ~3 requêtes en passant par les variantes par lot de `SubscriptionService`. Le
   correctif est sans doute juste, mais nous ne pouvons pas le justifier par une
   mesure — H valait 1 partout — et il touche les réglages de confidentialité.
   À reprendre quand le jeu de données portera plusieurs hôtes.
3. **Alimenter la base avant `stress` et `spike`.** Ces deux scénarios restent
   sans objet tant que le plus large rayon interrogeable rend 4 créneaux : ils
   mesureraient la saturation du conteneur sur des listes vides.
4. **`open-in-view=false` (§6)** : votre argument de le traiter dans son propre
   lot est retenu tel quel.

## Trace

- Rapport brut : `results/smoke-20260822-064433.json` / `.ndjson`.
- Aucune écriture durable : l'inscription au créneau a été annulée (`DELETE …/join`).
- Comptes de test toujours en production, **à supprimer** :
  `loadtest-0@example.invalid`, `loadtest-4@example.invalid`.
