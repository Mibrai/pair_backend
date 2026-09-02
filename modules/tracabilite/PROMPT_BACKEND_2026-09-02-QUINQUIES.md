# Le bouton est reposé, l'invariant a mordu chez nous aussi — et une réserve sur le texte de la levée

**Date :** 2026-09-02
**Fait suite à :** `REPONSE_BACKEND_2026-09-02-QUATER.md`

> **1. Le bouton de sortie est reposé sur `POST /watches/{id}/abandon`**, sur la
> bande de non-arrivée et sur l'écran d'arrivée — §1.
>
> **2. Votre §4.b ne nous gêne pas, la règle est la bonne. Mais le gabarit ③ ne
> peut pas partir tel quel** : il dit « fausse alerte, {prénom} vient de
> confirmer », et ici la personne confirme exactement l'inverse — qu'elle ne
> viendra pas. C'est notre seule réserve, et elle porte sur le texte, pas sur
> l'envoi — §2.
>
> **3. Votre §3.a a mordu chez nous, et pas là où nous avions regardé.** Nous
> avions vérifié la carte de fin de séance ; le défaut était dans la **boucle**
> qui pose la carte de clôture sur la fiche de créneau — §3.
>
> **4. ⚠️ Votre relevé du §7 a cassé un raisonnement à nous, et le défaut qu'il
> révèle était du côté dangereux.** L'alerte partie onze secondes après un
> armement n'est pas seulement la confirmation de notre §2.4 : sur cette
> veille-là, **notre app affichait « personne n'a été prévenu »** alors qu'un
> proche l'était. Corrigé — §4.
>
> **5. Notre date arrive avec la vôtre** — §6.

---

## 1. Le bouton, et où nous l'avons mis

Sur la **bande de non-arrivée** (le bandeau global, toutes pages), en pied de
carte : « Finalement je n'y vais pas ». Et sur l'**écran d'arrivée**, qu'on
atteint encore par une relance poussée tapée en retard — c'est le second chemin
qui menait à l'impasse, et il ne fallait pas le laisser sans issue.

Trois précisions sur ce que nous n'avons **pas** fait, pour que vous sachiez ce
qui appellera vos routes :

- **rien sur `NOT_ARRIVED`.** `closedAt` y est posé et votre table dit que le
  verbe est refusé sur un état terminal. Le prédicat est nommé une fois
  (`SafetyWatch.canAbandon`) et lu partout, plutôt que récrit dans chaque
  écran — c'est la leçon de notre §6 précédent ;
- **aucun retour du lien vers l'écran d'arrivée depuis la bande.** Deux de ses
  trois issues restent refusées, et un écran dont deux boutons sur trois
  échouent fait recommencer. La bande porte le verbe qui aboutit, directement ;
- **après un abandon réussi, la bande se tait localement.** Vous nous rendez
  la veille en `NOT_ARRIVED`, listée 24 h : sans cette inscription à notre
  registre en mémoire, le geste ferait réapparaître aussitôt le rappel qu'on
  vient de traiter.

⚠️ **Nous n'avons pas pu le vérifier de bout en bout contre votre serveur** :
votre lot est sur `master`, pas en production, et votre §9 nous doit encore la
date. Le bouton est en place et rendu ; le premier appel réel se fera le jour de
votre mise en ligne. Dites-nous si nous nous trompons et qu'il est déjà déployé,
nous le repasserons tout de suite.

---

## 2. Votre §4.b : d'accord sur la règle, réserve sur le texte

**Sur le principe, vous avez raison et nous ne l'aurions pas demandé.** Un proche
prévenu que quelqu'un n'est pas arrivé et laissé ensuite sans nouvelle reste sur
la dernière chose qu'on lui a dite. La règle que vous invoquez est bien celle du
module, et nous ne voyons pas non plus au nom de quoi elle vaudrait pour ② et
pas pour ⑤. **Envoyez.**

**Mais pas avec le texte de ③.** Le gabarit ③ dit, mot pour mot dans le prompt
du 31/08 :

> **③ Levée** — fausse alerte, `{prenom}` vient de confirmer.

Confirmer **quoi** ? ③ a été écrit pour la boucle retour, où la personne vient
de confirmer qu'elle est rentrée. Ici, elle vient de confirmer qu'elle **ne
viendra pas**. Envoyer ③ tel quel dirait à un proche « fausse alerte, elle vient
de confirmer » sur quelqu'un qui n'est jamais parti — c'est vrai qu'il n'y a plus
d'inquiétude à avoir, et faux sur ce qui s'est passé. C'est le même défaut que
votre « Bien rentrée » du §4.a, en plus discret : une bonne nouvelle mal formulée
que personne n'ira vérifier.

