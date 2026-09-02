# Le gabarit ⑤ est retiré — une non-arrivée ne prévient plus personne

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-BIS.md`

> **Une décision produit annule une pièce de la boucle aller que nous vous avions
> spécifiée le 31/08.** Elle vient de notre côté, elle n'a rien à voir avec un
> défaut de votre livraison, et elle retire du travail plutôt qu'elle n'en
> ajoute — mais elle touche un envoi sortant, donc nous préférons qu'elle soit
> écrite noir sur blanc plutôt que déduite.
>
> **Le message ⑤ ne doit plus partir.** Une personne qui n'a jamais validé son
> arrivée ne fait plus prévenir son contact d'urgence. Le système note qu'elle ne
> s'est pas présentée, prévient l'organisateur comme aujourd'hui, et s'arrête là.
>
> Côté app c'est fait : la boîte d'urgence ne s'affiche plus dans ce cas, et le
> bouton « prévenir maintenant » a disparu du trajet aller. **Il reste un
> intervalle où nos deux moitiés se contredisent** — §5.

---

## 1. La décision, dans les mots de qui l'a prise

> « Si l'arrivée n'a pas été validée alors le message d'urgence ne doit pas être
> envoyé. Nous ne sommes pas responsables. Le système va juste répertorier dans
> le journal que le participant n'est jamais arrivé. La boîte de message
> d'urgence ne doit donc pas s'afficher si l'arrivée n'était pas valide. »

Ce qu'elle recouvre, et ce qu'elle ne recouvre pas :

- **elle ne touche pas la boucle retour.** Quelqu'un qui a validé son arrivée et
  ne confirme pas son retour fait toujours partir ②, puis ④, avec la relance du
  contact de secours. C'est le cœur du module et il ne bouge pas ;
- **elle ne touche pas l'organisateur.** Il continue d'être prévenu à T+45, en
  in-app, avec le nom, l'absence de validation et l'heure — rien d'autre ;
- **elle ne touche pas le journal.** `LOST_ON_THE_WAY` reste écrit, l'incident
  reste journalisé, et un « perdu en chemin » continue de ne compter ni comme
  une absence ni contre la fiabilité (§6 du prompt du 31/08) ;
- **elle retire un seul envoi** : le gabarit ⑤ vers le contact d'urgence.

Le raisonnement, pour que vous puissiez le contester si vous y voyez un trou :
personne n'est parti. Il n'y a ni trajet à surveiller, ni dernier signe de vie à
transmettre, ni lieu où chercher quelqu'un. Réveiller un proche pour dire « elle
n'est pas allée à son cours » engage une inquiétude que rien ne justifie, et le
fait au nom de meetDo. Le prix de ce retrait est assumé : quelqu'un à qui il est
réellement arrivé quelque chose **en chemin** ne sera pas signalé par la veille.

---

## 2. Ce que nous vous demandons

**2.1 — À T+45 sans réponse, ne plus envoyer le gabarit ⑤.** Tout le reste de la
branche est inchangé : état « perdu en chemin », notification in-app à
l'organisateur, incident journalisé.

**2.2 — Ne plus créer le lien public de suivi sur cette branche.** Le contrat dit
« le lien naît à l'alerte » ; sans alerte, il n'y a pas de lien à créer, et un
jeton qui existerait sans destinataire est un jeton qui peut fuir.

**2.3 — La page publique ne doit jamais afficher « alerte envoyée » pour une
non-arrivée.** Si un lien existe déjà (veille armée avant ce changement),
l'état correct est « en trajet », pas l'état d'alerte.

**2.4 — `POST /watches/{id}/panic` : rendre `409` tant que l'arrivée n'est pas
validée.** L'app ne propose plus le geste avant `ON_SITE` — c'est la seconde
moitié de la décision, et §4 explique pourquoi elle compte autant que la
première. Nous vous demandons quand même le refus serveur : une app plus
ancienne, un rejeu de file hors ligne ou un bouton d'écran verrouillé oublié
suffiraient à faire partir le message que cette décision retire.

---

## 3. La question que nous ne pouvons pas trancher seuls : quel état, et qui referme ?

Aujourd'hui la branche aller pose `ESCALATED`. Ce nom voulait dire une chose
précise — *un message est parti à un tiers* — et il ne la voudra plus dire pour
cette branche.

Nous n'avons **pas** besoin d'un état nouveau pour nous en sortir : l'app lit
`arrivalConfirmedAt`, que votre `WatchDto` garantit nul tant que l'arrivée ne
l'est pas, et cela lui suffit à savoir lequel des deux récits raconter. Nous ne
demandons donc aucun champ. Mais deux points restent à vous :

- **le mot `ESCALATED` va mentir dans vos journaux, vos métriques et votre page
  publique**, pas seulement chez nous. Si vous préférez poser un état distinct
  (`NOT_ARRIVED`, ou refermer directement en `CLOSED`), dites-le-nous : notre
  lecture est tolérante (`WatchState.parse` rend `armed` sur l'inconnu, ce qui
  garde la veille visible), donc un état nouveau ne casse rien chez nous, et
  nous l'accueillerons nommément dès que vous l'aurez nommé ;
- **qui referme la veille ?** Aujourd'hui elle reste ouverte, et c'est la
  personne qui la referme depuis l'écran d'arrivée (« je n'y vais pas » →
  `abandon`). Nous avons gardé une porte visible vers cet écran dans la bande
  d'information, précisément pour ça. Si vous préférez refermer côté serveur à
  T+45, c'est mieux — la veille n'a plus rien à surveiller — mais dites-le, car
  nous cesserions alors d'afficher la porte.

---

## 4. Ce que nous avons changé côté app, et pourquoi la seconde moitié compte

**La boîte d'urgence ne s'affiche plus sans arrivée validée.** Le bandeau global
lit maintenant `arrivalConfirmedAt` et non l'état : avec arrivée, c'est le
bandeau corail « message d'urgence envoyé », inchangé ; sans arrivée, c'est une
bande d'information — ciel, aucun nom de contact puisque personne n'a été
prévenu, refermable d'un geste, avec un lien vers l'écran d'arrivée.

**« Prévenir maintenant » n'apparaît plus qu'une fois sur place.** C'est la
décision jumelle, et sans elle la première ne tient pas. Le bouton signale un
souci **au lieu de l'activité** : il suppose qu'on y soit. Tant qu'il existait en
chemin, un message pouvait partir sans arrivée validée — et notre bandeau,
lisant `arrivalConfirmedAt`, se serait alors tu sur une alerte bel et bien
envoyée. C'est exactement le défaut que nous cherchons à éviter, dans l'autre
sens.

Ces deux moitiés se tiennent : c'est la seconde qui nous dispense de vous
demander un champ « motif d'escalade ».

**Une phrase de l'écran d'arrivée a changé.** Elle promettait « sans réponse à
HH:MM, {contact} reçoit un message ». Elle dit maintenant que nous notons
simplement la non-arrivée, et ne nomme plus personne.

---

## 5. L'intervalle où nos deux moitiés se contredisent — et ce que nous en faisons

Tant que ⑤ part encore, **notre bande d'information dit « personne n'a été
prévenu » alors qu'un e-mail est parti.** C'est une phrase fausse sur un écran de
sécurité, et c'est le genre de phrase que ce module refuse depuis le premier
jour.

Nous l'assumons pour une raison courte : l'inverse — garder le bandeau d'alerte
en attendant — laisserait en place précisément ce que la décision retire. Mais
l'intervalle doit être court, et il est le seul point de ce document qui demande
une date plutôt qu'un avis.

**Ce que nous vous demandons donc :** une date, ou mieux, un interrupteur de
configuration que vous pouvez basculer sans redéploiement. Nous garderons notre
changement derrière rien du tout — il est déjà dans notre `main` — et nous vous
dirons le jour où il sort en production, pour que les deux moitiés se croisent
au plus près.

---

## 6. Ce qui ne change pas, pour éviter un malentendu

- `POST /watches/{id}/resend-code` continue de refuser un renvoi sans arrivée
  validée. C'est cohérent : sans arrivée il n'y a pas de code, et il n'y en aura
  pas. Nous ne demandons **pas** que ce refus soit levé ;
- les gabarits ①②③④ sont inchangés, e-mail seul, SMS toujours éteint ;
- `RETURN_ANNOUNCED`, `consecutiveConfirmedReturns`, `notifyGuardian`, `role` et
  la chronologie sont branchés et se comportent comme votre BIS le décrit ;
- `LOST_ON_THE_WAY` reste dans notre énumération et dans le journal de la
  personne. Ce qui disparaît, c'est le message au tiers, pas la trace.
