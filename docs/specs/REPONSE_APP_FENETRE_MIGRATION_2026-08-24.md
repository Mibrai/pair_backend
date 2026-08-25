# Fenêtre : maintenant — et une chose à mesurer avant de basculer

> Réponse à `REPONSE_BACKEND_PLANCHER_MESURES_2026-08-24.md`.
>
> **La fenêtre est ouverte, tout de suite.** Nous n'attendons pas la nuit :
> quelques minutes de coupure sur une base de 130 Mo, sur un produit qui compte
> aujourd'hui une poignée d'utilisateurs réels, ne justifient pas de laisser
> tourner une journée de plus à 750 ms par requête authentifiée. C'est une
> décision assumée, pas une inattention : un utilisateur peut tomber sur la
> coupure, et nous l'acceptons.
>
> **Une seule chose ne doit pas être sautée dans la précipitation : le
> `SELECT 1` d'avant.** C'est votre étape 1, elle devient irrattrapable dès que
> la base bouge, et c'est la moitié du seul couple qui dira ce que le
> déplacement a rendu.

---

## 1. La fenêtre

Ouverte à la lecture de ce document. Aucune contrainte d'horaire de notre côté,
aucune annonce à faire : les comptes en circulation sont les nôtres et ceux de
recette. Prévenez-nous simplement de l'heure de début et de l'heure de fin —
nous en avons besoin pour écarter d'éventuelles mesures prises à cheval sur la
bascule.

Deux détails d'exploitation, pour que rien ne vous surprenne pendant le dump :

- **Douze programmes en brouillon traînent en production**, semés cet
  après-midi, avec trente-six créneaux. Ils sont invisibles (voir §3) et sans
  conséquence pour la migration ; ils seront supprimés par notre nettoyage, qui
  est programmé et peut tomber pendant votre fenêtre. S'il échoue pour cause de
  coupure, nous le rejouerons — c'est prévu pour être rejoué.
- **Une campagne `smoke` a tourné deux fois aujourd'hui** (16h30 et 16h35), sans
  incident. Ces deux passages constituent notre référence « avant », côté
  client : ouverture à froid 3,67 s et 3,74 s, zéro erreur sur 26 requêtes. Nous
  relancerons le même smoke dès la bascule terminée et vous enverrons le tableau
  route par route.

---

## 2. Le limiteur : ce n'est pas seulement l'inscription

Votre §6 prévoit d'assouplir « le limiteur d'inscription » sur la recette. **Ce
ne sera pas suffisant, et nous l'avons mesuré aujourd'hui sans le chercher.**

`POST /auth/login` est limité lui aussi. En voulant vérifier ce que notre semis
avait produit, nous avons enchaîné une quinzaine de connexions sur une vingtaine
de minutes depuis une seule adresse — connexions légitimes, deux comptes, aucune
erreur de mot de passe. Le serveur a fini par refuser **toute** connexion, et le
refus est immédiat : 429 en 36 ms, contre ~870 ms pour un login accepté. Le
compteur ne s'était pas relâché dix minutes plus tard.

Ce que cela implique, très concrètement, pour la campagne que vous rendez
possible :

- `scenarios/auth-burst.js` — dont l'objet est précisément de mesurer le coût
  CPU du hachage de mot de passe — est **inexécutable** depuis une seule IP. Il
  mesurerait la vitesse du limiteur, pas celle du serveur.
- Tout scénario dont les VU se reconnectent est dans le même cas. Nos scénarios
  ouvrent les sessions une fois dans `setup()` justement pour éviter cela, mais
  `stress` à 800 VU sur deux cents comptes ouvre deux cents sessions en rafale
  au démarrage : c'est exactement le motif qui déclenche ce refus.

**Nous ne demandons rien pour la production.** Sur la recette, en revanche, il
faut assouplir `/auth/login` au même titre que l'inscription — ou nous donner le
seuil exact et sa fenêtre, ce qui nous permettrait de cadencer nos ouvertures de
session au lieu de les subir. La seconde option nous convient parfaitement et
elle est plus sûre que la première.

---

## 3. Notre semis avait un défaut, et il vous concerne

Puisque `tools/seed_content.js` peuplera votre recette, vous devez savoir ce
qu'il produisait ce matin, et ce qu'il produit depuis.

**`POST /programs` crée un brouillon.** `CreateProgramRequest` n'a pas de champ
`status`, le défaut serveur est `DRAFT`, et la publication est un second appel
(`PATCH /programs/{id}`, `status: ACTIVE`) — ce que fait l'app, et que notre
semis avait omis. Résultat du passage de 16h32 : douze programmes créés,
trente-six créneaux acceptés, tous les appels en 2xx, et une découverte
rigoureusement inchangée. Zéro programme visible dans `/programs?lat&lng` autour
des cinq villes.

C'est corrigé. Nous le signalons pour trois raisons : c'est notre erreur, pas la
vôtre ; elle est du genre à ne jamais se voir dans un rapport de charge — tout
est vert, les temps sont bons, et ils sont bons parce qu'on mesure du vide ; et
si vous peuplez la recette avec une version antérieure du script, vous
obtiendrez une base pleine de contenu qu'aucune requête de découverte ne verra.

Nous notons aussi, sans en faire une demande : un `POST` qui rend 201 pour un
objet que rien n'affichera est un contrat qui se prête à ce genre de méprise.

---

## 4. Sur le `SELECT 1` et vos prédictions

Le couple avant/après nous convient, et nous n'avons pas besoin de la valeur
d'avant séparément. Une seule insistance, celle du chapeau : **prenez-la avant
de basculer.** La fenêtre étant immédiate, c'est la seule étape de votre ordre
qui devient impossible à rattraper.

Vos prédictions du §5 sont notées telles quelles — `/slots/feed` à ~175 ms,
`/programs` à ~160 ms, `unread-count` à ~240 ms. Nous les vérifierons avec le
même outil et le même smoke, et nous publierons l'écart sans arrondir dans le
sens qui arrange. Si `unread-count` reste à 240 ms comme vous l'annoncez, nous
le dirons aussi clairement que le reste : une prédiction qui inclut ce qui ne
marchera pas est ce qui rend les deux autres croyables.

---

## 5. Ce que nous ferons, dans l'ordre

1. Nettoyer les douze brouillons (déjà programmé, rejouable après la coupure).
2. Attendre votre « c'est fait » avec les heures de début et de fin.
3. Relancer `smoke` immédiatement, et vous envoyer le tableau route par route
   produit par `tools/compare.py`, en regard des deux passages de cet
   après-midi.
4. Sur la recette, quand elle sera debout : peupler avec le semis corrigé, sur
   plusieurs hôtes, puis `load`, `spike`, `stress`, et le premier chiffre de
   capacité qui veuille dire quelque chose.

---

*Sur votre dernière ligne : nous prenons le compliment, mais l'insistance ne
valait que parce que vous avez répondu par une mesure au lieu d'une opinion. Un
champ de configuration, c'est deux jours ; un désaccord tranché à l'argument,
c'est un trimestre.*
