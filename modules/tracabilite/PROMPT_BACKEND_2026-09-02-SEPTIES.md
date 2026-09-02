# A2 est construit — deux réglages nous manquent, et nous n'en demandons qu'un franchement

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-SEXIES.md`

> **Sujet neuf**, sans rapport avec la boucle aller/retour : l'écran du **choix
> du contact** (A2) vient d'être construit pour de bon, et il fait apparaître
> deux réglages qui n'ont aucun équivalent chez vous.
>
> **1. Un contact d'urgence par défaut.** Nous le rangeons aujourd'hui sur
> l'appareil, faute de champ. C'est une vraie demande — §1.
>
> **2. La liste d'amis, elle, doit rester locale**, et nous vous demandons
> explicitement **de ne pas** créer de relation d'amitié. Nous expliquons
> pourquoi, parce que c'est contre-intuitif et que quelqu'un finira par la
> proposer — §2. Ce qui nous aiderait, c'est autre chose et c'est plus petit.
>
> **3. Un écart contrat/moteur sur `POST /search`** : `resultType: "user"` est
> déclaré et n'est **jamais** servi. Mesuré chez vous. Il nous a coûté un onglet
> mort qu'aucune erreur n'aurait signalé — §3.

---

## 0. Ce que l'écran fait maintenant, pour situer

A2 réunit ce qui vivait en quatre endroits : les abonnés, la mise en « ami », la
désignation du contact d'urgence, et l'ajout de quelqu'un qui n'est pas sur
meetDo. Trois onglets — **Mes proches**, **Hors meetDo**, **Trouver**.

Le vivier des proches est l'**abonnement mutuel** (`/users/me/subscriptions` ∩
`/users/me/subscribers`), jamais un lien à sens unique : le statut d'ami ouvre,
côté traçabilité, la visibilité du statut live — c'est-à-dire où je suis et à
quelle heure je compte rentrer. Le poser sur un abonnement simple permettrait à
n'importe qui de suivre un profil pour être en position de le regarder sortir.
La réciprocité est la seule barrière qui existe déjà dans vos données.

La désignation, elle, passe par vos routes telles quelles :
`POST /guardians {memberId}` puis `POST /guardians/{id}/invite`, et rien n'a
bougé de ce côté.

---

## 1. Ce que nous demandons : un rôle sur `GuardianDto`

**Le problème.** `POST /watches` exige un `guardianId`, et il a raison — une
veille qui ne prévient personne n'est pas une veille. Mais rien n'oblige à poser
la question **au moment de partir**, et c'est ce que nous venons de corriger :
on désigne une fois, sur A2, et l'armement n'est plus qu'un bouton.

Sauf que `GuardianDto` ne porte **rien qui distingue un contact d'un autre** :
`id`, `type`, `name`, `phone`, `email`, `consentState`, `invitedAt`,
`respondedAt`, `createdAt`. Nous rangeons donc le choix dans le Trousseau de
l'appareil.

**Pourquoi ce n'est pas qu'un confort.** Avant cette version, la feuille
d'armement retombait sur « le premier contact accepté de la liste » — un ordre
qui vient de vous, qui n'a aucun sens pour la personne, et qui peut changer
entre deux ouvertures. Quelqu'un avec trois contacts acceptés armait donc au
profit de l'un d'eux **sans le savoir**, et pouvait l'apprendre le lendemain.
C'est exactement ce défaut que le réglage local retire — et qui revient intact
au premier changement de téléphone ou à la première réinstallation.

**La forme qui nous irait**, et nous n'y tenons pas plus que ça si vous en voyez
une meilleure :

- un champ **`role`** sur `GuardianDto` : `PRIMARY` · `BACKUP` · `NONE` ;
- `PUT /guardians/{id}/role` avec `{role}`, ou un `PATCH /guardians/{id}` — le
  verbe nous est égal.

**Deux invariants que nous vous demandons de tenir vous-même**, et c'est la
raison principale de préférer un champ serveur à notre stockage :

**a. Au plus un `PRIMARY` et au plus un `BACKUP`, et jamais le même contact
dans les deux.** Nous le garantissons déjà côté app — poser un rôle le retire
de l'autre — mais deux appareils connectés au même compte peuvent poser deux
principaux sans jamais se croiser. Un `guardianId == backupGuardianId` envoyé à
`POST /watches` produirait alors un refus au pire moment.

**b. Le rôle se libère tout seul.** Un contact supprimé, ou qui passe en
`REFUSED` après avoir accepté, ne doit pas laisser un rôle pointant dans le
vide. Dites-nous ce que vous choisissez : libérer le rôle, ou le laisser et
nous laisser l'ignorer. Nous ignorons déjà un défaut devenu non armable — nous
retombons sur le premier accepté plutôt que de retirer la veille à quelqu'un qui
part — mais autant que la règle soit écrite une fois.

**Une réserve de confidentialité, à trancher par vous.** Le contact d'urgence
sait déjà qu'il est désigné : il a donné son accord. Savoir qu'il est
**principal plutôt que secours** est une information de plus, et elle n'a pas
d'usage pour lui. Nous ne l'afficherons nulle part de son côté ; si votre page
de consentement public expose un jour le `GuardianDto`, ne mettez pas `role`
dedans.

---

## 2. Ce que nous vous demandons de **ne pas** faire : une relation d'amitié

Nous préférons l'écrire avant qu'on nous la propose, parce que la demande a
l'air raisonnable et que le refus n'est pas évident.

**Aujourd'hui, « ami » = abonnement mutuel + une étoile rangée sur l'appareil.**
L'étoile ne sert qu'à **ordonner** la liste — les proches d'abord — et n'ouvre
aucun droit. Elle ne part sur aucune route, elle ne déclenche aucune
notification, ni à la pose ni au retrait.

**Ce que ça coûte, et nous l'assumons :** la liste ne suit pas d'un appareil à
l'autre, et une réinstallation la perd.

**Ce que ça achète :** personne ne peut apprendre qu'il a été retiré. Une
notification « X t'a retiré de ses amis » est un drame que ce produit n'a aucune
raison d'héberger — et il suffirait d'une route de stockage pour la rendre
possible un jour, par une seule ligne côté serveur, écrite par quelqu'un qui
n'aura pas lu ce paragraphe. **Ne pas avoir la donnée est la seule garantie qui
tienne dans le temps.**

C'est aussi la raison pour laquelle nous ne vous demandons pas de « liste
d'amis » : le jour où elle existe, elle est interrogeable, exportable, et un
écran finira par afficher « vous n'êtes plus amis ».

**Ce qui nous aiderait vraiment, et c'est beaucoup plus petit :** un espace de
**préférences privées** par utilisateur — une clé, une valeur opaque, lisible et
écrivable **par son seul propriétaire**, jamais servie à un tiers ni jointe à
aucun DTO public. Quelque chose comme :

```
GET    /users/me/preferences/{key}   → { "value": "<opaque>" }
PUT    /users/me/preferences/{key}     { "value": "<opaque>" }
DELETE /users/me/preferences/{key}
```

Nous y mettrions l'étoile — et le reste de nos réglages locaux du même genre.
La différence avec une relation d'amitié n'est pas cosmétique : une valeur
opaque appartenant à une seule personne ne peut pas devenir, par inadvertance,
une information sur quelqu'un d'autre. Elle ne se joint à rien, ne se cherche
pas, et aucun écran ne peut la lire à l'envers.

Si vous jugez que ça sort de votre périmètre, dites-le simplement : nous
garderons le Trousseau, et le coût — une liste à refaire après une
réinstallation — reste très inférieur au risque de l'autre solution.

---

## 3. `resultType: "user"` est déclaré, et le moteur n'en rend jamais

Ce n'est pas une demande, c'est un **écart entre votre contrat et votre moteur**
que nous avons découvert en construisant l'onglet « Trouver ».

`SearchResultDto.resultType` déclare trois valeurs : `user`, `program`, `slot`.
Nous avons donc écrit un onglet qui garde les `user`. Il est resté vide.

**Mesuré en production le 02/09**, depuis Berlin, rayon 200 km, avec un compte
authentifié :

| Requête | Résultats |
|---|---|
| `Lena Müller` | 1 × `program`, 0 × `user` |
| `Seyd` | 2 × `program`, 0 × `user` |
| `Müller` | 1 × `program`, 0 × `user` |
| `Laufen` | 4 × `program`, 0 × `user` |

Les deux premiers sont les **noms exacts de comptes existants** — le nôtre et
celui de notre profil de démonstration. Une cinquième requête depuis Paris rend
`type: "empty"` avec un `parsedIntent.activityKeyword: "Lena"` et l'action
`CREATE_SLOT` « Être le premier à proposer Lena dans votre zone » : le moteur
lit le nom d'une personne comme un **mot-clé d'activité**.

Et il n'y a aucun moyen de lui demander autre chose : votre `SearchRequest` est
`{query, lat, lng, radiusMeters, page, pageSize, accessibilityTags}` — **pas de
champ `type`**. Notre `SearchType.users` côté client ne part nulle part.

**Nous ne vous demandons pas de recherche de personnes.** Nous avons pris
l'autre chemin, et il nous paraît plus juste que celui que nous avions prévu :
chaque résultat porte `organizerId`, `organizerName` et `organizerAvatarUrl`.
L'onglet dédoublonne les organisateurs, s'écarte lui-même, et propose de
s'abonner. **Sur meetDo on rencontre les gens par ce qu'ils proposent** — et
ça marche aujourd'hui, sans rien attendre de vous. Les vrais `user` sont gardés
et passeraient en tête si votre moteur en rendait un jour.

**Ce que nous demandons est donc uniquement d'aligner le contrat sur le
moteur** : soit vous retirez `user` de l'énumération, soit vous documentez que
la valeur est réservée et jamais servie. En l'état, elle promet une
fonctionnalité qui n'existe pas, et elle nous a coûté un onglet mort qu'aucune
erreur n'aurait signalé — nous ne l'avons vu qu'en interrogeant votre
production.

Une remarque au passage, si vous ouvrez ce chantier un jour : chercher un proche
par son nom n'a **rien de géographique**. Quelqu'un qui cherche son frère à
Berlin depuis Paris a une raison parfaitement légitime de le trouver, et
`lat`/`lng` obligatoires n'auraient aucun sens pour cette recherche-là.

---

## 4. Récapitulatif

| # | Demande | Nature |
|---|---|---|
| 1 | `role` (`PRIMARY`/`BACKUP`/`NONE`) sur `GuardianDto`, avec la route pour le poser | demande |
| 2 | Les deux invariants du §1 tenus côté serveur : unicité des rôles, et libération à la suppression ou au refus | demande |
| 3 | `role` absent des DTO exposés au contact lui-même | précaution |
| 4 | **Ne pas** créer de relation d'amitié — voir §2 | demande de ne rien faire |
| 5 | Un espace de préférences privées par utilisateur, valeur opaque | souhait, refusable |
| 6 | Aligner le contrat sur le moteur : `resultType: "user"` est déclaré et jamais servi | correction de contrat |

Rien ici n'est bloquant : l'écran est livré et fonctionne avec vos routes
actuelles. Ce sont des réglages qui, aujourd'hui, ne survivent pas à un
changement d'appareil.
