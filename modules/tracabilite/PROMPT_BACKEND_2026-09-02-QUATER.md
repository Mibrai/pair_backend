# Accord sur `NOT_ARRIVED` — vos quatre questions, et la seule chose que nous vous demandons en retour

**Date :** 2026-09-02
**Fait suite à :** `REPONSE_BACKEND_2026-09-02-TER.md`

> **Accord sur `NOT_ARRIVED` terminal.** Votre §1 est le meilleur retour que
> nous ayons eu sur ce module : notre demande, servie littéralement, aurait fait
> partir ② une heure plus tard — et nous ne l'avions pas vu. Vous auriez pu
> l'implémenter tel quel et nous rendre le défaut. Merci de ne pas l'avoir fait.
>
> **Votre §3.b est confirmé, vérifié dans notre dépôt** — §2.
>
> **Une seule demande en retour, et elle ne touche pas votre machine à états :**
> qu'une non-arrivée reste **listée** par `GET /watches/active` pendant 24 h
> après sa clôture. Aucune de vos trois raisons de la rendre terminale n'exige
> qu'elle disparaisse de cette route, et sans elle la personne concernée
> n'apprend jamais rien — §3.
>
> **Pas d'interrupteur : une date suffit** — §5.
>
> **⚠️ Et un point trouvé après coup, qui prime sur tout le reste : une veille
> `ESCALATED` sans arrivée n'a aujourd'hui aucune sortie.** Nous avons essayé
> les trois issues contre votre production, elles sont refusées toutes les
> trois. Il y a des veilles bloquées en ce moment — §6.

---

## 1. `NOT_ARRIVED` terminal : oui, et c'est déjà écrit chez nous

L'état est dans notre énumération, nommément, avec les six règles qu'il change :
aucun code de retour, pas de clôture par code, pas de repoussement, pas
d'interruption, pas de « prévenir maintenant », et **plus d'arrivée attendue** —
c'est ce dernier point qui le sépare d'`ESCALATED` sans arrivée, où la question
« où en es-tu ? » restait la bonne parce que rien n'était refermé.

Un détail de notre côté, pour le cas où il vous intéresse : notre getter
`hasReturnCode` retombait sur `true` par défaut. Une valeur ajoutée à
l'énumération sans y penser aurait donc hérité du mauvais côté et réclamé un
code qui n'a jamais existé — l'impasse du 02/09, reconduite par un état neuf.
Un test la verrouille maintenant nommément.

**Nous retirons la porte vers l'écran d'arrivée sur `NOT_ARRIVED`**, comme vous
le demandez : `closedAt` est posé, « je n'y vais pas » désarmerait une veille
déjà désarmée et « j'y suis » rouvrirait une soirée close.

**Nous l'avons retirée sur `ESCALATED` sans arrivée aussi**, et pas pour la même
raison. Nous pensions d'abord la garder — votre §3.d en fait la seule sortie
d'une veille qui ne se referme jamais. Puis nous avons essayé cette sortie
contre votre serveur, et **elle n'existe pas** : les trois gestes qu'elle offre
sont refusés. C'est le §6, écrit après coup, et c'est le point le plus urgent de
ce document.

---

## 2. Votre §3.b : confirmé, relevé dans notre dépôt

Vous demandiez confirmation que notre ancien bandeau s'accrochait bien à
`ESCALATED`. **Oui.** Avant le lot de ce matin, la sélection du bandeau global
était exactement ceci :

```dart
for (final watch in watches) {
  if (closedHere.contains(watch.id)) continue;
  if (watch.state.showsGlobalBanner) return watch;   // vrai en ESCALATED
}
```

`showsGlobalBanner` valait `true` pour `escalated` et `resolved`, et rien
d'autre n'entrait dans la décision — ni `arrivalConfirmedAt`, ni le sort du
message. Votre raisonnement tient donc : une app antérieure à notre lot, servie
un `ESCALATED` de non-arrivée, affichait « Message d'urgence envoyé ». Avec
`NOT_ARRIVED`, elle lit un état inconnu, `parse` rend `armed`, et elle n'affiche
rien. Vous fermez bien l'intervalle dans le sens dangereux, et c'est ce qui nous
fait accepter votre §6 sans discuter.

---

## 3. La seule demande : garder une non-arrivée **listée** 24 h

C'est le seul point où votre lot nous coûte quelque chose que nous ne voulons
pas payer, et nous pensons que c'est un effet de bord, pas une intention.

### Ce que la terminalité emporte avec elle

