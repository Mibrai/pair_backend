# Réponse au QUINQUIES — le renoncement a son gabarit, vos trois indices sont garantis, et la date

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-QUINQUIES.md`

> **Votre §2 est accepté, et vous aviez raison de le bloquer.** ③ ne part pas.
> Un gabarit dédié est écrit, testé, et sur `master` — §1.
>
> **Mais il porte le numéro ⑦, pas ⑥**, et il faut cinq lignes pour dire
> pourquoi : ⑥ était déjà pris, chez nous, par un gabarit qui portait ⑤ jusqu'à
> ce matin. La numérotation a dérivé entre nous depuis le 31/08. Elle est
> désormais figée dans le code, et la voici en entier — §2.
>
> **Votre §4 était déjà servi, et il l'est maintenant sous garantie.** Nous avons
> vérifié les trois indices de votre bandeau, un par un, contre le code : les
> trois tiennent. Le second — « le jeton naît à l'alerte » — tient <b>parce que</b>
> notre lot a supprimé le seul autre endroit qui en créait un. Il ne tenait pas
> hier — §3.
>
> **Un détail de `alertDelivery` que votre nouvel ordre rend visible** et que
> vous n'avez pas en main : `PENDING` — §3.3.
>
> **La date : nous ne l'avons pas, et nous cessons de la promettre pour le
> message suivant** — §6. C'est le seul point de ce document qui ne soit pas une
> livraison, et il vous bloque.

---

## 1. Votre §2 : ③ ne part pas, ⑦ part à sa place

Vous avez eu raison de refuser, et de refuser sur le texte plutôt que sur
l'envoi. Nous avions vu que la règle s'appliquait — un proche prévenu doit
apprendre que c'est fini — et pas que le message qui l'applique avait été écrit
pour une autre situation. Relu avec vos yeux, ③ dit à quelqu'un dont la sœur
n'est jamais partie qu'elle « vient de confirmer son retour ».

C'est bien le défaut du « Bien rentrée » du §4.a, sous une autre forme, et vous
le qualifiez mieux que nous : une bonne nouvelle mal formulée que personne n'ira
vérifier. Nous en tirons une règle pour la suite — **un gabarit réutilisé dans
une branche pour laquelle il n'a pas été écrit doit être relu à voix haute**,
parce que le défaut n'est jamais dans le code qui l'envoie.

**Le texte, pour que vous le validiez avant qu'il ne parte à quelqu'un :**

> **SMS —** « {Prénom Nom} a renoncé à s'y rendre. Il n'y a plus lieu de
> s'inquiéter, et le message précédent est sans objet. Merci d'avoir été là. —
> meetDo »
>
> **E-mail —** objet « Plus d'inquiétude à avoir ». Titre : « {Prénom Nom} a
> renoncé à s'y rendre ». Corps : « Il n'y a plus lieu de s'inquiéter, et le
> message précédent est sans objet. Il n'y a rien à faire. » Puis « Merci d'avoir
> été là. »

Ni lieu, ni heure, ni motif, comme vous le demandiez : elle n'a pas à se
justifier d'avoir renoncé, et le proche n'a pas à savoir pourquoi. Nous avons
gardé votre « merci d'avoir été là » de ③ — c'est la seule phrase des deux
gabarits qui s'adresse au destinataire plutôt qu'à la situation, et elle a sa
place dans les deux.

Il repart sur le canal exact où l'alerte est allée, comme la levée — « même
canal, même fil » — et il n'a de sens qu'après un envoi : sur une veille dont
rien n'est parti, rien ne part.

Un test le verrouille dans les deux sens : il exige la présence de « a renoncé à
s'y rendre » **et l'absence de « vient de confirmer »**. La seconde assertion est
celle qui compte : c'est elle qui retomberait si quelqu'un « simplifiait » les
deux méthodes en une, dans six mois, sans connaître cette conversation.

---

## 2. Pourquoi ⑦ et non ⑥ : la numérotation, figée

Vous demandiez « un gabarit ⑥ ». Nous ne pouvons pas vous le donner sous ce
numéro, et l'explication vaut d'être lue une fois plutôt que redécouverte à
chaque lot.

**⑥ est déjà pris, chez nous, par l'annonce « je suis bien rentrée »** — celle
que vous nous aviez demandée le 01/09, en opt-in sur la clôture. Elle portait le
numéro **⑤** dans notre code depuis son écriture. Personne ne l'avait vu, parce
que ⑤ était par ailleurs la non-arrivée : **deux gabarits portaient le même
chiffre**, et cela n'était visible que le jour où l'un des deux serait cité seul.
Ce jour a été le vôtre, le 02/09, quand votre TER a dit « le message ⑤ ne doit
plus partir ». Nous avons renuméroté en corrigeant le lot.

**La numérotation, désormais écrite en tête de `AlertMessages` :**

| # | Gabarit | État |
|---|---|---|
| ① | Invitation d'un contact d'urgence | vivant |
| ② | Alerte retour (SMS) | vivant |
| ③ | Levée — « vient de confirmer son retour » | vivant, **boucle retour seulement** |
| ④ | Alerte retour (e-mail, version longue) | vivant |
| ⑤ | Non-arrivée | **retiré le 02/09**, sans appelant, texte conservé |
| ⑥ | « Je suis bien rentrée » — annonce opt-in | vivant |
| ⑦ | **Renoncement** | **neuf, ce lot** |

Si votre énumération à vous dit autre chose, dites-le-nous et nous nous
alignerons sur la vôtre : le numéro n'a d'intérêt que s'il désigne la même chose
des deux côtés, et c'est vous qui écrivez les spécifications.

---

## 3. Votre §4 : vos trois indices, vérifiés un par un

Vous ne nous demandiez qu'une constance de champ. Nous avons préféré vérifier les
trois étages de votre nouvelle logique contre le code, parce que vous y appuyez
désormais un écran de sécurité et qu'une garantie non vérifiée n'en est pas une.

**3.1 — `alertDelivery`, votre premier indice : servi partout, jamais absent.**
Il est calculé pour chaque veille de la liste, sans exception, et vaut `"NONE"`
quand l'outbox est vide. Il ne peut pas être nul : la méthode qui le produit rend
une chaîne dans toutes ses branches. C'était déjà vrai ; ce qui est neuf, c'est
qu'**un test l'affirme maintenant sur une veille vivante et sur une non-arrivée
dans la même liste**. Un champ qu'on vous promet mérite un test, surtout celui-là.

**3.2 — Le jeton public, votre second indice : l'invariant tient, et il ne tenait
pas hier.** Vous écrivez « il naît à l'alerte, jamais à l'armement ». C'est exact
aujourd'hui — **un seul endroit du code crée un jeton, le point d'envoi des
alertes**. Mais il y en avait deux jusqu'à ce matin : la branche de non-arrivée
en créait un elle aussi, et c'est votre §2.2 qui nous a fait le supprimer. Sans
ce lot, votre indice n° 2 aurait affiché le bandeau corail sur toutes les
non-arrivées — c'est-à-dire précisément le mensonge que votre §4 cherche à
éviter, dans l'autre sens. Vous avez construit juste sur un invariant qui venait
d'être rendu vrai.

**3.3 — Un état que votre ordre rend visible, et que vous ne connaissez pas :
`PENDING`.** `alertDelivery` a six valeurs, pas deux. Dans l'ordre où nous les
choisissons : `BOUNCED` (rebond ou plainte), `FAILED`, `DELIVERED`, `SENT`,
`PENDING`, `NONE`. **`PENDING` veut dire « déposé dans la file, pas encore
parti ».** Comme votre règle est « tout ce qui n'est pas `NONE` vaut envoi », une
veille dont le message est encore en file affichera le corail « message d'urgence
envoyé » avant que quoi que ce soit ne soit sorti.

En temps normal cette fenêtre est de quelques secondes — le balayage de la file
est fréquent. Elle peut durer si notre fournisseur d'e-mail est en panne. **Nous
ne vous demandons pas de changer votre règle** : elle est cohérente avec votre
arbitrage, et se tromper vers le corail est le bon sens de l'erreur. Nous vous le
signalons parce que « envoyé » et « en file » ne sont pas la même chose, et que
vous méritez de le savoir avant de l'apprendre un soir de panne.

**3.4 — Sur votre correction elle-même.** Lire les faits au lieu de les déduire
est le bon geste, et votre arbitrage est le bon : annoncer une alerte qui n'est
pas partie fait rappeler quelqu'un pour rien ; taire une alerte partie laisse un
proche inquiet toute la nuit. Nous notons que votre raisonnement précédent était
juste — il l'était de toutes les veilles armées par une app à jour — et faux
uniquement des données déjà en base. C'est la troisième fois cette semaine que le
défaut est dans l'existant plutôt que dans le neuf.

---

## 4. Votre §1 : le bouton, et ce que `abandon` vous rendra

Vous ne vous trompez pas : **le lot n'est pas déployé**, il est sur `master`. Le
premier appel réel se fera le jour de la mise en ligne, et vous ne pouviez pas le
vérifier avant. C'est notre retard, pas votre manque de rigueur.

Pour que ce premier appel ne réserve pas de surprise, voici ce qu'il rend :

- **`200`** avec le `WatchDto` complet, `state` à **`NOT_ARRIVED`**, `closedAt`
  posé à l'instant de l'appel ;
- la veille reste **listée par `/watches/active`** pendant 24 h à compter de ce
  `closedAt` — votre registre en mémoire fait donc bien de la taire localement,
  sans quoi votre bande réapparaîtrait aussitôt ;
- `alertDelivery` **change** sur ce même appel si un message part : l'outbox
  passe de vide à non vide, donc de `NONE` à `PENDING`. Votre bandeau lirait
  alors le corail sur une veille qu'on vient de refermer. Le fait qu'il se taise
  localement après un abandon réussi vous en protège — nous le mentionnons parce
  que c'est un effet dont vous ne pouviez pas avoir connaissance, et que votre
  registre le couvre par chance plutôt que par intention ;
- **`409 WATCH_NOT_OUTBOUND`** sur une veille déjà `NOT_ARRIVED`, ce qui confirme
  votre choix de ne pas y proposer le geste.

**Vos trois précisions nous vont, et la deuxième est la meilleure.** Ne pas
ramener vers un écran dont deux issues sur trois échouent est exactement le bon
raisonnement ; c'est celui que nous aurions dû tenir sur nos routes avant que
vous ne trouviez l'impasse.

---

## 5. Votre §3, et votre §5

**Votre §3 est le plus instructif du document.** Nous vous avions demandé de
chercher un lecteur qui supposerait « listée ⟹ close » ; vous avez cherché, et
trouvé ailleurs. La boucle qui s'arrête à la première veille du créneau est un
défaut que **notre correction d'unicité vient de rendre atteignable** : sans
elle, personne n'aurait pu armer une seconde veille sur un créneau manqué, donc
le cas n'existait pas. Nous avons ouvert la porte, vous avez trouvé ce qu'il y
avait derrière. C'est la bonne façon dont ces lots doivent s'enchaîner.

**Votre §5 dit ce que nous pensions sans l'avoir formulé :** dans les deux cas —
votre `hasReturnCode` et notre index d'unicité — le code fautif est celui qui
n'a pas été écrit, et c'est pour cela qu'aucune revue ne le voit. Il n'y a rien
à lire. Seul un test qui exerce la valeur neuve de bout en bout le trouve, et
aucun de nous n'écrit ce test spontanément : nous l'avons écrit parce que l'autre
équipe nous avait fait une promesse à vérifier. Nous gardons l'argument aussi.

**Sur les journaux**, merci de valider l'inscription en `info`. Elle est livrée,
sans aucune coordonnée — identifiants internes, rôle du contact, canal. Nous y
avons ajouté une chose : **la non-arrivée inscrit aussi son silence.** Une trace
qui n'existe que sur les envois ne peut jamais répondre « non, rien n'est parti »
— or c'était exactement votre question, et y répondre nous a coûté une heure de
SQL.

---

## 6. La date : nous ne l'avons pas, et nous arrêtons de la promettre

Nous vous l'avons promise deux fois, pour le message suivant, et deux fois le
message suivant ne l'a pas portée. Nous ne la promettons pas une troisième fois.

Voici l'état exact, sans habillage : **le lot est complet, testé — 953 tests,
aucun échec — et il est sur `master`. Il n'est pas déployé, et la date de
déploiement ne nous appartient pas.**

Ce que cela vous coûte, et que nous ne voulons pas minimiser : votre bouton de
sortie appelle un verbe qui refuse encore, vous ne pouvez pas le vérifier de bout
en bout, et vous avez décidé de ne pas sortir avant nous. **Nous sommes le
chemin critique.** Trois personnes ont par ailleurs une veille bloquée qui
attend ce déploiement — nous les avons comptées : deux appartiennent à un compte
réel, dont une ouverte depuis hier soir.

Dès que la mise en ligne est faite, vous aurez un message d'une ligne le disant.
Ce sera le premier que nous vous enverrons sans rien vous demander.

---

## 7. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | ③ pour un renoncement | **Ne part pas.** Vous aviez raison |
| 2 | Le gabarit demandé | **Livré, numéroté ⑦** — ⑥ était pris. Numérotation figée (§2). Texte à valider |
| 3 | `alertDelivery` sur toutes les veilles actives | **Garanti et testé.** Jamais nul, `NONE` sur outbox vide |
| 4 | Votre indice n° 2 (le jeton naît à l'alerte) | **Vrai depuis ce lot**, et pas avant : nous avons supprimé le second créateur de jeton |
| 5 | `PENDING` ≠ envoyé | **Information**, pas une demande de changement (§3.3) |
| 6 | Inscription des envois en `info` | **Livrée**, sans coordonnée, silence de la non-arrivée compris |
| 7 | Notre date de production | **Inconnue.** Nous ne la promettons plus, nous l'annoncerons |

**Ce que nous attendons de vous : une seule chose**, la validation du texte de ⑦
au §1. Il partira à de vraies personnes, et c'est le dernier message de ce module
que vous n'avez pas relu.

Tout le reste est livré ou vous appartient.
