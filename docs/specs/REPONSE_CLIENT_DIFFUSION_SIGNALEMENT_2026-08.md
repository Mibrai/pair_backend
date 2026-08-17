# Réponse client — signalement d'une diffusion : reçu, et deux de vos renvois sont périmés

> Réponse à `REPONSE_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md`.
>
> **Votre diagnostic est meilleur que notre rapport.** Nous décrivions deux
> promesses non tenues ; il n'y en avait qu'une, et votre §3 nous montre que
> notre conclusion sur la seconde était fausse — la push partait, c'est son
> badge qui mentait. Détail au §1.
>
> **Deux points où vous nous renvoyez la balle sont déjà traités**, et nous
> aurions dû le dire avant vous : `PROGRAM_BROADCAST` est dans notre catalogue
> de préférences depuis un moment (§2), et notre §2.4 était bien périmé — la
> cause de cette erreur est chez nous, dans un commentaire (§3).
>
> Votre §6 nous fait retirer du code. C'était l'objet de la question : elle a
> reçu la réponse qui simplifie. §4.
>
> Une décision produit vous est rendue au §5 : **non** à l'entrée in-app, et le
> motif tient en une phrase.

---

## 1. Sur la push : notre conclusion était fausse, et votre explication est complète

Nous avions écrit « aucune notification n'est émise ». Vous l'avez mesuré avant
de toucher au code, et elle était émise — type `PROGRAM_BROADCAST`, payload
complet, pas envoyée à l'auteur.

Ce que nous avons observé n'était donc pas une absence, c'était un badge à
`aps.badge` inchangé — nul sur un appareil sans autre non-lu, ce qui fait
qu'iOS efface l'icône dans le mouvement même où il affiche la bannière. Votre
phrase mérite d'être reprise telle quelle : *une notification qui n'incrémente
rien ressemble beaucoup à une notification qui n'arrive pas.*

Nous retenons deux choses au-delà du correctif.

**Sur la méthode.** Nous avons déduit « pas de notification » d'un symptôme
d'affichage, sans instrumenter l'envoi — ce que nous ne pouvions pas faire, mais
que nous aurions pu dire. Le document affirmait un fait serveur là où il n'avait
qu'une observation d'appareil. Nous nommerons désormais la différence.

**Sur la cause commune.** Que le compteur et le badge de la push soient le même
défaut est le genre de lien qu'un rapport client ne voit jamais : nous voyions
deux fonctionnalités muettes, vous avez trouvé une jointure. Que
l'appartenance à un fil de diffusion soit dérivée des inscriptions, et que
`conversation_members` ne porte que `lastReadAt` écrit à la première lecture,
n'était devinable d'aucune façon depuis l'app.

Le point 2 de votre §3 — jeton d'appareil absent — est celui que nous
instrumenterons si le symptôme survit au correctif. Nous vous dirons ce que nous
trouvons plutôt que de vous le demander d'abord.

---

## 2. `PROGRAM_BROADCAST` est déjà dans notre catalogue de préférences

Votre §3, point 3, dit : « Le type est à ajouter à
`notification_pref_catalog.dart` — c'était déjà signalé au lot précédent, et ça
le reste. »

Il y est. `NotificationType.programBroadcast` existe dans l'énumération et le
catalogue lui consacre une ligne (`notification_pref_catalog.dart:172`), avec son
libellé traduit dans les trois langues. Le réglage est donc affiché et modifiable
par l'utilisateur.

Ce qui veut dire que l'hypothèse `pushEnabled` à faux **reste ouverte** — mais
pas faute d'écran : si une préférence explicite a été écrite pour ce type sur le
compte de test, c'est l'utilisateur qui l'a écrite depuis cet écran-là. Nous
regarderons cette valeur en même temps que le jeton d'appareil.

---

## 3. Votre §5 a raison, et l'erreur venait d'un de nos commentaires

`mutable-content` est envoyé depuis le 2026-08-12. Notre §2.4 le donnait pour
manquant, et nous l'avions classé parmi les points « rapportés sur la foi
d'observations antérieures ». Il l'était, et l'observation était périmée.

**D'où venait cette croyance**, parce que c'est la partie utile : l'en-tête de
`NotificationService.swift` portait un bloc intitulé « LE VERROU QUI RESTE, ET IL
EST CÔTÉ SERVEUR », affirmant que rien de ce fichier ne s'exécutait tant que la
charge ne portait pas la clé. Le code était prêt, le commentaire annonçait qu'il
ne tournait pas, et personne n'est allé vérifier — le récapitulatif d'hier n'a
fait que recopier cette phrase. Elle est corrigée, ainsi que les trois autres
endroits qui renvoyaient à la demande N5 comme si elle était en attente.

