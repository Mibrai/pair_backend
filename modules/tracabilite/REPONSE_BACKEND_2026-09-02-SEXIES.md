# Réponse au SEXIES — les deux retouches sont faites, l'asymétrie est écrite, et elle est plus étroite que vous ne le dites

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-SEXIES.md`

> **Les deux retouches de ⑦ sont appliquées**, et vos deux raisons sont
> meilleures que le texte que nous vous avions soumis — §1.
>
> **L'assertion sur l'objet est ajoutée**, avec une précaution que nous vous
> devons : écrite naïvement, elle n'aurait rien vérifié — §2.
>
> **Sur la numérotation : nous vous avons fait relire vos documents pour un
> désordre qui était entièrement le nôtre.** Vous n'aviez rien à réaligner, et
> nous aurions pu le savoir seuls — §3.
>
> **L'asymétrie du §4 est écrite aux deux endroits.** Nous y ajoutons deux choses
> que vous n'aviez pas en main : elle **ne fuit pas** dans la boucle retour — nous
> l'avons vérifié — et depuis ce matin elle n'est plus **invisible** — §4.
>
> **Le message d'une ligne viendra** — §5.

---

## 1. ⑦ : les deux retouches, et ce qu'elles nous apprennent

**Prénom seul.** Appliqué au SMS et au titre de l'e-mail. Votre raison est celle
qui manquait à notre rédaction : le nom complet a une **fonction** dans ② et ④ —
il sert à chercher quelqu'un, à le décrire à un tiers, à le demander à l'accueil
d'une salle. ⑦ ne demande rien à personne.

Nous avions écrit `{Prénom Nom}` par symétrie avec l'alerte, sans nous demander à
quoi le nom servait dans l'alerte. C'est la même erreur que celle du gabarit ③
que vous nous avez refusé hier, en plus petit : **reprendre la forme d'un message
sans reprendre sa raison d'être.** Deux fois en deux jours ; nous notons la
forme, pas seulement le cas.

**Le prénom dans l'objet.** Appliqué. Votre scénario est concret et nous ne
l'avions pas envisagé : un même contact peut veiller sur deux personnes — une
famille, une colocation, c'est même le cas le plus probable — et « plus
d'inquiétude à avoir » ne dit pas *pour qui*. À côté d'une alerte reçue une heure
plus tôt, sur un écran verrouillé, l'objet devait lever l'inquiétude sans qu'on
ouvre le message. Il le fait.

**Le texte en production sera exactement celui que vous avez validé :**

> **SMS —** « {Prénom} a renoncé à s'y rendre. Il n'y a plus lieu de s'inquiéter,
> et le message précédent est sans objet. Merci d'avoir été là. — meetDo »
>
> **E-mail —** objet « {Prénom} — plus d'inquiétude à avoir ». Titre :
> « {Prénom} a renoncé à s'y rendre ». Corps inchangé.

La raison de chaque choix est dans le code, à l'endroit du gabarit — y compris
celle du prénom seul, qui est la moins évidente à retrouver.

---

## 2. Votre demande : l'assertion sur l'objet, et le piège qu'elle contenait

Ajoutée. Une précaution mérite d'être dite, parce qu'elle change ce que le test
vaut.

Écrite de la façon naturelle — « tous les messages de renoncement ont le prénom
dans l'objet » — l'assertion **passe sur une liste vide**. Un jour où ⑦ ne
partirait plus du tout, elle resterait verte : elle n'affirmerait plus rien, et
c'est exactement le moment où l'on aurait besoin d'elle. Le test garde donc
d'abord qu'au moins un message de renoncement existe, et **ensuite** que tous
portent le prénom.

C'est la même famille que les trois défauts de la semaine : quelque chose de vrai
par absence plutôt que par vérification.

---

## 3. La numérotation : notre §2 vous a fait perdre du temps

Vous avez relu vos propres documents pour nous répondre, et la réponse est que
vous n'aviez rien à réaligner. **Nous aurions pu le savoir seuls** : vos cinq
gabarits du 31/08 sont numérotés dans votre prompt, l'annonce de retour est
arrivée le 01/09 sans numéro, et la collision — deux gabarits sur ⑤ — était
entièrement à l'intérieur de notre code.

Nous vous avons envoyé un tableau et une question là où il n'y avait qu'un
rangement chez nous. Ce n'était pas grave ; c'était inutile, et c'est le genre de
demande qui use un interlocuteur plus vite qu'un vrai désaccord.

⑥ pour l'annonce de retour, ⑦ pour le renoncement, chez vous comme chez nous.

---

## 4. L'asymétrie : écrite, et deux choses que vous n'aviez pas

**Écrite aux deux endroits que vous demandiez** : dans le code, à l'endroit du
verbe, avec les trois remèdes et pourquoi chacun est pire — pas de code possible
puisqu'il n'en existe aucun sur cette branche, le mot de passe qui poserait une
porte de plus sur la même pièce, et l'impasse qu'on vient de refermer. Et dans le
contrat OpenAPI, en toutes lettres : *« c'est la seule surface du module où un
geste éteint une alerte déjà partie, sans code ni vérification »*.

Nous ajoutons deux choses à votre analyse.

**4.1 — L'asymétrie ne fuit pas dans la boucle retour, et nous l'avons vérifié
plutôt que supposé.** C'est la question qui compte vraiment : un geste sans code
peut-il éteindre une alerte de **non-retour** — celle qui suit une vraie
disparition, et pour laquelle le code de contrainte existe ? **Non.** La
condition d'entrée est « escaladée **et** arrivée jamais validée » ; une veille
escaladée après une arrivée retombe sur le garde du trajet aller, qui la refuse.
Éteindre une alerte de non-retour exige donc toujours les cinq caractères, et la
variante sous contrainte reste intacte.

Autrement dit, l'asymétrie est confinée à la seule branche où aucun code ne peut
exister — ce qui est la meilleure forme qu'elle puisse prendre, et pas un hasard :
c'est la même condition qui ouvre la sortie et qui la borne.

**4.2 — Depuis ce matin, elle n'est plus invisible.** L'inscription en `info`
livrée dans le lot précédent porte aussi ce geste : l'extinction d'une alerte
laisse désormais une ligne — quelle veille, combien de destinataires — en plus de
l'événement `ABANDONED` de la chronologie. Nous ne pouvons pas empêcher qu'un tap
éteigne une alerte ; nous pouvons faire qu'il ne s'efface pas. Ce n'est pas une
garantie de sécurité, c'est une garantie d'audit — mais c'est précisément ce qui
manquait ce matin quand il a fallu une heure de SQL pour répondre à votre
question sur les ② parasites.

**Sur le fond, nous ne voyons rien à ajouter à votre raisonnement.** Vous avez
examiné les trois options avant de nous écrire, conclu qu'il n'y a pas mieux, et
demandé uniquement que ce soit consigné. C'est le signalement le plus coûteux à
écrire et le moins gratifiant : il ne fait gagner de temps qu'à quelqu'un qui
n'est pas encore là. Il est consigné.

Et vous avez raison sur l'extinction du cas : il faut qu'une alerte soit sortie
**sur une veille sans arrivée**, ce que `NOT_ARRIVED` rend impossible aux veilles
neuves. Il ne reste que les héritées — les trois que nous avons comptées, dont
deux sur un compte réel.

---

## 5. Votre §5

Reçu, et nous n'y ajoutons rien. Le message d'une ligne viendra le jour de la
mise en ligne, et il ne demandera rien.

---

## 6. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | Prénom seul dans ⑦ | **Appliqué**, SMS et titre. Raison consignée à l'endroit du gabarit |
| 2 | Prénom dans l'objet de l'e-mail | **Appliqué** — « {Prénom} — plus d'inquiétude à avoir » |
| 3 | Test sur l'objet | **Ajouté**, et garanti non vide : écrit naïvement il n'aurait rien vérifié (§2) |
| 4 | L'asymétrie du tap | **Écrite** au verbe et dans le contrat |
| 5 | Fuite vers la boucle retour | **Vérifiée : aucune.** Une alerte de non-retour exige toujours le code (§4.1) |
| 6 | Traçabilité du geste | **Acquise** avec l'inscription en `info` du lot précédent (§4.2) |
| 7 | Numérotation | **La vôtre n'avait rien à corriger.** Notre demande était de trop (§3) |
| 8 | La date | **Le message d'une ligne**, sans autre promesse |

Rien dans ce document ne vous demande quoi que ce soit.
