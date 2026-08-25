# Réponse — la région, et ce qu'elle explique

> Réponse à `RELANCE_BACKEND_PLANCHER_2026-08-24.md`. Vous aviez raison
> d'insister, et vous aviez raison de ne pas lancer de campagne.
>
> **Le service applicatif et la base ne sont pas sur le même continent.** Le
> service tourne aux Pays-Bas, la base à San Francisco, depuis le 4 juillet.
> C'est la branche « ~200 ms » de notre §5, et elle referme le dossier : le
> plancher n'est pas dans notre code, il est dans un champ de configuration
> d'hébergement. Nous déplaçons la base.
>
> La seconde mesure — le `SELECT 1` chronométré — n'est volontairement pas dans
> ce document. Nous disons plus bas pourquoi, et quand vous l'aurez.

---

## 1. La région : la mesure que vous demandiez

Relevé sur l'environnement `production` du projet Railway, champ
`serviceManifest.deploy.multiRegionConfig` de chaque service :

| service | région | signification | déployé depuis |
|---|---|---|---|
| `pair_backend_service` | `europe-west4-drams3a` | Pays-Bas (Eemshaven) | 22 août 2026 |
| `postgres_db` | `sfo` | San Francisco, Californie | **4 juillet 2026** |

Cohérent avec ce que vous observiez vous-même en en-tête : `x-railway-edge:
ams1`. Le service répond bien depuis l'Europe. La base, non.

**Votre §0 était donc réglé à moitié, exactement comme nous le supposions.**
`postgresdb.railway.internal` est un vrai réseau privé — nous ne revenons pas
là-dessus. Mais un réseau privé chiffré n'est pas une proximité physique : c'est
un maillage entre régions, et il traverse la même distance que n'importe quoi
d'autre. L'hôte privé garantit qu'on ne sort pas sur l'internet public ; il ne
garantit rien sur les kilomètres.

Vous pouvez le vérifier de votre côté sans nous croire : `railway status --json`
sur l'environnement de production, puis le champ `multiRegionConfig` de chaque
service.

---

## 2. Ce que cette distance coûte, et pourquoi votre modèle tenait

Eemshaven ↔ San Francisco, c'est **8 763 km** à vol d'oiseau. Dans la fibre, la
lumière va à environ 200 000 km/s, et un tracé réel fait un tiers à une moitié
de plus que la ligne droite :

| hypothèse de tracé | aller-retour |
|---|---:|
| ligne droite théorique | 88 ms |
| fibre réelle × 1,35 | 118 ms |
| fibre réelle × 1,5 | 131 ms |

À quoi s'ajoutent la traversée du maillage privé chiffré, le chiffrement lui-même
et les sauts intermédiaires. On arrive sans effort dans les **150 à 200 ms**.

**Votre modèle donnait 187 à 212 ms par aller-retour SQL.** Il a été construit
par déduction, à partir de trois routes et de leur nombre de requêtes, sans
rien savoir de la géographie. La borne physique tombe dans votre fourchette.

Ce n'est plus une hypothèse qui explique le plus avec le moins : c'est deux
chemins indépendants — le vôtre par la mesure, celui-ci par la carte — qui
donnent le même nombre. Nous considérons le point comme établi.

Et la conséquence que nous écrivions au §5 tient telle quelle : **aucun
correctif de code ne déplacera cette valeur.**

---

## 3. Pourquoi le `SELECT 1` n'est pas joint

Parce qu'il ne tranche plus rien, et parce que la valeur qui vous intéresse
n'est pas celle d'aujourd'hui.

Le `SELECT 1` avait un rôle précis : départager « ~1 à 5 ms » de « ~200 ms »
lorsque nous n'avions qu'un modèle. La région le départage, et de façon moins
interprétable qu'un chronomètre — 8 763 km ne se discutent pas. Publier
aujourd'hui un chiffre à 200 ms confirmerait ce que ce document établit déjà.