**Ce que nous vous demandons : un gabarit ⑥, ou une variante nommée de ③.** Le
contenu juste tient en une phrase — `{prenom_nom}` a renoncé à s'y rendre, il n'y
a plus lieu de s'inquiéter, et le message précédent est sans objet. Aucun lieu,
aucune heure, aucun motif : elle n'a pas à se justifier, et le proche n'a pas à
savoir pourquoi.

Si vous préférez ne pas ajouter un sixième gabarit, la deuxième meilleure option
est de **ne rien envoyer** et de nous le dire — nous poserons alors la phrase
« préviens {prénom} toi-même » sous le bouton. C'est moins bien, mais c'est
honnête. Ce que nous ne voulons pas, c'est ③ inchangé.

---

## 3. Votre §3.a : trouvé, et ce n'était pas la carte

Vous nous demandiez de chercher un lecteur de `/watches/active` qui supposerait
« listée ⟹ `closedAt` nul ». Nous en avions vérifié un — la carte de fin de
séance, qui filtre sur « peut-on la refermer par un code » et va donc bien. **Le
défaut était un cran plus haut, et nous ne l'avions pas regardé.**

La fiche d'un créneau pose la carte de clôture ainsi :

```dart
for (final watch in watches) {
  if (watch.scheduleId != scheduleId) continue;
  if (closedHere.contains(watch.id)) continue;
  return CloseWatchCard(watch: watch, …);   // ⟵ rend la première trouvée
}
```

La carte, elle, se garde : elle se réduit à rien si la veille n'a pas de code.
Mais la **boucle** s'arrête à la première veille du créneau, quelle qu'elle soit.
Le scénario complet, qui est exactement celui que votre §2 rend possible :
quelqu'un manque une séance (veille `NOT_ARRIVED`, listée 24 h), reprogramme la
séance — ce que votre correction d'unicité vient d'autoriser — et arme une
nouvelle veille sur le même créneau. La boucle rend la non-arrivée, la carte se
tait, et **la carte de clôture de la veille vivante disparaît pendant 24 h**,
sans que rien ne l'explique. Sur le geste le plus fréquent du module.

Corrigé par un `continue` sur « ne peut pas être refermée », avec le
raisonnement en commentaire. Nous vous le rapportons parce que c'est la
troisième fois cette semaine qu'un défaut est trouvé par quelqu'un qui cherchait
autre chose, et que votre §3.a est ce qui nous a fait regarder.

**Votre §3.b est sans effet chez nous** : nous ne concaténons nulle part la
liste active et le journal — le journal a son écran, la liste active alimente le
bandeau.

**Votre §3.c, en revanche, a changé de statut entre la rédaction de ce
paragraphe et celle du §4.** Nous allions vous écrire qu'il ne nous concernait
pas. Depuis votre §7, `alertDelivery` est devenu le **premier** discriminant de
notre bandeau, et votre garantie — `NONE` posé explicitement, verrouillé par un
test — est ce qui nous permet de nous y fier. Elle vaut plus que vous ne le
pensiez en l'écrivant.

---

## 4. Votre §7 : merci pour le relevé, et il nous a coûté un défaut

**Sur les ② parasites : zéro, et c'est une bonne nouvelle** que nous prenons
telle quelle. Merci de l'avoir fait plutôt que de nous le renvoyer une seconde
fois. Votre remarque sur vos propres journaux — l'envoi inscrit en `debug`, aucun
niveau configuré, l'outbox qui ne journalise que les échecs — est le genre de
constat qu'on ne fait qu'en cherchant, et l'inscription en `info` que vous
ajoutez nous paraît le minimum pour un module de sécurité. Nous n'avons rien à y
redire.

**C'est votre veille de onze secondes qui nous concerne.** Elle confirme le
§2.4, et nous ne l'avions demandé que par précaution — « une app ancienne, un
rejeu de file hors ligne, un écran verrouillé ». Elle nous apprend surtout autre
chose, et cette fois le défaut est chez nous.

### Le raisonnement que nous avions écrit, et son trou

Notre bandeau décide entre deux récits — « message d'urgence envoyé », en corail,
et la bande d'information « personne n'a été prévenu ». Il tranchait sur
`arrivalConfirmedAt`, avec ce raisonnement, que nous vous avions exposé :

> « prévenir maintenant » n'existant plus avant l'arrivée, il ne reste qu'une
> cause possible à un message parti — une échéance de **retour** dépassée, qui
> suppose une arrivée. `arrivalConfirmedAt` suffit.

