# Réponse — chercher quelqu'un sans savoir où l'on est

**Date :** 2026-09-04 · Réponse à `PROMPT_BACKEND_2026-09-04.md`

> **Les trois défauts sont corrigés**, et votre demande de fond avec eux : la
> recherche de personnes n'exige plus aucune position, et elle indexe désormais
> ce que les gens organisent. Forme (a), comme vous la préfériez.
>
> **Votre §2.3 était le plus grave, et vous aviez raison de ne pas l'expliquer :
> l'explication n'était pas dans l'index.** Le compte n'était pas absent, il
> était exclu par un réglage que l'application écrit à un endroit et que la
> recherche lisait à un autre. Activer « Localisation publique » ne servait à
> rien — §1.
>
> **Une correction à votre §2.2 :** la recherche floue des programmes n'est pas
> insensible aux accents. Elle en a l'apparence, et c'est un piège — §2.
>
> **Deux défauts que vous n'aviez pas signalés** et que nous corrigeons dans la
> foulée : on pouvait se trouver soi-même, et votre pagination était instable —
> précisément parce que vous n'envoyez pas de position — §4.

---

## 1. Le compte introuvable : un réglage écrit ici, lu là

Votre relevé sur Lelouche01 était juste, et la cause n'a rien à voir avec
l'orthographe ni avec un index. La requête portait cette clause :

```sql
WHERE u.is_active = true
  AND u.location_public = true      -- inconditionnelle
  AND (LOWER(u.display_name) LIKE … OR LOWER(u.bio) LIKE …)
```

`users.location_public` vaut **`FALSE` par défaut** (V2, `NOT NULL DEFAULT
FALSE`) et n'est écrit que par `PUT /api/users/me`. L'écran de confidentialité,
lui, passe par `PUT /api/users/me/privacy`, qui pose `show_location` et
`show_on_map` — et **ne touche jamais `location_public`**.

Trois champs disent donc la même chose à l'utilisateur, et la recherche n'en
lisait qu'un : celui qu'aucun geste de l'écran de confidentialité n'écrit.
Conséquence, et c'est elle qui compte : **activer « Localisation publique » dans
l'application ne rendait personne trouvable.** Le réglage était stocké,
réglable, relu, affiché — et lu par aucun code de décision.

Si Kai Hartmann et Seyd Njoya remontaient, c'est qu'ils viennent du semeur de
démonstration, qui pose `locationPublic(true)` à la main. Vous compariez donc des
comptes de démonstration à des comptes réels, ce qui rendait le défaut
indéchiffrable depuis l'extérieur. Nous ne pouvions pas vous demander de le
deviner.

**La recherche accepte désormais les trois** — `location_public`,
`show_location`, `show_on_map`. Qui a activé l'un quelconque devient trouvable ;
qui n'a rien activé ne le devient pas. Ce dernier point est tenu par un test :
rendre trouvables ceux qui ont fait un geste ne doit exposer personne qui n'en a
fait aucun.

**Ce que nous n'avons pas fait, et qu'il faudra décider un jour :** unifier ces
trois champs. « Où je suis » et « peut-on me trouver par mon nom » sont deux
questions différentes, et les faire porter par le même réglage restera bancal
tant que le produit n'aura pas tranché. Nous n'avons pas voulu le trancher à
votre place dans un lot de correction.

---

## 2. Les accents — et une correction à votre prompt

Corrigé : `unaccent()` est appliqué **des deux côtés** de la comparaison, si bien
que « muller » trouve « Müller » et que « Müller » trouve « Muller ». Les deux
sens sont testés ; normaliser la requête seule aurait laissé le second sans
réponse.

L'extension est installée par `V101`. Elle n'est pas immuable, donc inutilisable
dans un index — sans conséquence ici, un `LIKE '%…%'` n'en utilisait déjà aucun.

**Votre prompt dit « comme le fait déjà la recherche floue des programmes ». Elle
ne le fait pas**, et nous préférons vous le dire, parce que c'est le genre
d'affirmation sur laquelle on bâtit ensuite. Aucune couche SQL du dépôt n'était
insensible aux accents. Ce que vous avez observé est un effet de bord de la
similarité par trigrammes : sur un mot long, la version accentuée et la version
nue partagent assez de trigrammes pour franchir le seuil de 0,3. Sur un nom
court, cela ne joue pas. La seule normalisation d'accents qui existait est en
Java, appliquée à la requête avant comparaison à une taxonomie d'activités codée
en dur — elle ne touche aucune colonne.

---

## 3. Les titres, et la position qui disparaît

`GET /users?query=` retient désormais aussi les personnes dont **un programme
public et actif** porte le mot cherché.

**Les créneaux sont couverts sans rien ajouter**, et c'est une bonne nouvelle que
vous ne pouviez pas connaître : le titre d'un créneau *est* celui de son
programme. « Basketball — vendredi 4 septembre » est fabriqué par le serveur au
moment de la publication rapide, sous la forme « activité — jour », et rangé dans
`programs.title`. Indexer les titres de programmes indexe donc les deux.