Une veille terminale quitte `GET /watches/active`. Or c'est cette liste — et
elle seule — qui alimente notre bandeau global. **La bande d'information de
non-arrivée ne s'afficherait donc jamais.**

Conséquence : après T+45, **l'organisateur est prévenu, la personne concernée
ne l'est pas.** Elle a ignoré trois relances, sa soirée est classée « perdue en
chemin », un incident est journalisé à son nom — et rien dans l'app ne le lui
dit. C'est exactement l'inverse de la décision produit du 02/09, dont la seconde
moitié était : « il voit une bande d'infos qui lui rappelle qu'il a manqué, et
ensuite tout est enregistré dans son journal ; il peut refermer le bandeau
simplement. »

### Pourquoi la demande ne touche pas votre §3

Vos trois raisons de rendre l'état terminal portent toutes sur la **machine à
états**, aucune sur le contenu de la route :

- **§3.a — la boucle retour ne balaie plus `NOT_ARRIVED`.** Vrai que la route
  la liste ou non : ce qui protège, c'est l'état, pas l'affichage ;
- **§3.d — la veille ne bloque plus un réarmement sur le même créneau.** Elle
  est close, `closedAt` est posé : la liste ne change rien à cela ;
- **§3.c — `ESCALATED` cessait de vouloir dire quelque chose.** C'est le nom que
  nous corrigeons, et il l'est.

Nous vous demandons donc : **`GET /watches/active` continue de rendre une veille
`NOT_ARRIVED` tant que `closedAt` a moins de 24 heures.** Un prédicat de plus
dans la requête, aucun champ nouveau, aucun type modifié.

### Ce que nous ferons de la ligne, pour que vous sachiez ce que vous servez

Une bande **ciel, pas corail** — ce n'est pas une alerte —, sans aucun nom de
contact puisque personne n'a été prévenu, portant « Tu n'as pas confirmé ton
arrivée · personne n'a été prévenu · c'est noté dans ton journal », et une croix
qui la referme pour toutes les pages. Aucun geste ne part vers vos routes depuis
cette bande. Elle est purement informative, et elle ne s'affiche qu'une fois :
une fois refermée, notre registre en mémoire la tait jusqu'au prochain démarrage
à froid.

Nous avons vérifié que ce que nous vous demandons ne casse rien d'autre chez
nous : le seul autre lecteur de la liste active — la carte de fin de séance —
filtre sur « peut-on la refermer par un code », faux sur une non-arrivée. Une
veille terminale y passe donc inaperçue, comme il faut.

### Si vous refusez, dites-le franchement

Nous avons une solution de repli — lire `GET /watches/history` et y filtrer les
`NOT_ARRIVED` récents — et nous ne l'aimons pas : c'est une lecture réseau de
plus **à chaque démarrage et sur toutes les pages**, pour un événement rare,
sur une API dont le plancher est d'environ 750 ms par requête authentifiée.
Nous la prendrons si votre réponse est non, mais nous préférons vous poser la
question avant de la payer.

---

## 4. Vos §2 et §2.3 : deux remarques

**Sur le ② parasite en production (votre §2)** — nous ne pouvons pas faire ce
que vous demandez : nous n'avons pas accès à vos journaux serveur, et l'app ne
garde aucune trace d'un message envoyé à un tiers. **Le relevé est chez vous**,
pas chez nous. Ce que nous pouvons dire : nos deux comptes de test n'ont armé
aucune veille avec un contact **membre** sur une séance qu'ils ont ensuite
manquée, donc notre campagne n'a pas pu le produire. S'il a réveillé quelqu'un,
c'est en dehors de nos essais.

**Sur votre §2.3** — vous avez eu raison de l'écrire plutôt que de le corriger
en silence. « Bien rentrée » affiché sur le lien hérité de quelqu'un qui n'est
jamais arrivé aurait été le pire résultat possible de tout ce lot, et il serait
passé inaperçu : personne ne va vérifier une page publique qui annonce une bonne
nouvelle. « En trajet » est le bon état.

---

## 5. Pas d'interrupteur : votre date suffit

Votre argument nous convainc, et votre honnêteté sur le redémarrage de service
compte pour moitié dans notre réponse : un interrupteur qui redémarre n'est pas
un retour arrière instantané, donc il ne vaut pas ce qu'on lui prêtait.

Sur le fond, vous avez raison : `NOT_ARRIVED` supprime les deux phrases fausses
au lieu d'en choisir une. Il n'y a plus d'intervalle à piloter.

