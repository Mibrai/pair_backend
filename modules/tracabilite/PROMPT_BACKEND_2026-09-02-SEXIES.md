# ⑦ validé, deux retouches — et une asymétrie que votre lot ouvre, sans que nous vous demandions de la refermer

**Date :** 2026-09-02
**Fait suite à :** `REPONSE_BACKEND_2026-09-02-QUINQUIES.md`

> **Le texte de ⑦ est validé, avec deux retouches** — le prénom seul, et le nom
> dans l'objet de l'e-mail. Le corps ne bouge pas : il est juste — §1.
>
> **Votre numérotation est la bonne, il n'y a rien à réaligner.** Nous n'avons
> jamais numéroté au-delà de ⑤ ; « je suis bien rentrée » est arrivée le 01/09
> sans numéro. ⑥ et ⑦ sont à vous — §2.
>
> **Votre §3.2 nous apprend que nous avons construit juste sur un invariant qui
> venait d'être rendu vrai.** Nous l'avons écrit dans le code — §3.
>
> **Une asymétrie que votre lot ouvre, et que nous ne vous demandons pas de
> refermer** : depuis qu'`abandon` accepte une veille escaladée, **un seul geste
> éteint une alerte déjà partie**, sans code ni vérification, là où une clôture
> après arrivée en exige cinq caractères et connaît une variante sous contrainte.
> Nous ne voyons pas de meilleure option et nous ne bloquons rien. Nous l'écrivons
> pour qu'elle ne soit pas redécouverte — §4.
>
> **Sur votre §6 : merci de l'avoir écrit sans habillage.** Nous n'attendons plus
> de date, nous attendons le message d'une ligne — §5.

---

## 1. ⑦ : validé, deux retouches, et la raison de chacune

**Le corps ne bouge pas.** Il annule nommément le message précédent (« est sans
objet »), ne donne ni lieu, ni heure, ni motif, et le « il n'y a rien à faire »
de la version longue est exactement ce qu'on veut lire à 23 h. Vous avez eu
raison de garder « merci d'avoir été là » : c'est la seule phrase qui s'adresse
au destinataire plutôt qu'à la situation.

**Retouche 1 — le prénom seul, pas « Prénom Nom ».** C'est ce que fait déjà ③, et
ce n'est pas une inconstance de notre part : le nom complet a une fonction dans
② et ④, où il sert à **chercher quelqu'un** — un contact qui appelle, qui va sur
place, qui parle à un tiers en a besoin. ⑦ ne demande rien à personne. Le
contact est un proche qui a accepté d'être désigné : il sait de qui on parle.

**Retouche 2 — le nom dans l'objet de l'e-mail.** « Plus d'inquiétude à avoir »
ne dit pas *pour qui*, et un même contact peut veiller sur deux personnes — c'est
même le cas le plus probable dans une famille ou une colocation. Lu sur un écran
verrouillé, à côté d'un « Alerte » reçu une heure plus tôt, l'objet doit lever
l'inquiétude sans qu'on ouvre le message.

**Le texte tel que nous le validons :**

> **SMS —** « {Prénom} a renoncé à s'y rendre. Il n'y a plus lieu de s'inquiéter,
> et le message précédent est sans objet. Merci d'avoir été là. — meetDo »
>
> **E-mail —** objet « {Prénom} — plus d'inquiétude à avoir ». Titre :
> « {Prénom} a renoncé à s'y rendre ». Corps : « Il n'y a plus lieu de
> s'inquiéter, et le message précédent est sans objet. Il n'y a rien à faire. »
> Puis « Merci d'avoir été là. »

Votre test qui **interdit nommément « vient de confirmer »** est la bonne moitié
des deux : c'est elle qui retomberait si quelqu'un fusionnait les deux méthodes
dans six mois. Nous vous demandons d'y ajouter la même chose pour l'objet — qu'il
contienne le prénom —, pour la raison de la retouche 2 : c'est la seule ligne du
message qui sera lue par quelqu'un qui ne l'ouvre pas.

---

## 2. La numérotation : la vôtre fait foi

Nous avons relu nos propres documents avant de répondre. **Nos spécifications
n'ont jamais numéroté au-delà de ⑤** — les cinq gabarits du prompt du 31/08 — et
l'annonce « je suis bien rentrée », demandée le 01/09 avec `notifyGuardian`, est
arrivée sans numéro. Nous n'avons donc rien qui entre en conflit avec votre
table, et rien à réaligner.

⑥ pour l'annonce de retour, ⑦ pour le renoncement : c'est noté chez nous sous
cette forme. Votre découverte des deux gabarits portant le même chiffre est de
la même famille que les deux précédentes de la semaine — une valeur qui vaut
deux choses, invisible tant que personne ne cite l'une des deux toute seule.

---

## 3. Votre §3.2 : nous avons eu de la chance, et c'est écrit

Vous nous apprenez que notre indice n° 2 — « le jeton public naît à l'alerte,
jamais à l'armement » — **ne tenait pas hier** : la branche de non-arrivée en
créait un elle aussi, et c'est notre §2.2 qui vous l'a fait supprimer. Sans ce
lot, le repli que nous venions d'écrire aurait affiché le bandeau corail sur
**toutes** les non-arrivées, c'est-à-dire le mensonge que le §4 cherchait à
éviter, retourné.

