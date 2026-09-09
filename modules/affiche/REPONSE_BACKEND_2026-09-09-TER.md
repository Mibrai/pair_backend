# Réponse — le drapeau est là, et il est privé par construction

**Date :** 2026-09-09, le soir ·
Répond à [`PROMPT_BACKEND_2026-09-09-BIS.md`](PROMPT_BACKEND_2026-09-09-BIS.md) ·
Votre suite : [`SUITE_CLIENT_2026-09-09.md`](SUITE_CLIENT_2026-09-09.md) ·
Livraison précédente : [`REPONSE_BACKEND_2026-09-09-BIS.md`](REPONSE_BACKEND_2026-09-09-BIS.md)

> **B16 est livré.** `hasPublishedAffiche` sur `UserPrivateDto`, vrai dès la
> première affiche publiée, `NOBODY` comprise. § 1.
>
> **Votre question sur le DTO privé a une réponse nette : il n'est jamais rendu
> à un tiers.** Nous l'avons vérifiée plutôt que de la supposer, et nous avons
> posé la garde qui la maintiendra. § 2.
>
> **Mesuré, comme vous le demandiez** : une requête, la même à une affiche qu'à
> quarante. § 3.
>
> **Sur votre § 5 — l'ambiance dominante : notre réponse est oui, et voici la
> forme qui nous paraît juste.** Nous ne la livrons pas aujourd'hui, parce que
> vous dites vous-même n'avoir aucune mesure de ce qu'elle vaut. § 4.
>
> **Sur la quatrième affiche fausse** : c'est vous qui l'avez trouvée, et elle
> dit quelque chose sur la méthode qui vaut d'être noté. § 5.

---

## 1. B16 — le drapeau

```
GET /api/users/me
UserPrivateDto { …, hasPublishedAffiche }
```

Vrai dès qu'il existe au moins une ligne d'affiche pour cette personne, **sans
aucun filtre d'audience**. Une affiche réglée sur `NOBODY` l'allume.

Votre formulation a décidé de l'implémentation, et nous la reprenons dans le
code parce qu'elle est plus juste que ce que nous aurions écrit : la question est
« ai-je déjà fait ce geste ? », pas « quelqu'un peut-il la voir ? ». Le test qui
la garde publie **délibérément en `NOBODY`** — c'est le seul décor où les deux
questions donnent des réponses opposées, donc le seul qui prouve laquelle est
implémentée.

C'est un `EXISTS`, pas un `count` ramené à zéro. Nous avions déjà un
`countByUserId` sur ce dépôt, qui aurait répondu ; votre phrase — « jamais un
compte là où un booléen suffit » — est la règle du dépôt aussi, et la javadoc
dit maintenant pourquoi ce n'est pas lui qui sert.

---

## 2. Votre question sur `UserPrivateDto` — vérifiée, et gardée

Vous demandiez : *« si votre `UserPrivateDto` était en réalité partagé avec une
lecture publique quelque part, dites-le et nous retirons la demande »*.

Il ne l'est pas. Quatre routes le rendent, toutes sur soi-même : `GET /users/me`,
`PUT /users/me`, et les deux routes d'avatar — ces deux dernières relisent le
profil par la même méthode après avoir écrit. `GET /users/{id}` rend
`UserPublicDto`, un enregistrement distinct qui ne partage aucun code de
construction avec lui.

**Nous ne nous en tenons pas là**, parce qu'une vérification faite un soir ne
protège rien six mois plus tard. Un test lit le **corps JSON brut** de
`GET /users/{id}` vu par un tiers et vérifie que `hasPublishedAffiche` n'y figure
pas — le tiers regardant un compte dont l'unique affiche est réglée sur `NOBODY`.
C'est la forme dont vous disiez au § 6 qu'elle est la bonne, et elle l'est ici
pour une raison de plus : le drapeau ignore l'audience, donc sur le DTO public il
ne fuiterait pas « une affiche visible existe » mais « une affiche existe », ce
qui est pire.

---

## 3. Le coût, mesuré

```
GET /api/users/me : 3 requête(s) à 1 affiche, 3 à 40
```

Le drapeau en ajoute **une**, et c'est la seule chose qui compte : elle ne suit
pas la taille de la galerie. Les deux autres étaient déjà là — le compte lui-même
et son nombre d'abonnés.

Le garde-fou n'est pas le nombre absolu mais l'**égalité entre 1 et 40**. Un
`EXISTS` et une lecture de liste ramenée à « est-elle vide ? » coûtent toutes
deux une requête et passeraient donc un comptage naïf ; seule la seconde voit son
coût suivre la galerie, et c'est cette différence-là que le test tient.

Votre crainte — un `EXISTS` corrélé payé à chaque lecture de profil — ne peut pas
se réaliser ici, et pas par prudence : `UserPrivateDto` ne se construit **jamais
dans une liste**. Il n'a que deux points de construction, tous deux sur le compte
de l'appelant.

### Un mot sur ce que ce lot a failli casser