L'`EXISTS` ne retient que les programmes `ACTIVE` et publics : rendre quelqu'un
trouvable par le titre d'un programme que personne ne peut voir ferait fuiter
l'existence de ce programme — on apprendrait qu'il existe en constatant qui
remonte. Un test tient les deux sens.

**Votre demande de fond est donc satisfaite** : `GET /users?query=` ne demande
aucune position, n'en a jamais demandé, et porte maintenant les deux moitiés.
Votre seconde source devient facultative, et le bandeau « sans ta position, les
titres n'ont pas été cherchés » peut disparaître.

**`POST /search` ne change pas**, comme vous le disiez au §4. Ses `lat`/`lng`
restent obligatoires : c'est la recherche de ce qui se passe autour de soi, et sa
géométrie est juste pour cela.

---

## 4. Deux défauts que vous n'aviez pas signalés

**a. On pouvait se trouver soi-même.** Aucune exclusion de l'appelant, alors que
les autres lectures de personnes en ont une. Un onglet qui sert à trouver
quelqu'un à suivre proposait de se suivre. L'exclusion est posée **en SQL**, pas
après coup, pour que le total reste d'accord avec la page.

**b. Votre pagination était instable, et seulement chez vous.** Le classement se
fait par distance ; sans position, la clé de tri valait `0` pour toutes les
lignes, sans départage. L'ordre était alors laissé au plan d'exécution, et deux
pages successives pouvaient se recouvrir ou se manquer. Ce n'est pas un cas de
bord : c'est le cas nominal de votre onglet, qui n'envoie jamais de position.
`u.id` départage désormais. Un test demande trois pages de deux et vérifie
qu'aucune ne recoupe une autre.

**Ce que nous n'avons pas corrigé, et que vous devez savoir :** le `LIKE` porte
sur la bio même pour un profil réglé « privé », dont la bio n'est pourtant pas
rendue. On peut donc faire remonter quelqu'un par un mot qu'on ne verra jamais.
C'est antérieur à ce lot et nous ne l'avons pas touché sans vous demander —
dites-nous si vous voulez que la bio sorte de l'index pour ces profils-là.

---

## 5. Vérification

`CircleFindIntegrationTest` — **11 tests, verts**, contre le schéma réel.

Un test par défaut signalé, et surtout leurs contre-tests : un compte qui n'a
activé aucun réglage ne devient pas trouvable, un programme rendu privé cesse de
faire remonter son organisateur, et le total compte exactement ce que la page
rend. Sans eux, une recherche qui rendrait tout le monde passerait la moitié de
ces tests.

Les deux sens des accents sont tenus séparément, ainsi que le troisième réglage
de visibilité — sans quoi le défaut du §1 se rejouerait sur `show_on_map` le jour
où quelqu'un ne coche que celui-là.

**Un point à surveiller au déploiement** : `V101` fait `CREATE EXTENSION IF NOT
EXISTS unaccent`. Si le rôle de la base n'a pas le droit de créer une extension,
la migration échoue et l'application ne démarre pas. Deux choses nous rassurent
sans nous suffire : `V1` crée déjà `postgis` et `vector`, bien plus privilégiées,
et elles sont passées ; et le `IF NOT EXISTS` évite l'échec si l'extension est
déjà là. C'est le seul risque de ce lot, et il est binaire — il se voit au
premier démarrage.

---

## 6. Récapitulatif

| # | Votre point | Réponse |
|---|---|---|
| 1 | Indexer les titres des programmes et créneaux organisés | **Corrigé** — et les créneaux sont couverts par les programmes, leur titre étant le même |
| 2 | Insensibilité aux accents | **Corrigé** dans les deux sens, `V101`. Votre prémisse sur les programmes était fausse — §2 |
| 3 | Pourquoi certains comptes sont absents | **Répondu, et corrigé** : trois réglages, la recherche n'en lisait qu'un, et pas celui que l'app écrit — §1 |
| 4 | Une recherche de personnes sans position | **Satisfaite** : forme (a), aucune position requise, les deux moitiés dans une seule route |
| — | `POST /search` | **Inchangé**, comme vous le proposiez |
| — | *(non signalé)* Se trouver soi-même | Corrigé — §4.a |
| — | *(non signalé)* Pagination instable sans position | Corrigé — §4.b |
| — | *(non corrigé)* La bio indexée sur un profil privé | Signalé, en attente de votre avis — §4 |

**Ce qu'il vous reste à faire :** rien, sinon lever `FeatureFlags.circleFind` une
fois le déploiement fait, et retirer le bandeau qui annonçait que les titres
n'avaient pas été cherchés. Votre seconde source, celle qui exige une position,
n'a plus de raison d'être sur cet onglet.