En revanche il redevient utile, et il devient même la seule preuve qui compte,
sur l'**écart avant/après**. Nous le chronométrerons donc deux fois — une fois
avant le déplacement, une fois après — et nous vous donnerons le couple. C'est
lui qui vous dira ce que le déplacement a réellement rendu, et pas ce que nous
en attendions.

Si vous préférez malgré tout la valeur d'avant dès maintenant, dites-le : c'est
quelques minutes et nous vous l'envoyons séparément.

---

## 4. Le correctif : nous déplaçons la base, pas le service

Les deux sens sont possibles et ne se valent pas.

**Déplacer le service vers `sfo`** supprimerait aussi le problème — service et
base enfin voisins. Mais vos 62 ms de réseau mesurés au `curl` deviendraient
~150 ms, pour vous et pour tous les utilisateurs européens, sur chaque requête.
On échangerait un défaut interne contre une pénalité visible par l'utilisateur.

**Déplacer la base vers l'Europe** ne coûte rien à personne. C'est ce que nous
faisons.

La contrainte est le volume : une région Railway porte son stockage, et un
service à volume ne change pas de région par un interrupteur. Le volume
`postgres_db-volume-vcIU` contient **130 Mo** — c'est peu, et cela rend la
migration ordinaire : base neuve dans la région européenne, `pg_dump` /
`pg_restore`, bascule de `PGHOST`, vérification, retrait de l'ancienne.

**Ce que nous vous demandons : une fenêtre.** Il y aura une courte coupure —
quelques minutes, dominées par le transfert transatlantique du dump, pas par sa
taille. Nous la voulons annoncée et hors heures d'usage. Dites-nous ce qui vous
arrange ; à défaut de contrainte de votre côté, nous proposerons un créneau.

---

## 5. Ce que le déplacement devrait rendre — annoncé comme prédiction

Ces chiffres ne sont pas des résultats. Ils sont ce que le modèle prédit, écrits
avant la mesure pour que vous puissiez nous prendre en défaut après.

Si le coût par aller-retour passe de ~205 ms à ~2 ms, chaque route perd
`n × 203 ms` :

| route | requêtes | aujourd'hui | prédit après |
|---|---:|---:|---:|
| `GET /slots/feed` | 9 | 2 003 ms | **~175 ms** |
| `GET /programs` | 7 | 1 581 ms | **~160 ms** |
| `/notifications/unread-count` | 2 | 646 ms | **~240 ms** |

Deux remarques, dont une qui nous gêne et que nous préférons écrire nous-mêmes.

**Votre cible de 500 ms est dépassée avec de la marge sur les deux routes de
fil.** C'est le point 1 de votre priorisation, et il se règle sans que nous
touchions une ligne.

**La troisième ligne est l'anomalie que nous signalions au §3 du 22, et elle ne
part pas avec le déplacement.** `unread-count` fait deux requêtes triviales ;
240 ms restants, c'est trop. C'est le terme fixe d'environ 130 ms que nous ne
savions pas expliquer, dilué jusqu'ici par les routes à sept et neuf requêtes.
Une fois le plancher tombé, il devient le terme dominant — et enfin mesurable.
Ce sera notre sujet suivant, et c'est là que `hibernate.generate_statistics`
retrouvera son intérêt.

Autrement dit : le déplacement ne clôt pas la recherche, il la rend possible.

---

## 6. Vos deux questions d'exploitation

**Les comptes `loadtest-*@example.invalid` : conservez-les.** Ils resserviront à
la campagne d'après déplacement, et les recréer coûterait de se battre avec le
limiteur pour rien. Nous les notons comme comptes de recette à retirer à la fin
de la campagne, pas avant.

**L'environnement de recette : oui, et nous le montons.** Un second projet
Railway existe déjà dans le même espace de travail, vide, sans déploiement.
Nous en ferons la recette : service applicatif et base **tous deux en Europe**,
peuplés par votre `tools/seed_content.js`, avec le limiteur d'inscription
assoupli pour ce seul environnement.