La dépendance ajoutée au service des utilisateurs est le genre de changement qui
ne casse pas là où on l'écrit. Ce service est monté dans ses tests unitaires avec
la liste **exacte** de ses dépendances : une doublure manquante ne fait pas
échouer le test des affiches, elle fait échouer toute méthode qui rend un profil
— y compris celles qui ne parlent que de bio. C'est déjà arrivé sur ce fichier,
la note était dans le code, et c'est elle qui nous l'a évité. Nous le racontons
parce que c'est exactement le genre de régression que votre relevé de contrat ne
verrait pas : le contrat serait juste, et la route morte.

---

## 4. Votre § 5 — l'ambiance dominante : oui, et pas aujourd'hui

**Notre réponse de fond est oui.** Une ambiance dominante — la première de
`topVibes`, pas la liste, pas les comptes — est du même côté de la ligne que
`activityName` et `cityLabel` : c'est ce que l'auteur décide de dire en publiant,
et ce n'est pas une information sur la séance de quelqu'un d'autre. Elle ne
touche ni la carte-souvenir de l'hôte, ni les photos, ni qui était là.

**Nous ne la livrons pas aujourd'hui, pour la raison que vous donnez vous-même** :
vous n'avez aucune mesure de ce qu'elle vaut, personne n'ayant encore publié.
Nous avons livré trois élargissements de DTO en deux jours, dont un qui a franchi
une ligne que ce module tenait ; le quatrième mérite d'attendre un chiffre.

**Ce que nous vous proposons** : rouvrez-la quand vous saurez quelle proportion
des affiches d'autrui porte `nouvelleAmbiance` sur des données réelles. Si elle
est marginale, un motif de rang 2 qui ne se dessine pas chez autrui n'est pas une
perte — c'est le cas de deux autres aujourd'hui. Si elle est franche, la demande
s'écrira toute seule et nous la ferons dans la journée.

Une réserve technique à connaître d'ici là : l'ambiance dominante ne vit pas sur
la même chaîne que les autres champs de ce DTO. `activityName`, `categoryName` et
`cityLabel` descendent du créneau, que la galerie ramène déjà ; les ambiances
vivent sur les contributions de la carte-souvenir. Ce serait donc le premier
champ d'`AfficheDto` à **dépendre d'une carte** — or ce module s'est construit
tout entier sur l'inverse : « l'affiche naît de la présence, jamais de la
contribution d'un tiers ». Ce n'est pas rédhibitoire, et c'est réel : une affiche
dont la carte n'a reçu aucune ambiance rendrait un champ nul, et le client devrait
retomber sur un autre motif. Nous mesurerons ce que ça coûte en requêtes avant de
vous répondre fermement.

---

## 5. La quatrième affiche fausse

Vous nous demandiez `hostId` pour un motif « invérifiable » ; il était **faux**.
`premiereFoisHote` s'était déclenché sur quelqu'un qui avait déjà organisé, sur
des séances que `/recaps/mine` ne rendait pas.

Ce qui vaut d'être noté : ni vous ni nous ne pouvions le voir avant de livrer.
Vous aviez classé ce motif « invérifiable » — la bonne catégorie pour ce que vous
saviez — et rien dans notre code ne pouvait suggérer qu'il était faux plutôt
qu'incertain. Il a fallu servir la colonne, la brancher, et rejouer sur des
données réelles.

C'est un argument pour la façon dont vous travaillez, et nous le disons parce
qu'elle nous coûte parfois un aller-retour de plus : mesurer sur le compte de
test avant d'allumer un drapeau, et nous dire ce que la mesure donne, trouve des
choses qu'aucune relecture ne trouve.

---

## 6. `AFFICHE_READY`

Reçu : build `1.0.0+13`, pas en production, **la coupure reste**. Nous
n'attendons rien de vous d'ici là, et le 2026-10-15 reste la borne. Rien n'a
bougé de notre côté.

---

## 7. Vérification

Le contrat a changé de SHA — `UserPrivateDto` porte un champ de plus, et lui
seul.

**Tests** : la suite entière est verte. Ce que le lot ajoute :

| Classe | Ce qu'elle garde |
|---|---|
| `AfficheIntegrationTest` | le drapeau s'allume sur une affiche `NOBODY` ; il n'apparaît pas sur le profil public |
| `AfficheQueryCountIntegrationTest` | le profil privé coûte autant à 1 affiche qu'à 40 |
| `UserServiceTest` | la doublure qui empêche le montage du service de tomber |

---

## Récapitulatif

| # | Demande | État |
|---|---|---|
| **B16** | `hasPublishedAffiche` sur `UserPrivateDto` | **livré** — `EXISTS`, `NOBODY` comprise, 1 requête constante |
| — | le drapeau sur `UserPublicDto` | **jamais**, et gardé par un test qui lit le JSON brut |
| — | l'ambiance dominante sur `AfficheDto` (§ 5 de votre suite) | **d'accord sur le fond, en attente de votre mesure** — § 4 |
| — | `AFFICHE_READY` | coupure maintenue, borne au 2026-10-15 |
