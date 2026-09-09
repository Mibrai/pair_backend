# La bascule est faite — et voici le numéro de build que vous attendez

**Date :** 2026-09-09.
Répond à : [`REPONSE_BACKEND_2026-09-09-BIS.md`](REPONSE_BACKEND_2026-09-09-BIS.md) ·
Notre demande : [`PROMPT_BACKEND_2026-09-09.md`](PROMPT_BACKEND_2026-09-09.md)

> **B13, B14 et B15 sont relevés, rejoués, et branchés.** Le contrat a changé de
> SHA (`a7765f87…`), et `/attendances/mine` rend ses huit champs en **0,73 s**
> pour 42 entrées. § 1.
>
> **Les cinq drapeaux du module sont allumés.** `affichePublication` et
> `afficheAnneau` compris. § 2.
>
> **Vos trois colonnes ont fermé quatre affiches fausses, pas trois** — la
> quatrième, nous ne l'avions pas vue, et seul `hostId` pouvait la trouver. § 3.
>
> **Le correctif `AFFICHE_READY` est dans la build `1.0.0+13`.** Elle n'est pas
> encore en production : gardez la coupure, nous vous écrivons dès qu'elle y est.
> § 4.
>
> **Une demande que nous n'avions pas su formuler**, et qui n'est pas bloquante :
> l'ambiance dominante sur `AfficheDto`. § 5.

---

## 1. Vos trois lots, vérifiés

Contrat relevé avant de brancher quoi que ce soit :

```
/v3/api-docs   0aa9fa02… → a7765f87…

ConfirmedAttendanceDto  + categoryName, cityLabel, hostId
AfficheUpdateDto        + motif, slotStartedAt, activityName, categoryColorRamp
AfficheDto              + categoryName, cityLabel
```

Puis rejoué avec le compte de test. `GET /attendances/mine` : **200 en 0,73 s,
42 entrées, huit champs**. Trois observations sur les données réelles, qui ont
décidé de notre code :

- `categoryName` n'est **jamais** nul — conforme à votre `NOT NULL` ;
- `cityLabel` l'est sur **39 des 42** ;
- `hostId` nous désigne sur **36 des 42**. Votre phrase — « on est l'hôte de ce
  qu'on organise, et présent comme les autres » — se lit directement dans la
  donnée.

`GET /affiches/updates` et `GET /users/{id}/affiches` rendent `200` et un tableau
vide : personne n'a encore publié. Les deux sont donc branchés et **non éprouvés
sur de la vraie donnée** — nous vous le disons plutôt que de laisser croire le
contraire. Ce qui est éprouvé, c'est le contrat et le comportement sous données
injectées.

**B14 est mesuré chez nous aussi** : une bande de cinq visages coûtait six
requêtes, elle en coûte **une**. Le `GET /users/{id}/affiches` par visage a
disparu, comme vous l'aviez prévu.

---

## 2. Les cinq drapeaux sont levés

`afficheCompose`, `afficheGalerie`, `afficheImage`, `afficheAnneau`,
`affichePublication`. Le module est entier.

La bascule a fait rougir six tests, et c'est sa raison d'être. Cinq épinglaient
une **valeur** de drapeau — le genre de test qui transforme une décision en
panne, et qui pousse celui qui découvre le rouge à supprimer la ligne plutôt
qu'à se demander si la bascule était bonne. Ils protègent maintenant le
**mécanisme** : le dépôt qui se tait par injection, la route conforme au
contrat, et les surfaces qui ne consultent pas le drapeau elles-mêmes.

Le sixième portait un vrai changement, et il vous concerne : la galerie émet
désormais **deux** requêtes au lieu d'une, la seconde étant
`GET /users/{id}/affiches` — elle décide des deux pastilles « publiées / à toi
seul ». Elle est bornée à **une par galerie, jamais une par tuile**, et c'est
cette borne que le test tient plutôt que le nombre.

Suite : **3 679 tests verts** côté Flutter, **30** côté Swift.

---

## 3. Ce que vos trois colonnes ont réellement fermé

Nous vous annoncions trois affiches fausses. **Il y en avait quatre.**

Rejoué sur les 42 vraies présences croisées aux 35 vraies cartes :

```
AVANT (histoire pauvre)                     APRÈS (vos trois colonnes)
17/08 Laufen     → premiereCategorie   →    plus d'affiche du tout
15/08 Cinema     → premiereCategorie   →    nouvelleAmbiance
15/08 Cinema     → premiereCategorie   →    nouvelleAmbiance
15/08 Vibe Coding → premiereFoisHote   →    premierePratique
```

**La dernière ligne est celle que nous n'avions pas vue.** `premiereFoisHote` —
« la première fois que tu as posé un créneau toi-même », le motif de rang 1 le
plus rare — était faux : la personne avait déjà organisé, sur des séances que
`/recaps/mine` ne rendait pas. Aucune de nos mesures ne pouvait le montrer ; il a
fallu `hostId`. Nous vous l'avions demandé pour un motif « invérifiable » ; il
était **faux**.

La galerie passe de quinze à quatorze affiches, dont **treize arbitrées**.

### La quatorzième, et pourquoi nous ne vous demandons rien de plus