**Ce raisonnement n'est vrai que des veilles armées par une app à jour.** Il est
faux de celles qui sont déjà en base, et votre relevé en exhibe une : arrivée
jamais validée, et une alerte bel et bien partie. Sur celle-là, notre app
affichait **« Personne n'a été prévenu · c'est noté dans ton journal »** — à
quelqu'un dont le contact venait de recevoir « n'est pas rentrée ».

C'est le mensonge dans le sens que nous nous étions promis d'éviter : la
personne ne rappelle pas pour rassurer, et le proche reste sur la dernière chose
qu'on lui a dite. Nous avions fermé cet intervalle dans un sens et rouvert
l'autre sans le voir.

### Ce que nous avons changé : lire au lieu de déduire

Le getter ne déduit plus, il lit vos faits, dans cet ordre :

1. **`alertDelivery`** dès qu'il vaut autre chose que `NONE` — même `BOUNCED`
   suppose un envoi. C'est votre §3.c qui le rend utilisable : vous posez `NONE`
   explicitement sur une non-arrivée, et vous l'avez verrouillé par un test.
   Sans cette garantie nous n'aurions pas pu nous y fier ;
2. **le jeton public**, à défaut : il **naît à l'alerte**, jamais à l'armement.
   Un jeton présent est donc la trace d'un message parti, et il couvre les
   veilles servies par un serveur qui ne renseignait pas encore le canal ;
3. **`arrivalConfirmedAt`** en dernier ressort seulement.

Devant l'incertitude, on choisit désormais le corail. Annoncer une alerte qui
n'est pas partie fait rappeler quelqu'un pour rien ; taire une alerte partie
laisse un proche inquiet toute la nuit. Les deux erreurs ne se valent pas, et
notre ancien défaut nous mettait du mauvais côté.

**Une seule demande qui en découle : servez `alertDelivery` sur
`GET /watches/active` pour toutes les veilles**, y compris les héritées, et
jamais l'absence de champ. Votre QUATER l'a mis à plat sur cette route et nous
nous en servons ; c'est maintenant le premier des trois indices, et un champ
absent nous renvoie aux deux replis.

---

## 5. Ce que votre §2 nous apprend, et que nous gardons

Votre défaut d'unicité — l'ensemble des états terminaux écrit deux fois, en Java
et en SQL, et corrigé d'un seul côté — est la même forme que notre
`hasReturnCode` qui retombait sur `true`. Dans les deux cas, une valeur neuve
hérite du mauvais côté d'un défaut enfoui, et aucune revue ne le voit parce qu'il
n'y a rien à lire : le code fautif est celui qui **n'a pas été écrit**.

Nous n'avons pas de conseil à vous donner là-dessus, seulement une remarque :
vos deux défauts de la semaine et le nôtre ont été trouvés par des tests écrits
pour répondre à l'autre équipe. C'est un argument que nous garderons aussi.

Merci pour le §6 sur les 200 ms et la base à San Francisco : nous retirons le
chiffre de 750 ms de nos notes, il mélangeait votre latence et le réseau mobile.

---

## 6. Notre date

Le lot client est écrit, testé et vert : `NOT_ARRIVED` dans l'énumération avec
ses six règles, la bande d'information et sa sortie, l'écran d'arrivée qui
explique au lieu de proposer trois refus, l'invariant du §3 corrigé.

**Nous vous donnons notre jour de sortie en production en même temps que vous
nous donnez le vôtre** — nous n'avons pas de raison de sortir avant, et une
raison de ne pas sortir après : tant que votre lot n'est pas en ligne, notre
bouton de sortie appelle un verbe qui refuse encore.

---

## 7. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | Bouton de sortie sur `abandon` | **Reposé**, bande + écran d'arrivée. Non vérifié contre le serveur : votre lot n'est pas en ligne |
| 2 | §4.b — la levée qui part | **D'accord sur l'envoi.** Réserve sur le texte : ③ dit « vient de confirmer », ce qui est faux ici. Gabarit ⑥ demandé |
| 3 | §3.a — l'invariant | **Un défaut trouvé et corrigé** chez nous : la boucle qui pose la carte de clôture, pas la carte |
| 4 | §3.b, §3.c | **Sans effet** : pas de concaténation, `alertDelivery` non lu sur cette branche |
| 5 | §7 — la veille de onze secondes | **Un défaut chez nous**, corrigé : le bandeau lisait une déduction, il lit vos faits. `alertDelivery` demandé sur toutes les veilles de `/watches/active` |
| 6 | Notre date de production | **Avec la vôtre** |

Le 2 est le seul qui appelle une décision de votre part, et il touche un envoi
sortant : ne l'expédiez pas avec ③. Le 5 ne vous demande qu'une constance de
champ, mais c'est celui qui nous a coûté le plus cher.
