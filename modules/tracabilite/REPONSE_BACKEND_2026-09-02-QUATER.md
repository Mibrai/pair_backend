# Réponse au QUATER — votre §3 accordé, votre §6 confirmé et refermé, et un défaut de plus trouvé au passage

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-QUATER.md`

> **Le lot est écrit, testé, et il est sur `master`.** `NOT_ARRIVED` terminal,
> ⑤ retiré, aucun jeton créé, `panic` en 409 sans arrivée, la page publique en
> « En trajet ».
>
> **Votre §3 est accordé : oui, une non-arrivée reste listée 24 h.** Votre
> raisonnement était juste et nous l'avons vérifié ligne à ligne : aucune de nos
> trois raisons de rendre l'état terminal ne portait sur le contenu de la route.
> Et l'effet que vous décrivez était réel — nous aurions livré un lot où
> l'organisateur est prévenu et la personne concernée ne l'est pas — §1.
>
> **Le test que nous avons écrit pour tenir notre promesse du §3.d l'a trouvée
> fausse.** L'ensemble des états terminaux est écrit **deux fois** chez nous, en
> Java et en SQL, et nous n'avions modifié que le premier. Réarmer une veille sur
> un créneau manqué rendait un **500** — §2. C'est notre deuxième défaut de la
> semaine trouvé par un test écrit pour vous répondre, et pas par une revue.
>
> **⚠️ Votre §6 est confirmé, et la porte de sortie est livrée.** Nous avons
> relu les sept verbes un par un : votre relevé est exact, et il était même un
> cran plus fermé que vous ne l'avez vu — `snooze` et `interrupt` refusent aussi,
> et notre `panic` de ce matin ferme le dernier. `abandon` accepte désormais une
> veille escaladée sans arrivée — §4. Nous y avons ajouté une chose que vous ne
> demandiez pas et qui nous paraît non facultative : **la levée part** si une
> alerte était réellement sortie.
>
> **Votre §6.3 — la table des états acceptés par verbe — est livrée aussi**, et
> pas dans ce document : dans le contrat OpenAPI, verbe par verbe, pour qu'elle
> ne se désynchronise pas de la prochaine réponse écrite à la main — §5.
>
> **Trois conséquences à connaître avant de brancher** votre bande d'information
> — §3. Une seule demande une vérification de votre côté.

---

## 1. Votre §3 : accordé, et voici ce que `GET /watches/active` rend désormais

Nous avons repris vos trois réfutations dans le code avant de dire oui. Elles
tiennent toutes les trois :

- la boucle retour filtre sur `ON_SITE, REMINDING, ESCALATED` — elle ne consulte
  jamais cette route ;
- le réarmement passe par un test d'existence sur l'ensemble terminal, pas par
  la liste (voir §2, où précisément ce point nous a mordus) ;
- le nom `ESCALATED` est corrigé par l'état neuf, indépendamment de l'affichage.

Vous aviez raison : le besoin est d'affichage, la terminalité est une affaire de
machine à états, et les deux ne se croisent pas.

**Et votre argument de fond emportait la décision à lui seul.** Après T+45,
l'organisateur reçoit une notification et la personne concernée n'en reçoit
aucune : sa soirée est classée perdue en chemin, un incident est journalisé à son
nom, et rien dans l'app ne le lui dit. Nous aurions livré cela sans vous. C'est
écrit noir sur blanc dans le code, à l'endroit qui sert la route, pour que
personne ne « simplifie » la requête dans six mois :

> *Une non-arrivée est terminale et n'aurait donc rien à faire ici — sauf que
> cette liste est le seul endroit où la personne concernée apprend que sa soirée
> a été classée perdue en chemin.*

**Servi comme vous le demandiez :** `state NOT IN (terminaux)` **OU**
`state = NOT_ARRIVED AND closedAt > maintenant − 24 h`. Un prédicat de plus,
aucun champ nouveau, aucun type modifié. Un index partiel sur
`(user_id, closed_at)` restreint aux `NOT_ARRIVED` le sert sans balayer le
journal.

**Ce que nous n'avons pas fait, et c'est important :** nous n'avons **pas**
retiré `NOT_ARRIVED` de l'ensemble terminal pour obtenir cet affichage. C'eût été
le raccourci évident, et il aurait rendu la non-arrivée bloquante pendant 24 h —
exactement le défaut que la terminalité venait de refermer. La visibilité est
servie par une requête à part. Le §2 raconte ce qui arrive quand ces deux plans
cessent de dire la même chose.

---

## 2. Ce que le test a trouvé : notre promesse du §3.d était fausse en base

Nous vous avions écrit, pour justifier la terminalité, qu'une non-arrivée
« ne bloque plus un réarmement sur le même créneau ». Nous avons écrit le test
qui le vérifie. **Il est tombé sur un 500.**

L'unicité « une seule veille vivante par créneau et par personne » est un index
partiel, et il **énumère les états terminaux en SQL** :

```sql
WHERE state NOT IN ('RESOLVED', 'CLOSED')
```

Cette liste est la jumelle de celle que nous tenons en Java, et nous n'avions
modifié que la seconde. Résultat : le service considérait la veille comme
terminale et autorisait le réarmement ; la base, elle, voyait encore une veille
vivante et refusait l'insertion. Quelqu'un qui manque une séance et la
reprogramme aurait reçu une erreur serveur — sur le geste même que la
terminalité était censée lui rendre.

C'est corrigé dans la migration du lot, avec le commentaire qui dit pourquoi les
deux listes doivent rester d'accord.

Nous vous le rapportons pour deux raisons. La première est que nous vous avions
promis le contraire, et qu'une promesse fausse doit être reprise nommément. La
seconde est que c'est **le deuxième défaut de cette semaine trouvé par un test
écrit pour vous répondre** — le premier étant le ② parasite du contact membre.
Aucune revue de code ne les avait vus. Cela règle un débat que nous avions en
interne sur le coût de ces allers-retours.

---

## 3. Trois conséquences à connaître avant de brancher votre bande

**a. `/watches/active` peut désormais rendre une veille dont `closedAt` n'est pas
nul.** C'est le seul cas, et c'est nouveau. Vous avez vérifié la carte de fin de
séance ; nous vous signalons l'invariant lui-même, parce que tout autre lecteur
qui supposait « listée ⟹ non close » se trompera ici. Il vaut la peine de le
chercher chez vous plutôt que de le découvrir sur un écran.

**b. Pendant ces 24 h, la veille figure dans `/watches/active` **et** dans
`/watches/history`.** C'est voulu, et cohérent avec votre bande — « c'est noté
dans ton journal » est vrai au moment où elle s'affiche, pas seulement le
lendemain. Mais si vous construisez un écran qui concatène les deux routes, la
ligne y apparaîtra deux fois.

**c. `alertDelivery` vaut `"NONE"`**, et `publicToken` est nul. Vérifié dans le
code et verrouillé par un test, puisque votre bandeau global lit ce champ et
qu'un `BOUNCED` y signifierait « le proche n'a pas été joint » : il ne peut pas
se déclencher sur une non-arrivée, aucun message n'étant jamais parti.

---

## 4. Votre §6 : confirmé verbe par verbe, et refermé

Vous aviez raison, et nous l'avons vérifié dans le code plutôt que sur écran.
L'impasse est réelle, et elle est un peu plus fermée que votre tableau ne le
montrait — vous n'aviez essayé que les trois gestes que votre écran offrait :

| Geste | Ce qui le refuse | Verdict |
|---|---|---|
| `arrival` | l'état n'est ni `ARMED` ni `EN_ROUTE` | refusé |
| `still-coming` | idem — `409 WATCH_NOT_OUTBOUND` | refusé |
| `abandon` | idem | refusé |
| `close` | exige une ligne de code de retour, qui n'a jamais existé | refusé |
| `snooze` | exige `ON_SITE` ou `REMINDING` | refusé |
| `interrupt` | idem | refusé |
| `DELETE` | exige `ARMED` seul | refusé |
| `panic` | acceptait — et **notre lot de ce matin le ferme** | refusé désormais |

Huit verbes, zéro sortie. Nous confirmons donc votre diagnostic sans réserve :
la veille reste ouverte, elle occupe les veilles actives, et elle bloque
l'armement d'une nouvelle sur le même créneau. `NOT_ARRIVED` empêche qu'il s'en
crée d'autres ; **il ne libère pas celles qui y sont déjà**, et c'est pour cela
que votre demande était la bonne.

Une note au passage sur `panic` : il était le seul verbe accepté, mais ce n'était
pas une sortie — il faisait partir une alerte au lieu de refermer. Notre 409 de
ce matin l'aurait donc transformé en huitième refus si nous n'avions rien fait
d'autre. Les deux moitiés de ce lot devaient partir ensemble.

**Le choix du verbe : `abandon`, pas `DELETE`.** Vous nous laissiez le choix.
`DELETE` porte « désarmer avant départ » — rien n'est parti, rien n'a eu lieu — et
c'est faux ici : trois relances sont sorties, un incident est journalisé, et sur
les veilles héritées un message est parti à un tiers. `abandon` porte « je n'y
vais pas », qui est exactement ce qui s'est passé. **Posez votre bouton sur
`POST /watches/{id}/abandon`.**

**Deux détails que nous avons tranchés seuls, et qu'il vaut mieux que vous
sachiez :**

**a. Elle se referme en `NOT_ARRIVED`, jamais en `CLOSED`.** Ces veilles-là ont
un jeton public **déjà distribué** — l'ancienne branche en créait un. `CLOSED`
est terminal, et notre projection publique rend « Bien rentrée » sur un état
terminal : refermer en `CLOSED` aurait annoncé au proche de quelqu'un qui n'est
jamais arrivé qu'il est bien rentré. C'est le piège de votre §4, rencontré une
seconde fois, par une autre porte — et cette fois nous l'attendions.

**b. Si une alerte était réellement partie, la levée part avec.** Vous ne le
demandiez pas. Mais ces veilles héritées ont fait sortir le message ⑤ : un proche
a été prévenu que quelqu'un n'était pas arrivé, et refermer sans rien lui dire le
laisserait indéfiniment sur la dernière chose qu'on lui a dite. C'est la règle
que le module applique déjà à la clôture par code — « quelqu'un réveillé par une
alerte doit apprendre qu'elle est levée » — et nous ne voyons pas au nom de quoi
elle vaudrait pour ② et pas pour ⑤. La levée repart sur le canal exact où
l'alerte est allée. Sur une veille où rien n'était parti, rien ne part.

Dites-nous si le **b** vous gêne : c'est le seul endroit de ce lot où nous avons
élargi votre demande, et il touche un envoi sortant.

---

## 5. Votre §6.3 : la table des états, et où nous l'avons mise

Votre demande est juste, et le reproche implicite l'est aussi : vous avez inféré
ces préconditions depuis le début parce que nous ne les avions écrites nulle
part. Le §6 est le prix de cette inférence, et il se paie chez vos
utilisateurs.

**Nous ne vous rendons pas un tableau dans un document**, parce qu'un tableau
écrit à la main dans une réponse est faux dès la livraison suivante. Les
préconditions sont désormais **dans le contrat OpenAPI, sur chaque verbe**, avec
le code d'erreur rendu quand elles ne sont pas remplies. Vous les lisez au même
endroit que le reste de la route :

| Verbe | États acceptés | Refus |
|---|---|---|
| `POST /arrival` | `ARMED`, `EN_ROUTE` | `WATCH_ARRIVAL_NOT_EXPECTED` |
| `POST /still-coming` | `ARMED`, `EN_ROUTE` | `WATCH_NOT_OUTBOUND` |
| `POST /seen-by-host` | `ARMED`, `EN_ROUTE` | `WATCH_NOT_OUTBOUND` |
| `POST /abandon` | `ARMED`, `EN_ROUTE`, **et `ESCALATED` tant que `arrivalConfirmedAt` est nul** | `WATCH_NOT_OUTBOUND` |
| `POST /snooze` | `ON_SITE`, `REMINDING` | `WATCH_NOT_ON_SITE` |
| `POST /interrupt` | `ON_SITE`, `REMINDING` | `WATCH_NOT_ON_SITE` |
| `POST /panic` | veille non close **et arrivée validée** | `WATCH_NOT_ON_SITE` |
| `POST /resend-code` | exige un code existant, donc une arrivée validée | `WATCH_NOT_ON_SITE` |
| `POST /close` | exige un code existant | `WATCH_NO_CODE_TO_CLOSE` |
| `DELETE /{id}` | `ARMED` seul | `WATCH_NOT_DISARMABLE` |
| `POST /revoke-link` | tous | — |

Le tableau ci-dessus est une copie de courtoisie. **La source est le contrat**,
et c'est lui qu'il faut lire la prochaine fois.

---

## 6. Sur votre repli, et sur les 750 ms

Vous n'aurez pas à le payer, mais un mot quand même, parce que le chiffre nous
concerne.

**Notre plancher mesuré est plutôt de l'ordre de 200 ms** par requête
authentifiée, et il a une cause que vous ne pouviez pas deviner : notre base et
notre service ne sont pas dans la même région — la base est à San Francisco, le
service en Europe. Chaque requête traverse l'Atlantique. Votre 750 ms inclut
sans doute le réseau mobile par-dessus.

Cela ne change rien à votre conclusion — une lecture réseau de plus à chaque
démarrage et sur toutes les pages, pour un événement rare, restait le mauvais
marché. Mais si vous voyez d'autres latences qui vous surprennent, la cause est
probablement celle-là et elle est chez nous, pas dans votre code.

---

## 7. Vos §1 et §4 : reçus

**Le `hasReturnCode` qui retombait sur `true`** est un bon rappel dans les deux
sens. Nous avons regardé si nous avions le symétrique — un défaut qui hériterait
du mauvais côté pour une valeur d'énumération neuve — et c'est précisément ce que
le §2 raconte, sous une autre forme : notre valeur par défaut à nous était
enfouie dans un index SQL.

**Le relevé des ② parasites est chez nous, vous avez raison** et nous n'aurions
pas dû vous le demander : vous n'avez pas les journaux, et l'app ne garde aucune
trace d'un message envoyé à un tiers. Nous le prenons en charge. Votre précision
sur vos deux comptes de test est utile : elle écarte la source la plus probable,
et resserre la recherche sur les comptes réels.

**Votre §4 sur le « Bien rentrée »** — nous l'avons verrouillé par un test
unitaire dédié qui n'affirme pas seulement le bon état, mais **interdit
nommément les deux mauvais**. Le commentaire dit pourquoi : personne ne va
vérifier une page publique qui annonce une bonne nouvelle.

---

## 8. Ce que le lot contient

| Élément | Changement |
|---|---|
| `WatchState` | nouvelle valeur **`NOT_ARRIVED`**, terminale. Aucune retirée, aucune renommée |
| Migration | vocabulaire d'état élargi, index de visibilité, **et l'unicité par créneau réalignée** (§2) |
| Boucle aller, T+45 | pose `NOT_ARRIVED`, pose `closedAt`, journalise l'incident, prévient l'organisateur |
| Gabarit ⑤ | **plus d'appelant**. Le texte et ses tests restent, documentés comme retirés et datés |
| Jeton public | **plus jamais créé** sur cette branche |
| `GET /watches/active` | rend une `NOT_ARRIVED` tant que `closedAt` a moins de 24 h |
| `GET /watches/history` | la rend ensuite, avec `state: "NOT_ARRIVED"` |
| `POST /watches/{id}/panic` | **`409 WATCH_NOT_ON_SITE`** tant que `arrivalConfirmedAt` est nul |
| `POST /watches/{id}/abandon` | **accepte `ESCALATED` sans arrivée** : referme en `NOT_ARRIVED`, et fait partir la levée si une alerte était sortie (§4) |
| Contrat OpenAPI | **les états acceptés sont écrits sur chaque verbe**, avec le code d'erreur du refus (§5) |
| Page publique | `NOT_ARRIVED` → **« En trajet »**. Un `ESCALATED` hérité sans arrivée aussi |
| `resend-code`, ①②③④, boucle retour, série, journal | **inchangés** |

Le refus de `panic` est servi sur `arrivalConfirmedAt`, jamais sur l'état : notre
garde « sur place » existant refuse `ESCALATED`, et l'employer aurait retiré le
bouton d'alerte à une personne bien arrivée dont la veille a escaladé faute de
retour confirmé — au moment précis où elle en a le plus besoin.

---

## 9. La date

Le lot est complet et la suite de tests est verte. **Nous vous donnons la date de
mise en production dans notre prochain message**, une fois la recette passée —
c'est la seule chose que ce document vous doit encore.

Rappel de notre côté : elle n'est plus critique. `NOT_ARRIVED` supprime les deux
phrases fausses au lieu d'en choisir une, et votre §2 nous a confirmé pourquoi —
une app antérieure à votre lot, servie un état inconnu, `parse` rend `armed`, et
n'affiche rien. L'ordre de nos deux sorties n'expose plus personne.

---

**Ce que nous attendons de vous :**

1. **Reposez le bouton de sortie, sur `POST /watches/{id}/abandon`** — c'est ce
   qui libère les personnes bloquées aujourd'hui, et c'est le plus pressé des
   quatre ;
2. **Dites-nous si le §4.b vous gêne** — la levée qui part sur une veille
   héritée. C'est le seul endroit où nous avons élargi votre demande, et il
   touche un envoi sortant ;
3. **Un coup d'œil pour l'invariant du §3.a** — un lecteur de `/watches/active`
   qui supposerait « listée ⟹ `closedAt` nul » ;
4. **Votre jour de sortie en production**, quand il est fixé.

Et merci pour vos deux trouvailles. Le §3 nous évitait un écran muet là où la
personne concernée était la seule à ne rien apprendre. Le §6 était chez nous
depuis le début, il bloque des gens en ce moment, et il a fallu que vous
l'essayiez à la main pour qu'il apparaisse — aucun de nos tests ne demandait à
une veille de sortir de cet état, parce qu'aucun de nous n'avait pensé qu'elle
puisse y entrer et y rester.
