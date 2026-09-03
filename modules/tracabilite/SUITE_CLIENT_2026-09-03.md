# L'arrivée à deux temps est livrée — et il manque la notification qui la rend utile

**Date :** 2026-09-03
**Fait suite à :** `PROMPT_BACKEND_2026-09-03.md`

> **Vos onze points sont servis, et le douzième — la bascule automatique — l'est
> aussi.** Nous l'avons vue tomber en production : une veille déclarée à
> 19:11:44, `arrivalAutoConfirmAt` à 19:26:44, et à 19:27:20 la veille est
> `ON_SITE` avec `ARRIVAL_AUTO_CONFIRMED` dans sa chronologie. C'était la
> condition que nous avions posée pour livrer la validation par l'hôte ; elle
> est remplie, et l'écran est livré — §1.
>
> **`FeatureFlags.watchWithoutGuardian` est allumé.** Un `POST /watches` avec le
> seul `scheduleId` rend `201`, `guardianId: null`, `alertDelivery: NONE`, et
> `NO_CONTACT` est au contrat. Les trois conditions que ce drapeau attendait —
> §2.
>
> **Un manque, et il annule la moitié du bénéfice : rien ne prévient la personne
> que sa présence a été validée.** `NotificationDto.type` ne porte toujours que
> les quatre types de veille d'avant. Nous avons contourné côté app, et le
> contournement a une limite que vous seuls pouvez lever — §3.
>
> **Deux détails de contrat relevés en passant**, dont un résumé qui contredit
> son propre schéma — §4.

---

## 1. Ce que nous avons vérifié, et comment

Nous n'avons pas cru la spécification sur parole : elle porte les types et pas
les valeurs, et nous avons déjà payé cette différence. Chaque ligne ci-dessous a
été rejouée en HTTP avec le compte de test, contre la production.

| Geste | Résultat observé |
|---|---|
| `POST /watches` avec le seul `scheduleId` | `201`, `ARMED`, `guardianId: null`, `alertDelivery: NONE` |
| `POST /watches/{id}/arrival/claim` | `202`, `arrivalClaimedAt` posé, `arrivalAutoConfirmAt` à +15 min, **état inchangé** (`ARMED`) |
| le même, une seconde fois | `409 WATCH_ARRIVAL_ALREADY_CLAIMED` |
| `POST /watches/{id}/code/claim` avant validation | `409 WATCH_ARRIVAL_NOT_CONFIRMED` |
| la bascule automatique | à l'heure annoncée : `ON_SITE`, `ARRIVAL_AUTO_CONFIRMED` |
| `POST /watches/{id}/code/claim` après | `200`, code de 5 caractères |
| le même, une seconde fois | `409 WATCH_CODE_ALREADY_CLAIMED` |
| `POST /schedules/{id}/arrivals/{pid}/confirm` sur notre créneau | `202` |
| le même sur le créneau d'un autre | `404 NOT_FOUND`, « Créneau introuvable » |
| `GET /slots/{id}/participants` | `arrival: {state, claimedAt, confirmedAt}` sur chaque inscrit |

**Le §1.4 est clos.** Nous avions écrit : « si vous refusez la bascule
automatique, dites-le franchement : nous ne livrerons pas la validation par
l'hôte. Le parcours entier tient à ce garde-fou-là. » Vous ne l'avez pas
refusée, elle tourne, et nous l'avons vue s'exercer. L'écran est donc livré.

**Ce que l'app en fait, côté hôte :** dans la ligne de l'inscrit, un bouton
« Confirmer » sur `CLAIMED`, un insigne sur `CONFIRMED`, **rien** sur `NONE`. Le
bouton et l'insigne viennent de la relecture de votre liste, jamais d'un état
posé après le tap — c'est ce qui les garde honnêtes quand l'hôte valide à la
seconde où votre délai tombe. Et l'insigne ne porte ni heure, ni retard, ni
identité du validateur : le type qui le nourrit ne les contient pas.

**Côté personne :** « J'y suis » déclare et ne referme plus l'écran — il bascule
sur une carte d'attente qui affiche **votre** `arrivalAutoConfirmAt`, jamais une
addition faite chez nous. Et la phrase « ton hôte peut valider ta présence » est
écrite **au-dessus du bouton**, avant le geste : c'est la contrepartie du §3.b
de notre prompt, un tiers acquiert une action sur le parcours de quelqu'un, et
cela ne s'apprend pas après coup.

---

## 2. L'armement sans contact est allumé

Les trois conditions du drapeau sont remplies, et la deuxième était la seule qui
comptait vraiment : **`NO_CONTACT` n'est pas `ESCALATED`**. Ce mot veut dire
« un message est parti à un tiers » dans tout notre code.

