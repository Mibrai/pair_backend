# Réponse au SEPTIES — les quatre points sont faits, et votre §1 cachait un défaut plus grave que celui que vous redoutiez

**Date :** 2026-09-02
**Fait suite à :** `PROMPT_BACKEND_2026-09-02-SEPTIES.md`

> **`role` est livré**, avec les deux invariants tenus par la base et non par vous
> — §1.
>
> **⚠️ Mais votre crainte du §1 était fausse, et la réalité est pire.** Un
> `guardianId == backupGuardianId` **ne produit aucun refus** aujourd'hui : il
> saute la vérification et arme la veille. À l'escalade, le même proche est
> prévenu **deux fois**, et la chronologie inscrit « contact de secours prévenu ».
> La veille affiche une seconde ligne de défense qui n'existe pas. Corrigé — §2.
>
> **Nous ne créons pas de relation d'amitié**, et votre argument est le bon. Les
> préférences privées sont livrées — §3.
>
> **`resultType: "user"` est retiré.** Votre mesure était juste, et la cause est
> structurelle : il y avait même une fabrique dédiée, sans appelant, depuis
> l'origine. **Une clé de facette disparaît avec elle — c'est la seule rupture de
> ce lot et elle peut vous casser** — §4.
>
> **Votre §3 (le rôle invisible au contact) est déjà garanti par la structure**,
> et pas par une précaution qu'il faudrait tenir — §1.4.

---

## 1. `role` sur `GuardianDto`, et les deux invariants

Livré exactement sous la forme que vous proposiez, parce qu'elle est la bonne :
un champ `role` valant `PRIMARY` · `BACKUP` · `NONE`, et
**`PUT /api/guardians/{id}/role`** avec `{"role": "..."}`.

**1.1 — Le champ est toujours servi.** `NONE` pour un contact sans rôle, jamais
l'absence de champ — nous avons retenu la leçon de votre `alertDelivery`. En base
c'est un `NULL`, converti aux deux bornes : le cas « aucun rôle » est celui de la
grande majorité des lignes, et le représenter par l'absence évitait de
rétro-remplir toute la table.

**1.2 — Invariant (a), au plus un de chaque, tenu par la base.** Deux index
partiels uniques sur `(owner_id)`, l'un pour `PRIMARY`, l'autre pour `BACKUP`.
Votre raison est celle que nous avons écrite dans la migration : *un invariant que
seul le client tient n'est pas un invariant, il ne survit pas au second client*.

**Poser un rôle le retire à celui qui le portait**, dans la même transaction.
Nous n'avons pas voulu d'un « libérez d'abord, posez ensuite » : la fenêtre entre
les deux appels laisse un compte sans principal si le second échoue, et c'est
précisément le réglage qu'on ne veut pas perdre. Poser `BACKUP` sur le principal
actuel le fait donc cesser d'être principal — c'est ce que la personne demande en
le déplaçant.

**1.3 — Invariant (b) : nous libérons le rôle.** Vous nous laissiez le choix.

Un contact **supprimé** emporte sa ligne, donc son rôle : rien à faire. Un contact
qui **refuse après avoir accepté** perd son rôle au moment du refus.

La raison de préférer libérer : un principal qui a dit non est un réglage qui
pointe dans le vide, et il est *pire qu'absent*. Votre feuille d'armement le
proposerait en premier, et notre armement le refuserait — vous auriez un défaut au
moment de partir, ce que ce module doit éviter par-dessus tout. **Un choix absent
se voit ; un choix mort ne se voit pas.** Votre repli (« nous retombons sur le
premier accepté ») reste donc en place, mais il ne sera sollicité que quand il n'y
a réellement pas de principal.

Symétriquement, **poser un rôle sur un contact déjà `REFUSED` est refusé** — ce
serait créer le réglage mort que le refus efface. Un contact `PENDING`, en
revanche, **accepte** un rôle : on désigne d'abord, on invite ensuite, et l'ordre
inverse vous obligerait à revenir sur l'écran après la réponse du contact.

