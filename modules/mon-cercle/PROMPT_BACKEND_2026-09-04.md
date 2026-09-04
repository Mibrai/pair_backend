# Trouver quelqu'un : la recherche de personnes ne voit pas la moitié de ce qu'elle devrait

**Date :** 2026-09-04

> **L'onglet « Trouver » de Mon cercle est devenu un module à part**, avec son
> domaine, son dépôt et son interrupteur — décision produit du 04/09. Il ne
> partage plus rien avec la recherche de l'Explorer, qui ne cherche pas la même
> chose : celle-ci cherche **ce qui se passe autour de moi**, celle-là cherche
> **quelqu'un à suivre**. Un nom n'a pas de latitude — §1.
>
> **Ce qu'on attend de lui :** on tape n'importe quoi — un nom, une ville, une
> activité, le titre d'une soirée — et les **personnes** concernées s'affichent,
> qu'elles correspondent elles-mêmes ou qu'elles organisent ce qui correspond.
>
> **Trois défauts mesurés le 04/09 l'en empêchent, et ils sont chez vous** :
> `GET /users?query=` n'indexe pas ce que les gens organisent, n'est pas
> insensible aux accents, et **ne trouve pas certains comptes même sur leur nom
> exact** — §2.
>
> **Une demande de fond derrière ces trois-là**, et c'est elle qui compte : une
> recherche de personnes qui n'exige **aucune position** — §3.

---

## 1. Ce que nous avons livré aujourd'hui, sans vous attendre

Le module est écrit et branché : `lib/features/circle_find/`, drapeau
`FeatureFlags.circleFind`, onze tests. Il compose deux sources :

| Source | Ce qu'elle apporte | Position exigée |
|---|---|---|
| `GET /users?query=` | le nom, la bio, les activités **déclarées au profil** | non |
| `POST /search` (organisateurs) | les titres de programmes et de créneaux | **oui**, `lat`/`lng` obligatoires |

La seconde n'est donc là **que si une position est déjà connue**. Nous ne
l'attendons jamais — cet onglet est resté muet une demi-minute le 02/09 parce
qu'il attendait un point GPS avant de chercher un nom. Quand elle manque,
l'écran le dit plutôt que d'annoncer un vide : « sans ta position, les titres de
programmes et de créneaux n'ont pas été cherchés ».

C'est honnête, et ce n'est pas satisfaisant : **la moitié des correspondances
dépend d'une permission qui n'a rien à voir avec la question posée.**

---

## 2. Les trois défauts, mesurés

Compte de test, production, le 04/09.

### 2.1 · `GET /users?query=` n'indexe pas ce que les gens organisent

```
Un créneau réel : « Basketball — vendredi 4 septembre », hôte Lelouche01

GET /users?query=Basketball — vendredi 4 septembre  → 0
GET /users?query=Basketball                         → 1  (Kai Hartmann)
```

Kai Hartmann remonte parce qu'il a déclaré « Basketball » **sur son profil**.
L'hôte du créneau, lui, ne remonte pas — alors que c'est précisément la personne
qu'on cherche quand on tape le titre d'une soirée.

**Notre demande :** que la recherche de personnes indexe aussi les **titres des
programmes et des créneaux qu'une personne organise**. C'est ce qui ferait de
`GET /users` une recherche de personnes complète, et qui rendrait la seconde
source — donc la position — facultative pour de bon.

### 2.2 · Elle n'est pas insensible aux accents

```
GET /users?query=Müller  → 2  (Anna Müller, Lena Müller)
GET /users?query=muller  → 0
```

Quelqu'un qui tape « muller » sur un clavier sans tréma ne trouve personne. Le
même mot, la même personne, zéro résultat. C'est la recherche la plus banale qui
soit, et elle échoue en silence.

**Notre demande :** normaliser les diacritiques des deux côtés de la comparaison,
comme le fait déjà la recherche floue des programmes.

### 2.3 · Certains comptes ne se trouvent pas, même sur leur nom exact

C'est le plus troublant, et nous ne savons pas l'expliquer :

```
Lelouche01 organise un créneau visible dans /slots/bounds.

GET /users?query=Lelouche01  → 0
GET /users?query=Lelouche    → 0
GET /users?query=louche      → 0
GET /users?query=LELOUCHE    → 0
```

Alors que la recherche par sous-chaîne fonctionne ailleurs — `njoya` rend Seyd
Njoya, `Kai` rend Kai Hartmann. Ce compte-là est **absent de l'index**, pas mal
orthographié.

**Notre question :** quelle condition exclut un compte des résultats ? Profil
incomplet, e-mail non vérifié, réglage de visibilité, compte de démonstration ?
Nous ne demandons pas de la lever — il y a sans doute une bonne raison —, nous
demandons à la **connaître**, parce que nous ne pouvons pas expliquer à
quelqu'un pourquoi son ami est introuvable.

---

## 3. La demande de fond : chercher des personnes sans position

Les trois points ci-dessus se ramènent à un seul besoin : **une recherche de
personnes qui n'ait pas besoin de savoir où l'on est.**

Aujourd'hui, ce qui manque à `GET /users` — les titres — n'existe que derrière
`POST /search`, qui exige `lat`/`lng`. Résultat : quelqu'un qui a refusé la
localisation, ou qui cherche depuis un train, ne peut pas trouver l'organisateur
d'une soirée par son nom. La géographie n'a pourtant rien à voir avec la
question « qui est cette personne ? ».

Deux formes possibles, et la première nous paraît la bonne :

**a. Élargir l'index de `GET /users?query=`** aux titres de ce que la personne
organise. Rien à changer chez nous : la route est déjà appelée, sans position, et
c'est celle qui porte l'onglet.

**b. Ou rendre `lat`/`lng` facultatifs sur `POST /search`** quand la requête est
textuelle. Nous la préférons moins : elle nous ferait dépendre du moteur de
l'Explorer pour un écran qui vient justement de s'en séparer.

---

## 4. Récapitulatif

| # | Point | Nature |
|---|---|---|
| 1 | `GET /users?query=` doit indexer les titres des programmes et créneaux organisés | **demande** — c'est la moitié manquante |
| 2 | Insensibilité aux accents (`muller` = `Müller`) | demande |
| 3 | Pourquoi certains comptes sont absents de l'index, même sur leur nom exact | **question** |
| 4 | Une recherche de personnes sans position | demande de fond — §3, la forme (a) nous va |
| — | `POST /search` | **rien à changer** si le §1 est fait |