Ce que cela vous rend :

- une campagne peut créer deux cents comptes et écrire librement, sans nettoyage
  anxieux ni CPU facturé sur la production ;
- `stress` et `spike` deviennent exécutables pour de bon ;
- la production cesse d'être le terrain de mesure.

Ce que cela ne vous rend pas : les chiffres de la recette ne seront pas ceux de
la production — pas la même taille de base, pas le même bruit. La recette sert à
comparer *avant/après* et à trouver le genou de la courbe. Le chiffre absolu se
relèvera toujours en production.

**Nous ne demandons donc plus de campagne contre la production.** Votre §3 posait
la question comme une décision plutôt qu'une habitude ; nous sommes d'accord, et
nous répondons en supprimant la décision.

Nous vous donnerons l'URL et un jeu d'identifiants quand ce sera debout. Ordre
retenu : le déplacement de la base d'abord, la recette ensuite.

---

## 7. Sur ce que vous avez fait entre-temps

**Le semis multi-comptes est le point qui compte.** Vous avez raison de le mettre
en avant : `H = 1` sur toute la campagne du 22 laissait le terme `4 × H` ni
confirmé ni infirmé, et c'était la seule zone du modèle où nous vous demandions
de nous croire sur parole. Elle cesse de l'être.

Nous maintenons l'ordre convenu au §7 du 22 : ne rien faire sur `4 × H` tant que
la mesure ne le justifie pas. Après le déplacement, à ~2 ms l'aller-retour, ce
terme vaudra quelques millisecondes par hôte au lieu de 800 — il est probable
qu'il disparaisse de vos rapports sans que nous y touchions. Le correctif reste
prêt si la mesure dit le contraire : `SubscriptionService` expose déjà les
variantes par lot.

**Le nettoyage programme par programme sous le compte propriétaire** est la bonne
forme. Nous n'avions pas demandé cette garantie ; vous l'avez apportée.

**`tools/compare.py`** — relever une p95 à la main était la faiblesse méthodique
de votre rapport du 22, et vous la corrigez avant que nous ayons eu à en parler.

**La correction sur la carte : prise, et sans conséquence pour nous.** Nous
n'avions rien dimensionné sur ce comportement — la carte n'apparaît dans aucun
de nos calculs, qui portaient sur les routes de fil et de programmes. Votre
phrase est à retirer de vos documents ; les nôtres ne bougent pas.

Un mot tout de même sur la façon dont vous la corrigez : « dix gestes de caméra
coûtent zéro requête, verrouillé par un test » est une correction qui vaut mieux
que la plupart des confirmations. Un chiffre qui allait dans le sens de votre
propre argumentaire, retiré et remplacé par une garantie testée.

---

## 8. Ce qui se passe maintenant

De notre côté, dans cet ordre :

1. `SELECT 1` chronométré **avant** déplacement — conservé pour la comparaison.
2. Déplacement de la base vers l'Europe, sur la fenêtre convenue.
3. `SELECT 1` chronométré **après**. Le couple vous est envoyé le jour même.
4. Montage de l'environnement de recette, service et base en Europe.
5. URL et identifiants de recette, avec le limiteur assoupli.

De votre côté, une seule chose est bloquante : **la fenêtre de coupure**. Le
reste peut attendre l'étape 5.

Puis votre campagne, sur la recette, avec plusieurs hôtes et le tableau route par
route. C'est elle qui dira ce qui reste vraiment à faire dans notre code — et
cette fois la réponse ne sera pas noyée sous 200 ms par requête.

---

*Deux jours de blocage pour un champ de configuration, c'est deux jours de trop,
et l'insistance était de votre côté. Nous notons aussi que sans votre refus de
lancer une campagne dans ces conditions, nous aurions un rapport de charge de
plus, chiffré, publiable, et entièrement dépourvu de sens.*
