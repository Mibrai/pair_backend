# Relance — le plancher authentifié, deux jours après

> Suite à `REPONSE_BACKEND_PLANCHER_AUTHENTIFIE_2026-08-22.md` (§5, « la mesure
> qui tranche »). Deux mesures y étaient demandées ; nous n'en avons reçu
> aucune, et **toute la priorisation en dépend encore**. Ce document les
> redemande, dit ce que nous avons fait entre-temps, et pourquoi nous ne
> lançons pas de campagne de charge tant qu'elles manquent.

---

## 1. Ce qui reste bloqué, et ce que ça bloque exactement

Votre §5 proposait deux gestes de quelques minutes :

1. **La région** du service applicatif et celle du service Postgres dans
   Railway. Si elles diffèrent, le plancher est expliqué et le correctif est un
   déplacement de service.
2. **Le coût nu d'un aller-retour** : un `SELECT 1` chronométré depuis le
   service applicatif, sur une connexion déjà ouverte.

Vous écriviez vous-même la règle de lecture : « ~1 à 5 ms → notre modèle est
faux […] ; ~200 ms → tout ce qui précède est établi, et **aucun correctif de
code ne déplacera cette valeur** ».

C'est pour cela que nous n'avons pas relancé de campagne. Ce n'est pas de
l'attentisme : à ~750 ms de frais fixes par requête authentifiée, un `stress`
mesurerait la file d'attente d'un conteneur occupé à attendre la base, et nous
publierions un « genou de la courbe » qui ne parlerait ni de votre code ni de
notre architecture, mais d'une ligne de configuration d'hébergement. Nous
préférons ne pas produire ce chiffre plutôt que de le produire faux.

---

## 2. Ce que nous avons fait pendant ce temps

**Nous avons levé le blocage qui était de notre côté.** Vous aviez raison de le
signaler : notre jeu de données plafonnait à 4 créneaux et 5 programmes, tous du
même hôte, et nous en avions tiré nous-mêmes que `stress` et `spike` étaient
« sans objet ».

- `tools/seed_content.js` sème désormais P programmes × S créneaux, dispersés
  géographiquement autour des points de mesure, **répartis sur plusieurs
  comptes**. C'est ce dernier point qui compte pour vous : le terme `4 × H` de
  votre modèle — quatre requêtes par hôte distinct dans une page de fil — n'a
  jamais été exercé, H valant 1 sur toute la campagne du 22. Il le sera à la
  prochaine.
- `tools/unseed_content.js` supprime ce que le semis a créé, programme par
  programme, sous le compte propriétaire. **Nous ne laisserons rien derrière
  nous** ; c'est votre base de production, et c'est aussi celle de vos
  utilisateurs réels.
- `tools/compare.py` rend le tableau route par route, p95 comprise, à partir du
  flux `.ndjson` d'une campagne. Les chiffres de notre rapport du 22 avaient été
  relevés à la main ; ils ne le seront plus.

**Nous avons aussi corrigé une de nos erreurs**, et elle allait dans le sens de
la surcharge : notre document annonçait qu'un doigt promenant la carte pendant
dix secondes valait « plusieurs dizaines de requêtes à filtre spatial ». C'est
faux depuis que la carte ne recharge que sur demande explicite. Mesuré et
verrouillé par un test : **dix gestes de caméra coûtent zéro requête**. Si vous
aviez dimensionné quoi que ce soit sur cette phrase, elle est à retirer.

---

## 3. Deux questions d'exploitation, restées elles aussi sans réponse

1. **Les comptes de test** (`loadtest-*@example.invalid`) : à supprimer ou à
   conserver ? Nous n'y toucherons pas sans votre accord.
2. **Le limiteur sur l'inscription** : ~2 créations par heure et par IP. Nous ne
   demandons pas de l'assouplir en production. La question est : **existe-t-il
   ou peut-il exister un environnement de recette** — service et base séparés —
   où une campagne pourrait créer deux cents comptes et écrire librement ?

Sans réponse à la seconde, toute campagne sérieuse s'exécute contre la
production, avec les vraies données et le CPU facturé. Nous le ferons si c'est
le choix retenu, mais alors : hors heures de pointe, annoncé, et avec le
nettoyage joint. Ce n'est pas une position idéale, et il vaut mieux que ce soit
une décision qu'une habitude.

---

## 4. Ce que nous ferons dès que le `SELECT 1` sera connu

- **~200 ms** — nous publions la révision du dimensionnement : à ce coût par
  aller-retour, le seul levier applicatif est la réduction du **nombre** de
  requêtes, ce que vos lots §1 à §5 ont déjà fait. La suite se joue alors sur
  l'hébergement, et nous mesurerons *après* déplacement pour donner le gain
  réel.
- **~1 à 5 ms** — votre modèle tombe, le plancher est ailleurs, et nous
  reprenons la recherche avec vous ; nous proposons alors
  `hibernate.generate_statistics` (votre §5), qui oppose le temps SQL rapporté
  par Hibernate au temps total de la requête.

Dans les deux cas, la campagne suivante sera lancée sur une base peuplée par le
semis ci-dessus, avec plusieurs hôtes, et son rapport comparera route par route
plutôt qu'à l'œil.

---

*Une ligne suffit pour le premier point : la région des deux services. C'est le
seul endroit où quelques secondes de votre part débloquent l'ensemble.*
