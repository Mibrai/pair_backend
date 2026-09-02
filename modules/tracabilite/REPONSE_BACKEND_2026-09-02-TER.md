# Réponse au TER — la décision est prise, et couper ⑤ ne suffit pas à l'appliquer

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-TER.md`

> **Nous ne contestons pas la décision.** Le raisonnement du §1 tient : personne
> n'est parti, il n'y a ni trajet à surveiller ni lieu où chercher, et le prix du
> retrait est écrit noir sur blanc dans votre document. Nous l'appliquons.
>
> **Mais le retrait ne se fait pas en supprimant l'envoi.** Notre audit trouve
> que couper ⑤ tel quel **fait repartir ②** une heure plus tard, par la boucle
> retour. Le seul garde-fou qui l'en empêche aujourd'hui est un effet de bord de
> ⑤ lui-même : la ligne qu'il dépose dans l'outbox. Retirez la ligne, la porte
> s'ouvre — §1.
>
> **Et cette porte est déjà ouverte en production**, pour un cas précis que ni
> vous ni nous n'avions vu : quand le contact d'urgence est un membre meetDo,
> ⑤ n'écrit rien dans l'outbox, et ② part **aujourd'hui**, sans ce lot — §2.
>
> **Votre §3 est donc la réponse, pas une question secondaire :** nous posons un
> état distinct, `NOT_ARRIVED`, et il est **terminal**. C'est le seul geste qui
> referme la porte par construction plutôt que par un garde-fou — et, cadeau
> inattendu, **il rend votre §5 sans objet** : votre `WatchState.parse` protège
> les vieilles versions d'app mieux qu'un interrupteur ne le ferait — §3 et §7.
>
> **Vos quatre demandes du §2 sont acceptées, toutes les quatre**, avec une
> correction sur la façon de servir la 2.4 — §4.

---

## 1. Ce que votre décision déclenche chez nous : couper ⑤ fait repartir ②

C'est le cœur de cette réponse, et c'est le genre de chose qu'on ne voit qu'en
ouvrant le code.

`escalateNonArrival` pose l'état `ESCALATED` sur la veille. Or notre boucle
**retour** balaie trois états — `ON_SITE`, `REMINDING`, **`ESCALATED`** — dans une
fenêtre de six heures autour de l'échéance. Une veille de non-arrivée y entre
donc, mécaniquement, dès que `deadlineAt` est franchie : fin de créneau plus une
heure, soit une heure et quelques après le T+45 qui l'a marquée « perdue en
chemin ».

Ce qu'elle y trouve est `ensureAlerted`, le point unique d'envoi des alertes ②.
Et ce qui empêche ② de partir aujourd'hui n'est pas un test sur la non-arrivée —
il n'y en a aucun. C'est ceci :

```java
boolean alerteDejaPartie = !outboxRepository.findByWatchId(watch.getId()).isEmpty();
```

**L'outbox n'est non vide que parce que ⑤ vient d'y déposer un e-mail.** Le
garde-fou qui protège aujourd'hui la branche aller est un effet de bord du
message que vous nous demandez de retirer.

Supprimez l'appel à ⑤ et rien d'autre : la veille reste `ESCALATED`, l'outbox
reste vide, la boucle retour la ramasse à l'échéance, et le contact d'urgence
reçoit l'alerte retour ② — « n'est pas rentrée » — pour quelqu'un qui n'est
jamais parti. Puis le contact de secours, à +75 minutes. C'est exactement ce que
votre décision retire, avec une heure de retard et un message qui envoie
chercher au mauvais endroit.

Nous vous le signalons parce qu'un lot rédigé comme « retirez un envoi » aurait
pu être servi littéralement, et l'aurait été à tort.

---

## 2. Le même défaut est déjà en production, quand le contact est un membre

En vérifiant le point précédent, nous en avons trouvé un second, qui ne dépend
pas de ce lot et qui vaut aujourd'hui.

Quand le contact d'urgence a un compte meetDo, l'envoi ⑤ prend une autre
branche : notification in-app, puis retour immédiat. **Rien n'entre dans
l'outbox.** Le garde-fou du §1 ne tient donc pas, et la boucle retour envoie ②
au même contact à l'échéance : une notification in-app d'alerte retour, plus un
e-mail « Alerte retour — meetDo », pour une personne qui n'est jamais arrivée.

Aucun de nos tests ne couvrait ce chemin : le test d'intégration de la boucle
aller n'arme qu'avec un contact externe, et il s'arrête au message ⑤ sans jamais
avancer jusqu'à l'échéance de retour.

Ce défaut disparaît avec le correctif du §3 — c'est la même cause. Nous le
mentionnons séparément parce qu'il ne vous est pas imputable, qu'il n'attend pas
votre accord, et que si vous avez des non-arrivées avec contact membre dans vos
relevés, elles ont probablement produit un ② que personne n'a expliqué.

---

## 3. Notre réponse à votre §3 : `NOT_ARRIVED`, et il est terminal

Vous nous laissiez le choix entre garder `ESCALATED`, poser un état distinct, ou
refermer en `CLOSED`. Nous posons **`NOT_ARRIVED`**, et nous le rendons
**terminal**. Quatre raisons, dans l'ordre où elles pèsent :

**a. Elle referme la porte du §1 par construction.** La boucle retour ne balaie
pas `NOT_ARRIVED`. ② ne peut plus partir parce que la veille n'est plus dans le
champ de vision de la boucle — pas parce qu'un garde-fou tient. C'est une
différence de nature : les garde-fous se contournent, les états ne se balaient
pas.

**b. Elle protège vos versions d'app anciennes mieux qu'un interrupteur.** C'est
votre propre phrase qui nous le donne : « `WatchState.parse` rend `armed` sur
l'inconnu ». Une app pas encore mise à jour, qui lisait l'état pour décider du
bandeau, lira `armed` et **n'affichera pas** le bandeau corail « message
d'urgence envoyé ». Si nous gardions `ESCALATED`, cette même app l'afficherait
pour une alerte que nous ne serions plus en train d'envoyer — c'est-à-dire le
mensonge **dans le sens dangereux**, celui qui fait croire qu'un proche a été
prévenu. Votre §5 accepte un intervalle où la phrase fausse est « personne n'a
été prévenu » alors qu'un message part ; `ESCALATED` nous ferait ouvrir un second
intervalle, dans l'autre sens, sur les app anciennes. `NOT_ARRIVED` ferme les
deux.

*Un point à confirmer de votre côté :* nous déduisons de votre §4 (« le bandeau
lit **maintenant** `arrivalConfirmedAt` et non l'état ») que l'ancien bandeau
s'accrochait à `ESCALATED`. Si c'est bien le cas, le raisonnement ci-dessus
tient ; sinon dites-le-nous, il ne change pas notre choix mais il change ce que
nous vous promettons sur l'intervalle.

**c. `ESCALATED` cessait de vouloir dire quelque chose.** Vous l'avez écrit mieux
que nous : le mot signifie « un message est parti à un tiers ». Il allait mentir
dans nos journaux, nos métriques et notre page publique.

**d. Terminal, parce que la veille n'a plus rien à surveiller — et parce que
sinon elle ne se referme jamais.** Ceci répond à votre seconde question, et le
constat est moins confortable que la théorie : aujourd'hui une veille de
non-arrivée reste `ESCALATED` **indéfiniment**. Le minuteur aller ne balaie que
`ARMED` et `EN_ROUTE` : après T+45, plus rien ne l'avance. Elle occupe « mes
veilles actives », et elle **empêche d'armer une nouvelle veille sur le même
créneau**, jusqu'à ce que la personne pense à faire « je n'y vais pas ». La porte
que vous avez gardée dans la bande d'information n'est pas un confort : c'est
aujourd'hui la seule sortie.

**Donc : nous refermons côté serveur à T+45, et vous pouvez retirer la porte.**
La veille passe `NOT_ARRIVED`, `closedAt` est posé, elle quitte les veilles
actives et entre au journal.

**Ce que « terminal » ne change pas :** notre série de retours confirmés se
calcule sur les **événements**, jamais sur l'état. `LOST_ON_THE_WAY` reste écrit,
l'incident reste journalisé, et la série se comporte exactement comme avant. Le
§6 de votre TER est respecté à la lettre.

---

## 4. Vos quatre demandes, une par une

**2.1 — Ne plus envoyer ⑤ : oui.** L'envoi disparaît. L'organisateur reste
prévenu en in-app avec le nom, l'absence de validation et l'heure. L'incident
reste journalisé. Aucune ligne d'absence, comme aujourd'hui.

**2.2 — Ne plus créer le lien : oui.** Nous supprimons la création du jeton dans
cette branche. Votre argument est le bon et il vaut la peine d'être répété : un
jeton sans destinataire est un jeton qui ne peut que fuir. Notez qu'il fallait
d'abord refermer le §1 — sans cela, la boucle retour aurait recréé le jeton une
heure plus tard, en envoyant ② avec.

**2.3 — Jamais « alerte envoyée » pour une non-arrivée : oui, et attention au
piège.** Notre projection publique teste d'abord les états terminaux, et rend
« Bien rentrée » pour tout ce qui est terminal. Rendre `NOT_ARRIVED` terminal
sans toucher à cette projection afficherait donc **« Bien rentrée »** sur un
lien hérité — infiniment pire que « alerte envoyée ». Le test de `NOT_ARRIVED`
passe donc **avant** le test terminal, et rend « En trajet », comme vous le
demandez. C'est le seul endroit du lot où une inattention aurait coûté cher, et
c'est pour cela que nous l'écrivons plutôt que de le corriger en silence.

**2.4 — `panic` en 409 sans arrivée validée : oui, mais pas comme notre code
l'aurait fait spontanément.** Nous avons déjà un garde `exigerSurPlace`, utilisé
par le snooze et l'interruption. **Nous ne l'employons pas ici**, parce qu'il
raisonne sur l'état et refuse `ESCALATED` : une personne bien arrivée, dont la
veille a déjà escaladé faute de retour confirmé, perdrait le bouton d'alerte au
moment précis où elle en a le plus besoin. Le critère est celui que vous avez
écrit — **l'arrivée est-elle validée** — donc `arrivalConfirmedAt == null` → 409.
Rien d'autre.

Votre raison de le demander malgré le retrait du bouton est la bonne, et nous la
partageons : une app ancienne, un rejeu de file hors ligne, un écran verrouillé.
Le serveur doit refuser ce que l'app ne propose plus.

**Code d'erreur rendu :** `WATCH_NOT_ON_SITE`, avec le message « Ce geste suppose
une arrivée validée. » — le même que pour le snooze et l'interruption, pour que
vous n'ayez pas un cas de plus à traiter.

---

## 5. Ce que devient une non-arrivée, de bout en bout

Pour qu'il n'y ait pas de zone grise, voici la branche entière après ce lot :

| Moment | Ce qui se passe |
|---|---|
| début +15 / +30 / +45 | « tu y es ? » à la personne, en push, comme aujourd'hui |
| T+45, troisième sans réponse | état **`NOT_ARRIVED`**, `closedAt` posé |
| | notification in-app à l'organisateur : nom, absence de validation, heure |
| | incident `LOST_ON_THE_WAY` journalisé, événement inscrit à la chronologie |
| | **aucun message au contact d'urgence**, **aucun jeton public créé** |
| | aucune ligne d'absence, aucun effet sur la fiabilité ni sur les badges |
| ensuite | plus rien. La veille est close, elle n'est plus balayée par aucune boucle |
| lien hérité, s'il en existe | la page publique dit **« En trajet »** |

---

## 6. Sur votre §5 : nous n'avons pas besoin de l'interrupteur, et voici pourquoi

Vous demandiez une date, ou mieux un interrupteur basculable sans redéploiement.
Nous vous devons une réponse franche sur les deux.

**Sur l'interrupteur, techniquement :** nous en avons un du même genre pour le
canal SMS, et il est piloté par une variable d'environnement lue **au
démarrage**. La basculer sur notre hébergeur ne redéploie pas de code, mais
**redémarre le service**. Ce n'est pas « à chaud ». Nous préférons vous le dire
que vous laisser compter dessus.

**Sur le fond : il ne sert plus à rien.** L'interrupteur existait pour choisir le
moment du croisement, parce que chaque ordre d'arrivée avait sa phrase fausse.
`NOT_ARRIVED` les supprime toutes les deux : votre app à jour lit
`arrivalConfirmedAt` et dit vrai ; votre app ancienne lit un état inconnu, rend
`armed`, et n'affiche aucun bandeau d'alerte — donc ne ment pas non plus. Il n'y
a plus d'intervalle à piloter.

**Notre réponse est donc une date, pas un interrupteur :** nous livrons ce lot en
un jet, avec les tests, et nous vous donnons la date de mise en production dès
que la recette est passée. Si vous préférez malgré tout l'interrupteur — parce
que vous voulez pouvoir revenir en arrière sans nous attendre — dites-le, nous
l'ajoutons ; c'est peu de travail, et c'est votre appel, pas le nôtre.

Ce que nous vous demandons en échange : **le jour de votre sortie en
production**, pour que nous sachions à partir de quand la population d'app
anciennes décroît.

---

## 7. Ce qui ne change pas

- **`POST /watches/{id}/resend-code`** continue de refuser sans arrivée validée.
  Nous n'y touchons pas, comme vous le demandez ;
- **la boucle retour est intacte.** ② puis ④, la relance du contact de secours à
  +75 minutes, la levée ③ : rien de tout cela ne bouge. Le seul changement est
  qu'une veille de non-arrivée n'y entre plus — et elle n'aurait jamais dû ;
- **les gabarits ①②③④** sont inchangés, e-mail seul, SMS toujours éteint ;
- **`RETURN_ANNOUNCED`, `consecutiveConfirmedReturns`, `notifyGuardian`, `role`**
  et la chronologie se comportent comme le décrit notre réponse précédente ;
- **`LOST_ON_THE_WAY`** reste dans le journal et dans la chronologie. Ce qui
  disparaît est le message au tiers, pas la trace — exactement comme vous
  l'écrivez ;
- **le gabarit ⑤ lui-même n'est pas supprimé du code.** Il n'a plus d'appelant.
  Nous le gardons, documenté comme retiré et daté de cette décision, parce qu'une
  décision produit peut se reprendre et que réécrire un message d'urgence de
  mémoire est une mauvaise façon de la reprendre.

---

## Récapitulatif des changements de contrat

| Élément | Changement |
|---|---|
| `WatchState` | **nouvelle valeur `NOT_ARRIVED`**, terminale. Aucune valeur retirée, aucune renommée |
| `GET /api/watches/active` | une veille de non-arrivée n'y figure plus (elle est terminale) |
| `GET /api/watches/history` | elle y figure désormais, avec `state: "NOT_ARRIVED"` |
| `WatchDto.publicToken` | reste **nul** sur une non-arrivée : plus aucun jeton n'y est créé |
| `POST /api/watches/{id}/panic` | **`409 WATCH_NOT_ON_SITE`** tant que `arrivalConfirmedAt` est nul |
| page publique `/public/watch/{token}` | une non-arrivée affiche **« En trajet »**, jamais « Alerte envoyée », jamais « Bien rentrée » |
| e-mail ⑤ au contact d'urgence | **ne part plus** |

Aucun champ ajouté, aucun retiré, aucun type modifié. Le seul élargissement est
une valeur d'énumération de plus — celle que votre `parse` tolérant absorbe déjà.

---

**Ce que nous attendons de vous, dans l'ordre :**

1. **Un accord sur `NOT_ARRIVED` terminal** — c'est la seule décision de ce lot
   qui vous coûte quelque chose : vous retirez la porte vers l'écran d'arrivée
   (§3.d), puisque le serveur referme désormais lui-même ;
2. **Une confirmation** que votre ancien bandeau s'accrochait bien à `ESCALATED`
   (§3.b) ;
3. **Le jour de votre sortie en production**, et un mot si vous voulez malgré
   tout l'interrupteur du §6 ;
4. **Un coup d'œil à vos relevés** pour d'éventuelles non-arrivées avec contact
   membre : le ② parasite du §2 est en production aujourd'hui, et il a pu réveiller
   des gens pour rien.
