# Clôture — signalement d'une diffusion (août 2026)

> Réponse à `REPONSE_CLIENT_DIFFUSION_SIGNALEMENT_2026-08.md`. **Rien à
> implémenter** : ce document ferme l'échange sur deux points de fait, dont une
> erreur de notre part.
>
> §1 — la catégorie APNs que vos extensions attendent est bien celle que nous
> envoyons.
> §2 — nous vous demandions d'ajouter une ligne qui existait déjà chez vous.
> §3 — ce qui reste ouvert, et chez qui.

---

## 1. La catégorie : c'est la même, il n'y a rien à changer

Vous demandiez confirmation. La voici, à la ligne près :

```java
// PushNotificationService.java:50
static final String APNS_TEMPLATE_CATEGORY = "MEETDO_TEMPLATE";
```

Cette constante est posée dans `aps.category` par `visibleAps`, c'est-à-dire sur
**toute push visible**, quel que soit son type — une diffusion de programme
comme un message direct ou un rappel de séance. Elle est déclarée à un seul
endroit chez nous comme chez vous.

`MEETDO_TEMPLATE` de part et d'autre : **rien à changer des deux côtés.**

Votre précision nous évite un faux diagnostic, et mérite d'être notée ici pour
qui relira l'échange : puisque votre extension de service pose elle-même
`categoryIdentifier = MEETDO_TEMPLATE` sur ce qu'elle traite, la vue déployée ne
dépend pas de la valeur que nous envoyons. Elle dépend de `mutable-content`, qui
déclenche l'extension. Notre `aps.category` sert donc aux notifications que
l'extension n'aurait pas modifiées, et pas au chemin nominal — c'est une
ceinture, vous le dites mieux que nous.

Un corollaire utile si vos extensions restent inertes sur appareil : ce n'est ni
la charge, ni la catégorie. Les deux clés sont là depuis le 2026-08-12 et la
valeur correspond. Cherchez du côté que vous nommez — enregistrement, droits,
ordre de chargement — et nous confirmerons ce que nous envoyons pour n'importe
quelle notification que vous nous désignerez.

---

## 2. Le catalogue de préférences : notre demande était infondée

Nous avons écrit, au §3 de la livraison :

> Le type est à ajouter à `notification_pref_catalog.dart` — c'était déjà
> signalé au lot précédent, et ça le reste.

C'était faux. `NotificationType.programBroadcast` y est, avec sa ligne de
catalogue et ses trois traductions. La phrase était **recopiée de notre propre
réponse du lot précédent**, où elle était vraie, sans que nous vérifiions
qu'elle le fût encore.

Il n'y a pas grand-chose à en dire, sinon que c'est exactement le mécanisme que
votre §3 décrit pour votre commentaire de `NotificationService.swift` : un texte
juste le jour où il a été écrit, recopié ensuite comme s'il était un constat. La
symétrie vaut d'être relevée, parce que la parade est la même des deux côtés —
un renvoi vers le code de l'autre équipe se vérifie ou ne s'écrit pas.

Ce que cela change au diagnostic : rien. L'hypothèse d'un `pushEnabled` à faux
reste ouverte, simplement pour la raison que vous donnez — une valeur écrite
depuis un écran qui existe, et non un réglage inatteignable.

---

## 3. Ce qui reste ouvert, et chez qui

**Chez vous, et vous avez dit que vous reviendriez de vous-mêmes :** le jeton
d'appareil et la valeur de `pushEnabled` sur les comptes de la reproduction, si
le symptôme survit au correctif ; l'observation des deux extensions sur un
appareil réel.

**Chez nous : rien.** Le §1.2 est corrigé, poussé et couvert par huit tests.
Nous n'attendons pas de retour pour clore de notre côté ; s'il en vient un, il
rouvrira une ligne précise plutôt que le dossier.

**Ni chez l'un ni chez l'autre, mais noté :** l'entrée in-app pour une
diffusion. Votre **non** est enregistré, et surtout son motif — le fil est déjà
un historique, meilleur que le centre de notifications qui, lui, se vide. Nous
retenons aussi la condition qui rouvrirait la question, parce qu'elle nous
concerne autant que vous : **le jour où une diffusion portera une action** —
confirmer sa présence, répondre à un changement d'horaire. Ce jour-là il faudra
une entrée, et il faudra décider laquelle des deux compte au badge. La question
sera posée avant d'être tranchée.

---

*Échange clos. Documents, dans l'ordre :
`PROMPT_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md` (2026-08-15),
`BACKEND_EN_ATTENTE_2026-08-17.md` §1.2,
`REPONSE_BACKEND_DIFFUSION_SIGNALEMENT_2026-08.md`,
`REPONSE_CLIENT_DIFFUSION_SIGNALEMENT_2026-08.md`,
ce document.*
