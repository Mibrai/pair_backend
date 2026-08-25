# Réponse backend au smoke d'après correctifs — 22 août 2026

> Réponse à `RESULTATS-2026-08-22-APRES.md` (06h44). Votre campagne établit ce
> qu'elle devait établir : le N+1 est mort, et vous l'avez démontré proprement.
>
> Sur le terme dominant qui reste, nous arrivons à une conclusion différente de
> la vôtre. **Votre hypothèse — un coût dans la chaîne de filtres authentifiée —
> ne survit pas à l'audit du code : il n'y a rien dans ce chemin qui puisse
> coûter 750 ms.** Mais vos mesures, elles, tiennent, et elles désignent autre
> chose : non pas un plancher par requête HTTP, mais un **coût par aller-retour
> SQL**, de l'ordre de 210 ms. Ce document donne le raisonnement, ce qu'il
> implique, et la mesure de trente secondes qui tranche.

---

## 1. Ce que votre campagne établit, et que nous prenons pour acquis

La pente a disparu. C'était le critère, et il est rempli : 2, 3 et 4 éléments
coûtent 2 030 / 2 209 / 2 037 ms, un écart inférieur au bruit d'un même point
répété. Une marche, plus une droite.

Nous relevons trois choses dans votre méthode, parce qu'elles nous font gagner
du temps et qu'elles ne vont pas de soi :

- **Vous écartez vous-même la régression naïve** qui rendrait « 240 ms par
  élément », en montrant qu'elle naît entièrement du saut entre 0 et 2 éléments.
  Un rapport qui aurait publié ce chiffre nous aurait envoyés chercher un N+1
  résiduel qui n'existe pas.
- **Vous signalez que `H = 1` sur toute la mesure** — les quatre créneaux
  appartiennent au même hôte — et refusez d'en faire un feu vert sur le terme
  `4 × H`. C'est exactement la réserve que nous avions posée, et vous avez
  raison : elle n'est ni confirmée ni infirmée.
- **Vous distinguez ce qui est lisible à n = 1** de ce qui ne l'est pas.

La forme du tableau est le point le plus instructif : seules les trois routes qui
rendent des listes d'objets composés ont bougé, les treize autres sont
inchangées. C'est la signature d'une correction de mapping, et elle rend le reste
du tableau interprétable — ce que vous en tirez.

---

## 2. La chaîne de filtres authentifiée : nous avons regardé, il n'y a rien

Vous suggérez de regarder « ce que fait le filtre JWT après avoir validé la
signature — chargement de l'utilisateur, écriture éventuelle d'une trace de
dernière activité, ouverture de transaction ». Nous l'avons fait, point par
point :

| Piste que vous suggérez | Ce que dit le code |
|---|---|
| Vérification de signature coûteuse | `Jwts.parser().verifyWith(…)`, HMAC-SHA. Quelques microsecondes. Vous l'aviez anticipé. |
| Chargement de l'utilisateur | `userRepository.findById(userId)`, **une** requête par clef primaire. |
| Associations tirées avec l'utilisateur | `User` ne porte **aucun** `FetchType.EAGER`. `UserPrincipal` ne lit que `id`, `email`, `passwordHash`, `isActive` — des colonnes, pas des associations. |
| Écriture d'une trace de dernière activité | `setLastActiveAt` n'est appelé qu'à la connexion (`AuthService`) et sur une route dédiée. **Jamais par requête.** |
| Intercepteur, aspect, auditeur | Aucun `HandlerInterceptor`, aucun `@Aspect`. `@EnableJpaAuditing` est présent mais sans `AuditorAware` qui chargerait un utilisateur. |

Le filtre entier, pour une requête authentifiée, c'est : une vérification HMAC,
puis **une requête SQL**. Il n'y a pas 750 ms à y trouver.

Cela n'invalide pas votre mesure. Cela veut dire qu'elle mesure autre chose que
ce que vous en avez déduit.

---

## 3. Ce que votre mesure dit à la place

### Le couple qui porte l'information

Votre tableau au `curl` contient la réponse, dans deux lignes voisines :

| cas | code | total |
|---|---:|---:|
| jeton invalide | 401 | 100 ms |
| jeton valide | 200 | 849 ms |

La différence entre ces deux cas **n'est pas l'authentification**. Un jeton
invalide fait échouer `validateToken`, le filtre passe son chemin sans appeler
`loadUserById`, et Spring Security rejette. Ce cas ne touche **jamais** la base.

Le cas à 849 ms est le premier des deux qui ouvre une conversation avec
Postgres. Ce que vos 750 ms mesurent, c'est le prix d'aller parler à la base —
pas le prix de s'authentifier.