**1.4 — Votre §3 est garanti par la structure, pas par une promesse.** Vous
demandiez que `role` n'apparaisse pas dans une vue servie au contact. C'est déjà
impossible : le flux public de consentement ne sert **pas** `GuardianDto`, il passe
par une projection dédiée qui ne porte que le nom du parrain et l'état. Un champ
ajouté au DTO ne peut pas fuir par cette porte. Nous l'avons écrit dans le
javadoc du record, pour que quelqu'un qui voudrait un jour montrer quelque chose
au contact sache qu'il lui faut sa propre projection.

Sept tests couvrent la pose, l'échange, l'unicité, le refus, la libération au
refus, et l'appartenance au parrain.

---

## 2. Ce que nous avons trouvé en vérifiant votre crainte

Vous écrivez :

> « Un `guardianId == backupGuardianId` envoyé à `POST /watches` produirait alors
> un refus au pire moment. »

**Il n'y a pas de refus.** La validation était écrite ainsi :

```java
if (req.backupGuardianId() != null && !req.backupGuardianId().equals(req.guardianId())) {
    exigerContactAccepte(userId, req.backupGuardianId());
}
```

Le cas égal ne déclenche pas une erreur : **il saute la vérification** et la veille
s'arme avec le même contact aux deux postes. Ce qui suit est silencieux et pire
qu'un refus.

À l'escalade, le contact principal est prévenu. Puis, à +75 minutes, la branche du
contact de secours voit un `backupGuardianId` non nul, prévient **la même
personne** une seconde fois, et inscrit `BACKUP_ALERTED` à la chronologie. Le
système croit avoir sollicité un second recours ; il a envoyé deux fois le même
message au même proche. **La veille affiche une seconde ligne de défense qui
n'existe pas** — et c'est le genre de fausse assurance qu'un module de sécurité ne
peut pas se permettre.

C'est corrigé : le cas est désormais refusé (`422 WATCH_BACKUP_SAME_AS_PRIMARY`),
avec un test. Votre `role` rend l'erreur improbable côté client ; nous ne voulions
pas que la route continue de l'accepter pour autant.

Nous vous le rapportons parce que vous nous l'avez fait trouver en énonçant une
crainte **fausse mais bien dirigée** : vous regardiez au bon endroit, et ce qui
s'y trouvait était plus grave que ce que vous imaginiez.

---

## 3. Votre §2 : nous ne créons pas de relation d'amitié, et voici l'espace

**Nous ne construisons pas la relation, et votre argument emporte la décision.**
Vous écrivez que « ne pas avoir la donnée est la seule garantie qui tienne dans le
temps ». C'est exact, et c'est plus fort que les garanties d'accès : une relation
stockée devient interrogeable, exportable, et un écran finit par afficher « X vous
a retiré de ses amis » — écrit un jour par quelqu'un qui n'aura lu ni votre
paragraphe ni cette réponse.

Nous avons recopié ce raisonnement **dans la migration et dans le contrôleur**,
pas seulement ici. Un refus argumenté qui ne vit que dans un document est un refus
qui sera annulé.

**L'espace de préférences est livré**, tel que vous le décriviez :

```
GET    /api/users/me/preferences/{key}   → { "value": "<opaque>" }   (404 si absente)
PUT    /api/users/me/preferences/{key}     { "value": "<opaque>" }
DELETE /api/users/me/preferences/{key}                               (idempotent)
```

Ce que nous avons décidé, et que vous ne précisiez pas :

- **la clé** est bornée à `[a-zA-Z0-9._-]`, 64 caractères — un identifiant
  technique de votre côté, jamais une saisie d'utilisateur : ainsi elle ne peut ni
  transporter de contenu, ni ressembler à un chemin ;
- **la valeur** est bornée à 8192 caractères. Un porte-clés de réglages, pas un
  stockage de documents ;
- **rien n'indexe la valeur**, et le dépôt n'a aucune méthode qui cherche par
  valeur. C'est écrit comme une interdiction dans le javadoc du dépôt : la
  propriété entière de cet espace tient à cette absence ;
- **aucune colonne ne référence un autre utilisateur.** C'est ce qui garantit
  mécaniquement qu'un réglage privé ne peut pas devenir une information sur
  quelqu'un d'autre.

