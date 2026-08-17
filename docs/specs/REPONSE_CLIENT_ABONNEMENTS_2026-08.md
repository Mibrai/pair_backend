# Réponse client — abonnements (août 2026)

> Réponse à `REPONSE_BACKEND_ABONNEMENTS_2026-08.md`. Le contrat nous convient
> tel qu'il est écrit, y compris le découpage en trois lots, qui est meilleur
> que le nôtre.
>
> **Votre seul point bloquant n'a plus d'objet** : le §1.4 est retiré de la
> demande, et la maille des marqueurs n'a pas à changer. Explication au §1, avec
> ce qui l'a rendu caduc — un changement de notre côté, postérieur à l'audit que
> vous avez mené.
>
> Vos trois constats d'audit sont acceptés sans réserve ; deux d'entre eux
> corrigent la demande sur des points où nous avions écrit plus que ce que nous
> savions. Détail au §2.
>
> Six adaptations sont à notre charge, listées au §4 pour que vous sachiez ce
> qui vous attend à la mise en service — rien n'y demande d'action de votre part.
>
> Deux précisions vous sont demandées en retour, toutes deux sur des codes
> d'erreur : §5.

---

## 1. Le §1.4 est retiré — et votre constat sur la maille reste vrai

Vous écrivez :

> « Ce n'est pas une régression que nous introduirions ; c'est l'état actuel, et
> il est déjà visible dans votre app — sur les zones denses, s'abonner à
> « l'auteur » depuis la carte peut abonner à quelqu'un d'autre que celui qu'on
> croit suivre. »

Ce ne l'est plus, et c'est récent. La carte a changé de source entre votre audit
et cette réponse (commit `01d2ec6`, *un pin par programme, et la fin des
pastilles*).

**Les puces d'abonnement de la fiche carte ne lisent plus un marqueur d'activité.**
Elles lisent un `ProgramDto`, par l'intermédiaire du pin de programme
(`program_detail_sheet.dart:398-408`) :

```dart
_MapSubscribeRow(
  organizerId:     program.organizerId ?? program.organizer?.id,
  userActivityId:  program.userActivityId,
  categoryId:      program.categoryId ?? program.category?.id,
  ...
)
```

Les trois identifiants viennent donc du programme lui-même, jamais d'un créneau
représentatif. Ils désignent exactement ce que l'utilisateur a sous les yeux,
puisque le pin **est** le programme.

Quant à la branche `/map/activities`, elle n'alimente plus aucun rendu :
`mapActivitiesProvider` (`map_providers.dart:674`) n'est consommé nulle part —
la seule mention qui subsiste dans `map_page.dart:991` est un commentaire, et
`MapViewState.setVisibleActivities` n'est appelé par personne, donc
`visibleActivities` reste vide en permanence. `MapActivityDto` survit dans le
modèle et dans deux fichiers de domaine (`author_suggestions`, `city_focus`),
tous débranchés.

**Ce que nous vous demandons de faire du §1.4 : rien.** N'ajoutez pas
`userActivityId` au marqueur, et surtout ne changez pas la clé de groupement
pour un consommateur qui n'existe plus. Le lot B et le lot C partent sans
attendre de nous.

**La réserve, pour le jour où la question reviendra.** La suppression de ce rendu
n'est pas définitive : la carte doit évoluer vers un design d'horizon où une
source large peut réapparaître. Si `/map/activities` revient nourrir des
marqueurs, ce sera l'**option A**, et pour votre raison — un marqueur qui ment
n'est pas moins gênant parce qu'il ment depuis longtemps. La cardinalité
supplémentaire ne nous fait pas peur : notre filtrage de marqueurs
(`marker_filtering.dart`) est bâti sur le principe qu'un identifiant absent
n'écarte jamais une entrée, et intégrer un champ de plus à la clé de
déduplication est un changement local. Nous vous préviendrons avant, pas après.

Une remarque en passant, qui ne demande pas de réponse : le défaut que vous
décrivez — deux organisateurs superposés à 111 m près, fondus en un marqueur qui
porte le nom de l'un d'eux — **existe toujours dans la route**. Il ne nous
atteint plus, il n'a pas disparu. Si un autre client la consomme un jour, il
héritera du problème sans le savoir, et le commentaire que vous mettrez dans le
code de groupement vaudra mieux qu'une note dans ce document.

