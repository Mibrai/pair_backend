# Un booléen sur `UserPrivateDto` — nous payons une liste pour répondre par oui ou non

**Écrit le 2026-09-09, le soir.**
Fait suite à : [`SUITE_CLIENT_2026-09-09.md`](SUITE_CLIENT_2026-09-09.md) ·
Votre livraison : [`REPONSE_BACKEND_2026-09-09-BIS.md`](REPONSE_BACKEND_2026-09-09-BIS.md)

> **Une seule demande, petite, et non bloquante.** Un champ
> `hasPublishedAffiche` sur `UserPrivateDto`. Il retire une requête du chemin le
> plus chaud de l'app — le fil — et il ne coûte rien, `/users/me` étant déjà
> chargée au démarrage.
>
> **Nous avons vérifié le seul point qui pouvait la rendre inacceptable** :
> `/users/me` rend `UserPrivateDto`, un schéma **distinct** de `UserPublicDto`.
> Le drapeau n'est donc lu que par son propriétaire. Sur le DTO public, ce
> serait une fuite — § 3.

---

## 1. Ce que nous avons livré depuis hier soir, et qui crée le besoin

La bande d'affiches du fil porte désormais, sur **mon** visage, une pastille
« + » qui amorce la publication — et qui n'apparaît **que tant que je n'ai rien
publié**. Quand le module n'a composé aucune affiche, elle ouvre une feuille qui
explique d'où vient une affiche : une présence confirmée, une contribution qui
fait naître la carte-souvenir, et une séance qui écrit quelque chose de neuf.

Le geste de publication lui-même est branché (il ne l'était pas : le bouton
existait depuis la bascule du matin et ne faisait rien — notre défaut, corrigé et
gardé par une assertion qui rend le cas impossible).

## 2. Le coût que cela ajoute, et pourquoi il est mal placé

Pour savoir si la pastille doit paraître, le fil lit
`GET /users/{monId}/affiches`.

**Nous payons une liste pour répondre à un booléen.** La bande n'a besoin ni de
savoir *lesquelles* sont publiées, ni *combien* : seulement s'il y en a au moins
une. C'est l'inversion exacte de la règle que ce module s'applique partout
ailleurs — « jamais un compte là où un booléen suffit ».

Ce n'est pas grave et nous vivons avec : un aller-retour par session, la famille
n'étant pas `autoDispose` et le profil lisant déjà cette route. Mais c'est sur le
**fil**, l'écran d'entrée du produit, et c'est le genre de coût qu'on ajoute une
fois puis qu'on n'enlève jamais.

### Ce que nous avons écarté avant de vous écrire

**Le déduire de `/affiches/updates`** — la réponse tentante, et elle est morte.
Votre requête porte `a.user_id <> :viewerId` : cette route ne nous rend **jamais
nous-mêmes**, par construction, et c'est très bien ainsi. Elle ne pourra jamais
répondre à cette question-là.

**Le retenir sur l'appareil** — mauvais dans le sens dangereux. Après une
réinstallation ou un changement de téléphone, on oublie, et le « + » réapparaît
chez quelqu'un qui a publié : une invitation fausse. Et une publication faite
depuis un autre appareil ne serait jamais vue. Nous avons déjà assumé cette
faiblesse pour l'état « anneau vu » — elle y coûte une soirée, ici elle
mentirait.

---

## 3. La demande

```
GET /api/users/me
UserPrivateDto { …, hasPublishedAffiche }
```

Vrai dès qu'il existe au moins une affiche publiée par cette personne, quelle
que soit son audience — **y compris `NOBODY`**. C'est la nuance qui compte : la
question posée est « ai-je déjà fait ce geste ? », pas « quelqu'un peut-il la
voir ? ». Une affiche publiée en `NOBODY` est un geste posé, et la pastille
d'amorce n'a plus lieu d'être.

### Pourquoi sur ce DTO-là, et sur aucun autre

Nous avons relevé le contrat avant de demander : `/users/me` rend
`UserPrivateDto`, `/users/{id}` rend `UserPublicDto` — deux schémas distincts.

**Le drapeau doit vivre sur le privé, et nulle part ailleurs.** « Cette personne
a publié une affiche » est exactement ce que le filtre d'audience protège : sur
`UserPublicDto`, ce serait une fuite du fait même qu'une affiche existe, à
quelqu'un qui n'a peut-être pas le droit de la voir. C'est le raisonnement que
vous nous aviez opposé pour l'anneau, et il vaut ici sans changement.

Si votre `UserPrivateDto` était en réalité partagé avec une lecture publique
quelque part, **dites-le et nous retirons la demande** — nous préférons la
requête à un champ qui fuite.

### Le coût, tel que nous le voyons

Un `EXISTS` sur la table que vous venez d'écrire, projeté sur une réponse que le
client charge **déjà au démarrage** (`myProfileProvider` lit `/users/me`). Donc
**zéro requête** au lieu d'une, sur le fil.

Comme d'habitude : passez-le à votre harnais de comptage avant de livrer. Un
`EXISTS` corrélé qui deviendrait une requête par lecture de profil serait un
mauvais échange, et c'est vous seuls qui pouvez le voir.

### Ce que cela ne retire pas

**La lecture du profil reste.** `affiche_mise_en_avant` a besoin de la *liste*
pour dessiner l'affiche mise en avant — là, la question n'est pas un booléen. Ce
drapeau ne remplace `GET /users/{id}/affiches` que sur le fil, où seul le oui/non
est demandé.

---

## 4. Où en est le client

Cinq drapeaux allumés, module entier livré. **3 737 tests verts.**

`AFFICHE_READY` : le correctif des deux extensions est dans la build `1.0.0+13`,
**toujours pas en production**. Gardez la coupure ; nous vous écrivons le numéro
dès qu'une version distribuée le porte, et le **2026-10-15** reste la borne.

La question ouverte du § 5 de notre suite client — l'ambiance dominante sur
`AfficheDto`, qui rendrait `nouvelleAmbiance` dessinable chez autrui — tient
toujours, sans urgence et sans mesure. Elle n'est pas plus pressante aujourd'hui
qu'hier.

---

## Récapitulatif

| # | Demande | Bloque | Coût attendu |
|---|---|---|---|
| **B16** | `hasPublishedAffiche` sur **`UserPrivateDto`** | rien | un `EXISTS` sur une réponse déjà servie ; retire une requête du fil |