Huit tests, dont celui qui porte la propriété centrale — la préférence de
quelqu'un n'est lisible que par lui — et qui est nommé comme tel : s'il tombe, la
fonctionnalité a perdu sa raison d'être.

---

## 4. `resultType: "user"` : retiré, et une rupture à vérifier chez vous

**Votre mesure était juste, et la cause est plus structurelle que vous ne pouviez
le voir.** `user` n'est pas une valeur qui a cessé d'être servie : elle n'a
**jamais** été produite. Les trois seuls producteurs rendent `slot` ou `program`.
Il existait même une fabrique dédiée `forUser(...)`, écrite, complète, et **sans
aucun appelant depuis l'origine**.

Nous avons choisi de **retirer** plutôt que de documenter. Une valeur qu'aucun
code n'émet n'est pas « réservée », elle est fausse ; la documenter comme réservée
aurait laissé en place la promesse qui vous a coûté un onglet mort. La fabrique
morte est supprimée dans le même geste.

**⚠️ Une clé de facette disparaît avec elle, et c'est la seule rupture de ce
lot.** `countsByType` initialisait `"user": 0` — une facette structurellement
nulle, pour un type qui ne pouvait pas exister. Elle part aussi :

```jsonc
// avant
"countsByType": { "user": 0, "program": 4, "slot": 2 }
// après
"countsByType": { "program": 4, "slot": 2 }
```

Vous écrivez garder le traitement des `user` « au cas où le moteur en rendrait un
jour ». **Si votre code lit `countsByType['user']` sans le protéger, il recevra un
nul là où il recevait zéro.** Nous vous le signalons parce que c'est exactement la
forme de défaut dont vous nous parlez depuis hier : quelque chose qui ne casse pas
au compilateur et qu'aucune erreur ne signale. Nous pouvons remettre la clé si
vous préférez la garder le temps d'une version — dites-le, c'est une ligne.

**Votre remarque sur la géographie est notée**, et elle est juste : chercher son
frère à Berlin depuis Paris n'a rien de local, et `lat`/`lng` obligatoires
n'auraient aucun sens pour cette recherche. Nous l'avons écrit dans le javadoc du
champ, à côté de la raison du retrait — le jour où la recherche de personnes
existera, elle reviendra avec le code qui la produit, et vraisemblablement sans
contrainte de rayon.

**Enfin : votre chemin est meilleur que celui que vous aviez prévu.** Rencontrer
les gens par ce qu'ils proposent est plus juste, sur ce produit, qu'un annuaire —
et ça marche aujourd'hui, sans rien attendre de nous.

---

## 5. Récapitulatif

| # | Point | État |
|---|---|---|
| 1 | `role` sur `GuardianDto` + `PUT /guardians/{id}/role` | **Livré.** `NONE` servi, jamais l'absence |
| 2 | Unicité des rôles | **Tenue par la base** — deux index partiels uniques. Poser retire à l'autre, atomiquement |
| 3 | Libération du rôle | **Libéré** au refus et à la suppression. Un contact `REFUSED` n'en prend pas |
| 4 | `role` invisible au contact | **Garanti par la structure** : le flux public ne sert pas ce DTO |
| 5 | `guardianId == backupGuardianId` | **Ne produisait aucun refus.** Défaut réel corrigé — `422` (§2) |
| 6 | Relation d'amitié | **Non construite**, et le refus est argumenté dans le code |
| 7 | Préférences privées | **Livrées**, clé bornée, valeur opaque, jamais indexée |
| 8 | `resultType: "user"` | **Retiré**, fabrique morte supprimée |
| 9 | Clé de facette `"user"` | **⚠️ Retirée aussi.** À vérifier chez vous, ou nous la remettons |

**Ce que nous attendons de vous : une seule chose**, le point 9. Dites-nous si la
disparition de la clé `user` de `countsByType` vous casse — c'est la seule chose
de ce lot qui puisse vous surprendre en silence.

Et le message d'une ligne sur le déploiement viendra ; il portera maintenant deux
lots.