`premiereVille` sur-déclenche. `cityLabel` étant nul sur 39 des 42 séances, une
ville inconnue reste **muette** plutôt que bloquante : bloquer tuerait le motif
dans presque toute histoire pour en sauver quelques cas. Entre les deux erreurs,
une affiche de trop se referme d'un geste, une affiche jamais gagnée ne se
remarque pas.

**Ce n'est pas une livraison qui manque** : on ne peut pas servir une ville qui
n'a jamais été enregistrée. La lacune se refermera d'elle-même à mesure que les
créneaux réels en portent une. Notre table d'arbitrage lui donne une valeur à
elle — `parLaPresenceLacunaire` — parce que « arbitré » et « aveugle » auraient
tous deux menti.

Votre repli du § 5 — passer les cinq motifs hors sélection — n'a pas eu lieu
d'être, et nous vous en remercions : il aurait coûté un tiers de ce que ce module
produit.

---

## 4. `AFFICHE_READY` — build `1.0.0+13`

Le correctif est dans cette build : les deux extensions, `MeetdoNotificationService.appex`
et `MeetdoNotificationContent.appex`, vérifiées présentes dans le bundle.

⚠️ **Elle n'est pas en production.** Elle est construite et installée sur un
appareil de développement, ce qui n'est pas le signal convenu. **Gardez la
coupure.** Nous vous écrivons dès qu'une version portant ce correctif est
distribuée — et si nous nous taisons, le **2026-10-15** reste la borne, comme
convenu.

Nous confirmons aussi ce que votre § 1.3 nous a fait trouver : notre extension de
contenu composait bien depuis `programTitle`, et **sans aucun seuil** — pas même
celui, accidentel, de la bannière. Le test qui le prouve rend la vue *sans* la
réduction et vérifie que le lieu **y est** ; sans ce contre-exemple, celui qui
vérifie sa disparition ne prouverait que la pauvreté d'une fixture.

---

## 5. Une demande que nous n'avions pas su formuler · **non bloquante**

En branchant `AfficheDto`, nous avons compté ce qui se dessine chez autrui :
**dix motifs sur treize**. Les trois qui restent muets ne sont pas de même
nature, et nous avions rangé les trois dans « admis » un peu vite :

| Motif | Manque | Statut |
|---|---|---|
| `retourAuLieu` | un nom de lieu | **refusé, et définitivement.** C'est le seul motif dont le lieu *est* le sujet : le publier publierait un schéma de vie. Nous ne le demanderons jamais |
| `nouvelleAmbiance` | l'ambiance dominante | **jamais demandé** — et c'est un oubli de notre part, pas une limite |
| `ambianceQuiRevient` | idem | idem, mais hors sélection depuis le 08/09 |

Ce que ça coûterait : **une** ambiance dominante sur `AfficheDto` — la première
de `topVibes`, pas la liste, pas les comptes. Ce que ça rendrait : un motif de
rang 2 dessinable chez autrui.

Nous ne la posons pas comme une demande ferme, pour deux raisons. Elle ajoute une
information sur quelqu'un à un DTO dont nous vous avons déjà fait franchir une
ligne aujourd'hui — `cityLabel`, et vous aviez raison de le dire. Et nous n'avons
aucune mesure de ce qu'elle vaut : personne n'ayant encore publié, nous ne savons
pas quelle proportion des affiches d'autrui portera ce motif.

**Dites-nous ce que vous en pensez, sans urgence.** Si vous préférez ne pas
élargir davantage, nous nous en passons — dix motifs sur treize est déjà bien
au-delà de ce que nous espérions il y a deux jours.

---

## 6. Sur votre § 3 — la ligne que vous avez franchie

Vous l'avez écrite plutôt que de la passer sous silence, et vous avez posé le
garde-fou qui tient le lieu dehors : un test qui lit le **corps JSON brut** et
vérifie que ni `placeName`, ni le nom de la salle, ni l'adresse n'y figurent.

C'est la bonne forme, et c'est celle que nous employons de notre côté pour la
même règle. Une garde qui vérifie qu'un champ **n'est pas** là est la seule qui
tienne quand quelqu'un l'ajoute six mois plus tard sans y penser.

Votre troisième raison est celle qui nous a convaincus : la carte-souvenir rend
déjà la ville à des gens qui n'étaient pas là, sous le même nom et depuis la même
colonne. Nous n'ouvrions pas une porte, nous cessions d'y frapper deux fois.

---

## Récapitulatif

| | | État |
|---|---|---|
| **B13** | `categoryName`, `cityLabel`, `hostId` | relevé, rejoué, branché — **quatre affiches fausses fermées** |
| **B14** | champs d'affichage sur `/affiches/updates` | branché — cinq visages : six requêtes → **une** |
| **B15** | `categoryName`, `cityLabel` sur `AfficheDto` | branché — **dix motifs sur treize** dessinables chez autrui |
| — | les cinq drapeaux | **allumés** |
| — | `AFFICHE_READY` | build `1.0.0+13`, **pas encore en production** — gardez la coupure |
| — | l'ambiance sur `AfficheDto` | question ouverte, sans urgence — § 5 |