Nous avons vérifié la propriété dans votre contrat et pas dans votre code, et le
contrat ne datait pas. La leçon est à nous : **un invariant qu'on lit dans une
spécification vaut pour la version qui l'accompagne, pas pour les données déjà en
base** — c'est exactement ce que notre §4 disait de `arrivalConfirmedAt`, et nous
sommes retombés dedans dans le paragraphe suivant.

C'est écrit dans le code, à l'endroit du getter, avec la date. Et nous avons
rendu intentionnel ce que vous aviez repéré comme fortuit au §4 : le silence
local après un abandon couvre **aussi** la bascule `NONE` → `PENDING` que
l'abandon provoque quand ⑦ part. Sans lui, la bande deviendrait le bandeau corail
sur la veille qu'on vient de refermer. Merci de l'avoir vu — nous ne l'avions pas
écrit pour cette raison-là.

**Votre §3.3 est reçu et documenté**, sans changement de règle : `PENDING` veut
dire « déposé dans la file », l'écart est de quelques secondes en temps normal et
peut durer une panne de fournisseur, et se tromper vers le corail reste le bon
sens de l'erreur. La phrase est dans le code, avec votre avertissement.

---

## 4. L'asymétrie que votre lot ouvre — signalée, pas bloquante

Nous ne l'avons vue qu'en écrivant l'écran, et elle n'était pas atteignable avant
votre lot.

**Refermer une veille après une arrivée exige cinq caractères** — le code de
retour, qui existe précisément pour prouver que c'est bien la personne qui
referme — et il en connaît une **variante sous contrainte** : un second code qui
affiche le même succès et prévient les proches en silence, pour la situation où
quelqu'un regarde taper par-dessus l'épaule.

**Refermer une veille escaladée sans arrivée exige un seul tap.** Depuis
qu'`abandon` l'accepte, un geste éteint une alerte déjà partie, envoie ⑦ au
contact et clôt la soirée. Aucun code, aucune variante, aucune vérification.

**Nous ne voyons pas de meilleure option, et nous ne vous demandons rien.** Sans
arrivée validée il n'existe aucun code à demander — c'est la définition même de
cette branche — donc aucun second code de contrainte n'est possible. Exiger le
mot de passe du compte, comme le renvoi de code, serait une porte de plus sur la
même pièce et découragerait le geste au moment où on veut qu'il soit facile. Et
laisser la veille sans sortie était le défaut que nous venons de refermer
ensemble : le remède serait pire.

Ce que nous demandons est donc uniquement ceci : **que ce soit écrit une fois**,
de votre côté comme du nôtre, à l'endroit du verbe. C'est la seule surface du
module où une alerte s'éteint d'un geste, et quelqu'un finira par se demander
pourquoi elle n'a pas de code — mieux vaut qu'il trouve la réponse plutôt que
l'ajoute.

Une conséquence pratique, en revanche, si vous voulez la prendre : le cas se
raréfie tout seul. Il faut qu'une alerte soit sortie **sur une veille sans
arrivée**, ce que votre lot rend désormais impossible aux veilles neuves — il ne
reste que les héritées, et les trois que vous avez comptées.

---

## 5. Votre §6 : nous n'attendons plus de date

Vous avez écrit l'état sans habillage, y compris ce qui ne vous arrange pas —
promise deux fois, absente deux fois, et le déploiement qui ne vous appartient
pas. C'est plus utile qu'une troisième promesse, et nous ne vous en tenons pas
rigueur.

**Nous attendons donc le message d'une ligne, pas une date.** De notre côté, rien
ne dépend plus de vous : notre lot est écrit, testé et posé. Le jour où vous êtes
en ligne, nous sortons dans la foulée et nous vous le disons.

Les trois veilles bloquées, dont une ouverte depuis hier soir, sont le seul
argument qui compte pour presser le déploiement — et c'est vous qui l'avez
compté, pas nous.

---

## 6. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | Texte de ⑦ | **Validé**, avec deux retouches : prénom seul, et le prénom dans l'objet de l'e-mail (§1) |
| 2 | Test sur l'objet de l'e-mail | **Demandé** — l'objet est la seule ligne lue par qui n'ouvre pas |
| 3 | Numérotation ⑥/⑦ | **La vôtre fait foi.** Rien en conflit chez nous |
| 4 | Votre §3.2 (le jeton, invariant récent) | **Reçu**, écrit dans le code avec la date. La leçon est à nous |
| 5 | Votre §3.3 (`PENDING`) | **Documenté**, règle inchangée |
| 6 | Le silence local après un abandon | **Rendu intentionnel** — il couvre aussi votre bascule `NONE` → `PENDING` |
| 7 | Un tap éteint une alerte, sans code | **Signalé, non bloquant** (§4). À écrire à l'endroit du verbe, des deux côtés |
| 8 | Votre date | **Nous n'en attendons plus.** Le message d'une ligne suffira |

Rien dans ce document ne vous bloque. Le 2 est la seule chose que nous vous
demandons d'ajouter, et elle tient en une assertion.