Nous avons posé un garde-fou explicite là où il fallait. `SafetyWatch.guardianAlerted`
décide de la couleur du bandeau par trois indices, dont le dernier est « l'arrivée
était validée, donc une échéance de retour a été dépassée, donc un message est
parti ». Ce raisonnement est juste partout — **et faux sur une veille sans
contact**, qui a une arrivée parfaitement validée et n'a prévenu personne. Sans
la ligne qui écarte `NO_CONTACT` avant les trois indices, l'app afficherait
« message d'urgence envoyé » sur la seule veille du module dont vous nous
garantissez que rien n'est parti. Un test le verrouille.

L'acquittement « je démarre sans contact d'urgence » reste où nous l'avions mis,
dans vos réglages privés (`safety.guardianWaiver`), et il cesse de valoir dès
qu'un contact accepté existe — **sans être effacé**. Votre §2.3.b reste donc
ouvert : dites-nous si vous préférez effacer la clé de votre côté, et nous
retirerons notre règle. Jamais les deux.

---

## 3. Le manque : personne ne dit à la personne que sa présence est validée

`NotificationDto.type` porte quatre valeurs de veille : `WATCH_RETURN_REMINDER`,
`WATCH_GUARDIAN_ALERT`, `WATCH_ARRIVAL_PROMPT`, `WATCH_LOST_ORGANIZER`. Aucune
ne dit « ta présence vient d'être validée ». Votre propre description de
`code/claim` suppose pourtant cette notification — « envoyez la notification
*ta présence est validée*, mais sans le code », écrivions-nous, et vous avez
livré la route en conséquence.

**Pourquoi c'est structurant et pas cosmétique.** Avant ce lot, « j'y suis »
rendait le code dans sa réponse : qui avait touché le bouton **avait** son code.
Depuis, il faut revenir le chercher. Quelqu'un qui déclare son arrivée puis range
son téléphone n'a donc pas de code à l'échéance — donc pas de clôture possible,
donc une alerte partie à son proche pour une soirée qui s'est bien passée. C'est
exactement le défaut que ce module existe pour empêcher, réintroduit par le
parcours à deux temps.

**Notre contournement, et sa limite.** Un guetteur invisible tourne sur toutes
les pages de l'app : dès que la liste active porte une veille validée dont le
code n'est pas au Trousseau, il le réclame. Il suffit alors que l'app revienne
au premier plan **une fois** entre la validation et l'échéance.

La limite est là, et elle est entière : si l'app n'est pas rouverte, rien ne se
passe. Or c'est précisément le scénario d'une soirée — le téléphone reste dans
la poche. **Une notification est ce qui rouvre l'app**, et sans elle notre
guetteur attend un geste que personne n'a de raison de faire.

Nous demandons donc un type de notification poussée à la validation d'arrivée —
**sans le code**, pour toutes les raisons que vous avez acceptées. Le tap doit
ouvrir la veille ; c'est là que notre réclamation part.

---

## 4. Deux détails de contrat

**a. Le résumé de `POST /watches` contredit son schéma.** Il dit encore « Exige
un contact d'urgence accepté » alors que `CreateWatchRequest.required` ne porte
plus que `scheduleId`. Nous ne nous y sommes pas fiés — nous avons appelé la
route — mais quelqu'un qui lira la spécification sans le faire en conclura
l'inverse de la vérité.

**b. `WatchDto` ne porte pas `guardianName`.** Ce n'est pas une demande : nous
prenons le nom dans la liste des contacts, et un identifiant nous suffit à
savoir s'il y a quelqu'un. Nous le signalons parce que notre §2.2 vous demandait
de confirmer que « `guardianId` **et** `guardianName` » seraient nuls — la
moitié de la question n'avait pas d'objet.

---

## 5. Récapitulatif

| # | Votre livraison | État chez nous |
|---|---|---|
| 1 | `arrival/claim` + `arrivalClaimedAt` | câblé, testé |
| 2 | `arrivals/{pid}/confirm` | câblé, testé — bouton et insigne dans la ligne de l'inscrit |
| 3 | `code/claim` | câblé, réclamé depuis trois endroits, un seul appel garanti |
| 4 | `arrival` sur les inscrits | câblé, `NONE` indistinguable par construction |
| 5 | la bascule automatique + `arrivalAutoConfirmAt` | **vue tomber en production**, affichée à l'écran |
| 6 | `guardianId` facultatif | drapeau allumé |
| 7 | `NO_CONTACT` | lu, terminal, et écarté du bandeau corail |
| 8 | `guardianId: null` | vérifié sur une vraie réponse |
| 9 | l'acquittement dans les réglages privés | livré — **votre §2.3.b reste ouvert** |
| 10 | l'insigne hors des DTO publics | rien à faire chez nous |
| 11 | `seen-by-host` ne vaut pas déclaration | conservé tel quel |
| 12 | les préconditions au contrat | présentes, et exactes |
| — | **la notification de validation** | **manquante — §3** |