**Livrez en un jet, et donnez-nous la date.** De notre côté, le lot client est
écrit et testé ; nous vous donnerons notre jour de sortie en production dès
qu'il est fixé, comme vous le demandez au §6.

---

## 6. ⚠️ Le défaut est vivant aujourd'hui, et nous l'avons reproduit à l'écran

Ceci a été trouvé **après** la rédaction des sections précédentes, sur un
signalement d'utilisateur, et cela change l'urgence de votre lot.

### Ce qui a été fait, geste par geste

Sur le simulateur, un compte réel, une veille armée sur une activité puis
escaladée faute d'arrivée — `ESCALATED`, `arrivalConfirmedAt` nul. Nous avons
essayé les trois issues de l'écran d'arrivée, l'une après l'autre, capture à
l'appui :

| Geste | Route | Votre réponse |
|---|---|---|
| « J'y suis » | `POST /watches/{id}/arrival` | *« L'arrivée ne peut être validée que sur une veille en attente d'arrivée. »* |
| « Je suis toujours en chemin » | `POST /watches/{id}/still-coming` | aucun effet visible |
| « Finalement je n'y vais pas » | `POST /watches/{id}/abandon` | *« Ce geste ne vaut que sur le trajet aller, avant l'arrivée. »* |

Et `close` demande un code qui n'a jamais existé.

### Ce que cela veut dire

**Une veille escaladée sans arrivée n'a aucune sortie.** Ce n'était pas une
conséquence théorique de votre §3.d — c'est l'état de la production. La personne
garde une veille ouverte indéfiniment, elle occupe ses veilles actives, et elle
**empêche d'armer une nouvelle veille sur le même créneau**. Aucun geste de
l'app n'en sort.

Notre part de responsabilité est écrite : nous avions offert ces trois boutons
sur cet écran en supposant que les verbes y étaient acceptés. Nous les avons
retirés — l'écran affiche maintenant une explication plutôt que trois refus, et
la bande de non-arrivée ne porte plus que sa croix. **L'app ne propose plus rien
qui échoue, mais elle ne propose plus rien du tout**, parce qu'il n'y a rien.

### Ce que nous vous demandons

1. **Votre lot `NOT_ARRIVED` devient urgent** : il referme à T+45, donc il
   supprime le problème à la racine. Nous n'avions pas mesuré à quel point ce
   §3.d était concret ;
2. **En attendant, une porte de sortie d'une ligne :** acceptez
   `POST /watches/{id}/abandon` sur une veille `ESCALATED` dont
   `arrivalConfirmedAt` est nul. C'est le geste dont le libellé dit déjà « ça
   désarme proprement : pas de code, pas de message, et pas de lapin », et
   c'est exactement ce que fait votre `NOT_ARRIVED` — en manuel, et tout de
   suite. Si vous préférez le faire porter par `DELETE /watches/{id}`, cela nous
   va aussi : dites-nous lequel des deux, nous poserons le bouton dessus ;
3. **Une question de contrat, tant que nous y sommes :** nous vous demandons la
   **table des états acceptés par verbe**. Nous avons inféré ces préconditions
   depuis le début, et ce défaut est le prix de l'inférence. Une colonne de plus
   dans votre table de routes suffirait.

Les personnes déjà bloquées ont besoin du 2 ; le 1 empêche que cela recommence.

---

## 7. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | `NOT_ARRIVED` terminal | **Accord**, écrit et testé côté app |
| 2 | La porte vers l'écran d'arrivée | **Retirée** sur `NOT_ARRIVED`, gardée sur `ESCALATED` sans arrivée |
| 3 | Votre §3.b (l'ancien bandeau lisait `ESCALATED`) | **Confirmé**, relevé dans notre dépôt |
| 4 | Lister une non-arrivée dans `/watches/active` 24 h après `closedAt` | **Demandé** — la seule chose que nous vous demandons |
| 5 | L'interrupteur du §6 | **Non merci**, une date suffit |
| 6 | Le relevé des ② parasites | **Chez vous** : nous n'avons pas les journaux |
| 7 | Sortie d'une veille `ESCALATED` sans arrivée | **Aucune aujourd'hui** — reproduit, §6. `abandon` ou `DELETE` à ouvrir |
| 8 | La table des états acceptés par verbe | **Demandée** — c'est l'inférence qui a produit le 7 |

Le 4 et le 7 appellent une décision de votre part, et **le 7 est le plus
pressé** : il y a des veilles bloquées en production. Le reste est un accord.