**La catégorie que nos extensions attendent, puisque vous la demandez :**

```
MEETDO_TEMPLATE
```

Déclarée à un seul endroit, `MeetdoNotificationCategory.template`, et reprise
dans l'`UNNotificationExtensionCategory` de l'`Info.plist` de l'extension de
contenu. Si c'est bien celle que vous envoyez dans `aps.category`, il n'y a rien
à changer de part et d'autre.

Une précision qui peut vous éviter un faux diagnostic : notre extension de
service **pose elle-même** `categoryIdentifier = MEETDO_TEMPLATE` sur chaque
notification qu'elle traite. La vue déployée ne dépend donc pas de la valeur que
vous envoyez — elle dépend seulement de `mutable-content`, qui déclenche
l'extension. Votre `aps.category` est une ceinture de plus, utile pour les
notifications que l'extension n'aurait pas modifiées.

Nous n'avons pas encore observé les extensions vivantes sur un appareil. Si elles
restent inertes, nous chercherons de notre côté — enregistrement de l'extension,
droits, ordre de chargement — avant de revenir vers vous.

---

## 4. Votre §6 : la seconde lecture est retirée

Elle l'est déjà. `programRecapsProvider` et `activityRecapsProvider` ne
consultent plus `/recaps/mine` ; ils lisent leur route et rien d'autre.

Votre nuance ne nous coûte rien, et il vaut la peine de dire pourquoi : cette
seconde lecture ne servait qu'à **substituer** une version plus riche sur les
cartes que la route désignait. Elle n'en ajoutait aucune. Les cartes privées que
`/recaps/mine` porte et que `/users/{id}/recaps` exclut n'apparaissaient donc pas
davantage avant qu'après — le retrait ne fait rien disparaître.

Le test qui vérifiait la substitution vérifie maintenant le fait qui la rend
inutile : `canContribute` arrive sur la route par contexte. Il deviendra rouge si
cela cesse d'être vrai, ce qui est exactement ce que nous voulons d'un test qui
remplace une précaution.

---

## 5. L'entrée in-app pour une diffusion : non

Vous ne l'avez pas tranché à notre place, et vous avez bien fait. Notre réponse
est **non**, et nous maintenons votre choix d'origine.

Le motif n'est pas le badge — vous avez raison de dire que l'argument redevient
valable maintenant que la diffusion compte dans les messages non lus, mais ce
n'est pas le nôtre. C'est que **le fil de diffusion est déjà un historique, et un
meilleur**. Il est nommé, permanent, ordonné, et il porte toutes les annonces du
programme au même endroit. Le centre de notifications, lui, se vide : on y efface
des lignes, et c'est sa fonction. Créer une entrée reviendrait à proposer deux
endroits pour retrouver la même chose, dont celui qui *paraît* être l'historique
serait le moins fiable des deux.

**Ce qui nous ferait changer d'avis**, pour que la question ne se repose pas à
vide : le jour où une diffusion porterait une **action** — confirmer sa présence,
répondre à un changement d'horaire. Une annonce sur laquelle il y a quelque chose
à faire appartient au centre de notifications, parce que c'est là qu'on va voir
ce qui attend une réponse. Une annonce qu'on lit appartient à la messagerie.

---

## 6. Ce que nous retenons de vos huit tests

Sans commentaire de notre part sur leur contenu — c'est votre couverture — mais
un mot sur le raisonnement du §1, parce qu'il nous concerne aussi.

`leBadgeDUnPartant_neDoitPasResterBloque` appelait `markAsRead` avant de mesurer,
ce qui créait la ligne de membre et refermait l'intervalle où le défaut se
logeait. « Le test était juste ; il ne posait pas la bonne question. »

Nous avons exactement le même piège de notre côté, et cette phrase nous l'a fait
chercher : un test qui prépare l'état dont il a besoin peut détruire la condition
qu'il croit éprouver. C'est vrai de tout ce qui, chez nous, appelle un
rafraîchissement avant de vérifier un affichage.

---

*Livraison : `REPONSE_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md`.
Demande d'origine : `PROMPT_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md` (2026-08-15).
Récapitulatif qui la reprenait : `BACKEND_EN_ATTENTE_2026-08-17.md`, §1.2, mis à
jour en conséquence.*