### Le modèle, et son ajustement à vos chiffres

Si le coût était un plancher fixe par requête HTTP, comme votre titre le pose,
toutes les routes authentifiées partiraient du même niveau et ne différeraient
que par leur travail. Or `/notifications/unread-count` (646 ms) et `/programs`
(1 581 ms) font tous deux un travail négligeable en base — un `COUNT` d'un côté,
quatre agrégats groupés de l'autre. Un plancher fixe ne peut pas produire un
facteur 2,4 entre eux.

Nous avons compté les requêtes SQL que chaque route émet **dans le code
corrigé**, en incluant celle du filtre JWT :

| route | requêtes | détail |
|---|---:|---|
| `/notifications/unread-count` | 2 | filtre JWT + `countByUserIdAndIsReadFalse` |
| `GET /programs` | 7 | filtre + ids + rechargement + séances + médias + résumé d'avis + inscrits |
| `GET /slots/feed` | 9 | filtre + ids + rechargement + participations + 4 (profil de l'hôte unique) + étiquettes |

Avec vos 62 ms de réseau et ~35 ms de traversée serveur, les deux routes à
plusieurs requêtes donnent :

- `/programs` : (1 581 − 97) / 7 = **212 ms par requête**
- `/slots/feed` : (2 003 − 97) / 9 = **212 ms par requête**

Deux routes indépendantes, deux comptes de requêtes différents, **la même valeur
à un millième près**. C'est ce qui nous fait retenir le modèle.

### Ce qui ne colle pas, et que nous ne cachons pas

`/notifications/unread-count` devrait alors coûter 97 + 2 × 212 = **519 ms**.
Vous mesurez **646 ms**. Il manque 127 ms.

L'explication la plus simple est qu'il existe **aussi** un coût fixe par requête
authentifiée, que les routes à sept et neuf requêtes diluent. En ajustant les
deux termes sur les trois routes à la fois, on obtient un fixe d'environ 210 ms
et un coût par requête d'environ 187 ms, qui rend les trois points à moins de
50 ms près.

Autrement dit : votre lecture d'un plancher par requête n'est pas fausse, elle
est **incomplète**. Il y a bien un terme fixe, mais il ne fait que le quart du
total sur `/slots/feed` ; l'essentiel est proportionnel au nombre de requêtes
SQL. Selon qu'on retient un modèle à un ou deux termes, le coût par aller-retour
ressort entre **187 et 212 ms** — et c'est cette fourchette, pas la valeur
exacte, qui porte la conclusion.

---

## 4. Conséquence : votre §0 n'est réglé qu'à moitié

Votre premier rapport demandait **deux** vérifications sous le §0 :

> `…​.railway.internal` → réseau privé […]
> Vérifier également que le service applicatif et la base sont dans la **même
> région** (le service répond depuis `ams1`).

Le rapport d'après valide la première — `postgresdb.railway.internal`, dont acte
— et conclut « §0 — réglé ». **La seconde n'apparaît nulle part.**

Or un hôte privé ne dit rien de la distance physique. Un aller-retour privé
mais inter-région produit exactement ce que nous mesurons : un coût par requête,
constant, indifférent à la route, invisible tant que le mapping en coûtait cinq
fois plus. À 212 ms, l'ordre de grandeur évoque bien davantage une traversée
géographique qu'un réseau interne, où l'on attend 1 à 5 ms.

Ce n'est pas une certitude. C'est l'hypothèse qui explique le plus avec le moins,
et elle est vérifiable en une commande.

---

## 5. La mesure qui tranche

Deux gestes, quelques minutes, et le doute est levé :

1. **La région.** Comparer la région du service applicatif et celle du service
   Postgres dans Railway. Si elles diffèrent, c'est réglé — et le correctif est
   un déplacement de service, pas une ligne de code.

2. **Le coût nu d'un aller-retour**, qui ne dépend d'aucune interprétation :
   chronométrer un `SELECT 1` depuis le service applicatif, en réutilisant une
   connexion déjà ouverte. C'est la mesure directe de ce que notre modèle estime
   à 212 ms par déduction.

   - ~1 à 5 ms → notre modèle est faux, revenez vers nous, nous chercherons
     ailleurs et nous aurons appris quelque chose.
   - ~200 ms → tout ce qui précède est établi, et **aucun correctif de code ne
     déplacera cette valeur.**

Nous pouvons aussi activer temporairement `hibernate.generate_statistics` (votre
§7) pour opposer le temps SQL rapporté par Hibernate au temps total de la
requête. C'est plus lourd que le `SELECT 1` et cela répond à la même question ;
dites-nous si vous le préférez.

---

## 6. Ce que cela implique, si c'est confirmé

**Pour vous, une bonne nouvelle et une mauvaise.** La mauvaise : le plancher ne
se corrigera pas dans notre code. La bonne : il se corrige, et probablement par
une configuration d'infrastructure — ce qui est plus rapide et moins risqué que
n'importe quel lot applicatif.

**Cela requalifie aussi ce que nous venons de livrer.** Si chaque aller-retour
coûte ~200 ms, alors supprimer des requêtes est le seul levier applicatif qui
compte — et c'est exactement ce que les §1 à §5 ont fait.

Précision utile pour lire nos chiffres : notre estimation de « 600 à 1 000
requêtes » pour `/programs` valait pour une page pleine de 100 programmes. Votre
base en contient cinq. L'ancienne route en émettait donc de l'ordre de 35, pas
600 — ce qui, à 5 260 ms, redonne ~150 ms par requête.

**Et c'est là que votre première campagne devient une corroboration
indépendante.** Sa pente était de ~1,5 s par élément de fil. Chaque élément
coûtait alors une douzaine de requêtes (la cascade program → userActivity →
activity → category, les étiquettes, la participation, le profil public et ses
badges). Soit **~120 ms par requête** — mesuré en janvier de la même journée,
sur un tout autre protocole, sans rien connaître du modèle ci-dessus.

Trois mesures indépendantes donnent 120, 150 et 190-210 ms. La dispersion est
réelle et nous ne la lissons pas ; l'ordre de grandeur, lui, ne bouge pas. Un
aller-retour vers cette base coûte **cent fois** ce qu'il devrait coûter sur un
réseau interne.

Et cela donne le bon ordre pour la suite : **si le plancher tombe à 5 ms, le fil
passe sous 200 ms sans que nous touchions une ligne**, et votre cible de 500 ms
est dépassée avec de la marge. Inversement, tant qu'il tient, aucune
optimisation applicative ne vous y amènera.

D'où notre accord complet avec votre point 1 : le plancher d'abord.

---

## 7. Le terme `4 × H` — nous suivons votre décision

Vous proposez de ne rien faire tant que la mesure ne le justifie pas, `H` ayant
valu 1 partout. **Nous suivons, et pour la même raison.**

Nous ajoutons un argument dans votre sens : à 212 ms l'aller-retour, ramener
`4 × H` à ~3 requêtes ferait gagner environ 200 ms par hôte distinct — soit
beaucoup, mais toujours moins que de faire tomber le plancher lui-même. L'ordre
que vous proposez est le bon dans les deux hypothèses.

Le correctif reste prêt à être pris : `SubscriptionService` expose déjà les
variantes par lot, et `UserService` s'en sert pour sa liste paginée. À reprendre
quand votre jeu de données portera plusieurs hôtes — ce qui rejoint votre
point 3.

---

## 8. Points opérationnels

**Les comptes de test en production.** `loadtest-0@example.invalid` et
`loadtest-4@example.invalid`. Dites-nous si vous voulez que nous les supprimions
ou si vous préférez le faire — nous ne touchons pas à des données de production
sans demande explicite. S'ils doivent resservir à la prochaine campagne, autant
les garder et le noter quelque part.

**Alimenter la base avant `stress` et `spike`** (votre point 3) : d'accord, et
c'est aussi ce qui permettra enfin d'exercer `H > 1`. Nous pouvons fournir un
jeu de données de recette — plusieurs dizaines de créneaux répartis sur plusieurs
hôtes — si cela vous est utile. Dites-nous la forme qui vous arrange.

**`open-in-view=false`** (votre point 4) : noté, traité dans son propre lot.
Nous le prendrons après le plancher, parce que c'est un réglage qui se juge sous
concurrence et que `stress` n'est pas encore exécutable.

---

## 9. Ce que nous attendons de vous

1. **La région du service et de la base**, et le `SELECT 1` chronométré. C'est le
   seul point bloquant : tout le reste de la priorisation en dépend.
2. **Confirmation sur les comptes de test** — à supprimer ou à conserver.
3. Rien d'autre. Si le plancher est bien où nous le pensons, la prochaine
   campagne se mesure sur une infrastructure corrigée, et elle dira ce qui reste
   vraiment à faire côté code.

Merci pour la contre-mesure. Un rapport qui distingue ce qu'il prouve de ce qu'il
suggère, et qui dit lui-même où son jeu de données s'arrête, vaut beaucoup plus
qu'un tableau de chiffres — c'est ce qui nous a permis d'aller chercher le
désaccord dans le code plutôt que de vous croire ou de vous contredire à
l'aveugle.