---

## 2. Vos trois constats d'audit

### `NEARBY_PROGRAM` jamais émis

Accepté, et c'est utile bien au-delà de cette demande. Nous avions écrit « trois
notifications » depuis l'énumération, sans vérifier qu'un émetteur existait —
c'est une affirmation que nous n'aurions pas dû poser sans preuve, et vous avez
eu raison de la reprendre.

Ce que nous ne changeons pas : le type reste dans notre énumération, et reste
dans la liste des types qui affichent un compte à rebours
(`NotificationType.countsDownToSession`). Un type mort qui ne se rend jamais ne
coûte rien, et il sera prêt le jour où la proximité se mettra à notifier —
exactement le raisonnement qui vous fait écrire la priorité à trois branches.

Ce que cela change en revanche : la « variante A » de notre maquette de
notification, qui réunit visuellement `AUTHOR_NEW_PROGRAM`, `NEARBY_PROGRAM` et
`ACTIVITY_NEW_PROGRAM`, n'a que **deux** branches vivantes — et après votre
déduplication, une seule à la fois. Le groupement visuel n'a plus grand-chose à
grouper. Nous le laissons tel quel : il ne coûte rien et il redeviendra juste.

Aucun travail n'avait été repoussé au motif que trois pushes partaient.

### L'idempotence était déjà servie

Accepté. Notre formulation était prudente — « n'est pas documenté et nous ne
l'avons pas éprouvé » — et c'est exactement pour éviter d'affirmer un défaut que
nous n'avions pas constaté. Le `409` nous convient, `ALREADY_SUBSCRIBED` encore
mieux : voir §4.3 pour ce que nous en faisons.

Les trois index uniques partiels règlent la question du doublon en base. Nous
retirons cette inquiétude.

### `DELETE` → `204`

Merci. C'est la moitié qui nous manquait vraiment, et elle rend notre retrait
optimiste correct au lieu d'approximativement correct.

---

## 3. L'ordre de livraison

Il nous va, et il est meilleur que le nôtre : découper par zone de code touchée
plutôt que par lot fonctionnel évite de réécrire quatre fois les boucles de
fan-out, et notre découpage ne pouvait pas le savoir.

La seule contrainte que nous avions posée — `subscribed` avant la pagination —
est respectée par votre séquence (lot A / lot C). Nous n'en avons pas d'autre.

Vous pouvez livrer les trois lots sans nous prévenir et dans l'ordre qui vous
arrange. Tous les champs ajoutés sont lus comme nullables ou optionnels ; aucun
ne casse l'app par son absence, et aucun ne change de comportement tant que
l'écran correspondant n'est pas branché.

---

## 4. Les six adaptations à notre charge

Listées ici pour que vous puissiez vérifier que nous avons compris le contrat, et
pour qu'aucune ne vous revienne sous forme de question à la mise en service.

**4.1 Le bouton d'abonnement passe à trois états.** `MUTED` conserve
`subscribed: true` : le bouton dira « Abonné », avec une icône de cloche barrée,
et l'action de sourdine vivra dans le menu. Vous insistez sur ce point ; il était
déjà le nôtre, et votre formulation — un abonnement en sourdine affiché comme
absent sera recliqué, et le second `POST` rendra `409` — décrit précisément le
défaut que nous voulions éviter.

**4.2 `subscribed` est traité comme une valeur à trois cas, pas comme un
booléen.** Absent sur `/users/me` (on ne s'abonne pas à soi-même : aucun bouton
n'est rendu), absent ou `false` sans identité sur `CategoryDto` — que nous
n'utiliserons jamais comme source de vérité hors session, conformément à votre
avertissement. Notre modèle de profil public s'appelle déjà `UserPublic`
(`user_models.dart:40`), donc votre `UserPublicDto` tombe juste sans renommage.

**4.3 `409 ALREADY_SUBSCRIBED` est traité comme un succès.** Notre bascule
optimiste (`subscription_providers.dart:64`) relaie aujourd'hui l'erreur à
l'écran ; recevoir `ALREADY_SUBSCRIBED` signifie que l'état voulu est déjà en
base, donc l'affichage doit se stabiliser sur « Abonné » sans message d'erreur.
Symétriquement pour le `204` du `DELETE`. C'est le code métier nommé qui rend
cette distinction possible — merci de ne pas nous avoir laissé le `CONFLICT`
générique.

**4.4 Nous n'exposons pas le tri `targetName,asc`.** Un tri alphabétique qui ne
trie que la page se voit, et notre écran d'abonnements n'en a pas besoin : le
regroupement par type plus l'ordre chronologique décroissant suffisent. **Ne
posez pas la colonne dénormalisée** — vous préfériez ne pas la poser tant que
l'écran n'en dépend pas, et il n'en dépendra pas.

**4.5 La provenance dans le payload devient un geste.** Les trois clés du §2.3
alimentent une ligne « Vous suivez **Lena Müller** » sous la notification, et un
appui long qui propose « Mettre en sourdine » et « Se désabonner ». Votre choix
de faire pointer `subscriptionId` sur **la ligne qui a gagné la déduplication**
est celui qui rend ce geste honnête, et c'est le seul : mettre en sourdine ce
qui est nommé à l'écran doit faire taire ce qu'on vient de recevoir.

Le label copié plutôt que relu nous va, y compris sa conséquence — une cible
renommée laisse d'anciennes notifications au nom d'avant. Une notification doit
dire ce qu'elle disait le jour où elle est partie ; nous ne compenserons pas
côté client.

**4.6 `allowSubscriptions` se branche sur l'écran existant.** `PrivacySettings`
(`user_models.dart:259`) porte déjà `allowMessages` et se règle au même endroit ;
le nouveau champ y prend place sans écran nouveau. Votre choix de le loger dans
`GET|PUT /users/me/privacy` plutôt que dans une route dédiée nous épargne
exactement le travail que nous aurions eu à faire.

---

## 5. Deux précisions demandées

**5.1 Le code métier du `403` sur un profil en `NOBODY`.** Vous nommez
`ALREADY_SUBSCRIBED` pour le conflit, mais pas le refus d'abonnement. Sans nom,
nous afficherons un message serveur brut — le défaut même que le premier code
corrige. `SUBSCRIPTIONS_NOT_ALLOWED` nous conviendrait ; n'importe quel nom
stable fera l'affaire.

**5.2 Le `403` couvre-t-il les abonnements existants ?** Si un utilisateur passe
son profil en `NOBODY` alors que des gens le suivent déjà, que deviennent ces
lignes ? Trois réponses sont défendables — elles restent et continuent de
notifier ; elles restent et cessent de notifier ; elles sont supprimées. Nous
penchons pour la première (le réglage ferme la porte, il ne vide pas la pièce), et
c'est celle qui demande le moins de travail des deux côtés. Mais l'utilisateur
qui coupe s'attend peut-être à l'inverse, et c'est un choix produit autant que
technique : dites-nous lequel vous implémentez, nous l'écrirons dans le libellé
du réglage.

---

## 6. Vos deux réponses aux questions ouvertes

Les deux nous vont, et la seconde appelle une remarque.

Sur `level` et `NotificationPref` : votre ordre d'évaluation — `level` décide
*si*, `NotificationPref` décide *par quel canal* — est plus clair que notre
« le plus restrictif gagne », qui laissait croire à une comparaison entre deux
grandeurs comparables. Nous reprenons votre formulation dans nos notes.

Sur le corollaire du `subscriberCount` : bien noté qu'additionner les compteurs
d'un auteur et de ses activités ne donne pas le nombre de personnes touchées par
une publication, la déduplication rendant le second plus petit. **Nous ne
demandons pas ce nombre aujourd'hui** — aucun écran ne l'affiche, et nous ne
créerons pas l'écran pour créer le besoin. Si nous le faisons un jour, nous vous
le demanderons calculé plutôt que de l'additionner nous-mêmes.

---

## 7. Ce que nous livrons de notre côté, sans rien attendre

Inchangé depuis la demande : page `/subscriptions` (regroupement, désabonnement
en masse, date d'abonnement), proposition « suivre cet auteur ? » après une
inscription à un programme, filtre « Mes abonnements » dans l'Explorer.

S'y ajoutent les six adaptations du §4, qui partiront au fil de vos lots.

---

*Demande initiale : `ios/docs/PROMPT_BACKEND_ABONNEMENTS_2026-08.md` — noté chez
vous sous `docs/specs/`. Réponse backend :
`ios/docs/REPONSE_BACKEND_ABONNEMENTS_2026-08.md`.*
