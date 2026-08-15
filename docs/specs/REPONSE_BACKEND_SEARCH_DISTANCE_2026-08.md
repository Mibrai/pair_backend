# Réponse backend — `/search` situe désormais un programme à sa séance

> Réponse à `PROMPT_BACKEND_SEARCH_DISTANCE_2026-08.md`. **Corrigé**, et sur un
> périmètre plus large que celui que vous décriviez.
>
> Votre rapport est le plus facile à instruire que nous ayons reçu : vous aviez
> fermé chaque échappatoire avant de nous écrire. Nous n'avons eu qu'à ouvrir le
> code à l'endroit que vous désigniez. Trois choses en sont ressorties que vous
> ne pouviez pas voir, dont une qui change ce que vous devez retester.

---

## Ce que vous décriviez

Exact, sur les deux chemins de construction des résultats `program` :

- le chemin sémantique lisait `owner.getLocation()` pour la coordonnée **et**
  pour la distance ;
- le chemin full-text/taxonomie sélectionnait `ST_Y(u.location), ST_X(u.location)`,
  `u` étant la table `users` jointe via `user_activities`.

C'est bien la position du compte, jamais celle du créneau. Et la branche `slot`,
que vous donniez en exemple, lisait déjà `schedule.getLocation()` : le bon
patron était effectivement juste à côté.

## Ce que vous ne pouviez pas voir

Vous avez reproduit avec `radiusMeters: 2000000`. À ce rayon tout passe, donc
seul l'affichage paraissait faux. **Mais la position de l'organisateur ne servait
pas qu'à afficher : elle servait aussi à sélectionner, filtrer et classer.** Les
deux chemins bornaient leur recherche par un `ST_DWithin` sur `u.location`, et
triaient sur la distance qui en découlait.

Au rayon par défaut de **5 km**, cela donnait :

1. un programme dont les séances se tiennent à 2 km de l'utilisateur, mais dont
   l'organisateur habite ailleurs, **n'apparaissait pas du tout** ;
2. un programme dont l'organisateur n'avait aucune position n'était **jamais**
   rendu — le chemin full-text portait un `AND u.location IS NOT NULL` ;
3. le classement par distance ordonnait sur l'éloignement de l'organisateur.

Le point 1 est le plus sérieux, et c'est pourquoi nous avons élargi le
correctif : **une distance fausse se voit, une absence non.** Votre utilisateur
de Herne voyait ses quatre programmes à 448 km ; il ne voyait pas ceux qui se
tiennent près de lui et dont l'organisateur habite Munich. Corriger la seule
coordonnée affichée aurait réparé ce que vous aviez remarqué en laissant intact
ce que vous ne pouviez pas remarquer.

## Ce qui est livré

Un résultat `program` est désormais situé à **la séance localisée la plus proche
du point interrogé** — celle des deux options que vous préfériez, et celle qui
répond à « à quelle distance de moi ». La coordonnée, la distance, le filtre de
rayon et le tri en découlent tous.

Le filtre suit un principe simple, qu'il vaut la peine d'énoncer parce qu'il
décide de tous les cas limites : **un rayon ne peut exclure que ce qu'on sait
situer.**

| Cas | `lat`/`lng`/`distanceMeters` | Filtré par le rayon ? |
| --- | --- | --- |
| Séances localisées | celles de la plus proche | oui, sur la séance |
| Plusieurs lieux | le plus proche du point interrogé | oui, entre dès qu'un lieu y est |
| Aucune séance localisée | **`null`** | non — rendu, classé en dernier |
| `locationType` `REMOTE` / `ONLINE` | **`null`** | non — rendu, classé en dernier |

Les deux cas `null` sont ceux que vous demandiez, et votre argument a emporté la
décision : « nous savons ne rien afficher, nous ne savons pas deviner qu'un
chiffre est faux ». Nous ajoutons que c'est aussi ce repli silencieux qui rendait
le défaut indétectable de notre côté — un programme mal situé était
indiscernable d'un programme bien situé.

Notez qu'ils restent **rendus**, pas exclus : les exclure reviendrait à les
filtrer sur un critère qu'on est incapable d'évaluer pour eux. `distanceMeters`
est nul exactement quand `lat`/`lng` le sont.

## Une méprise à corriger de votre côté

Votre §6 demande `null` pour un « programme en ligne (`isOnline: true`) ».
**`isOnline` ne veut pas dire ça.** Il vaut « l'organisateur a été actif dans les
cinq dernières minutes » — une présence, pas une modalité. Il est même figé à
`false` sur les résultats `slot`, donc vous ne pouvez pas vous y fier pour cela
non plus.

La notion que vous visez existe déjà dans la réponse : **`locationType`**, avec
les valeurs `REMOTE`, `ONLINE`, `IN_PERSON`, `HYBRID`. C'est sur elle que le
correctif s'appuie, et c'est elle que votre affichage doit lire. Les deux champs
sont désormais documentés dans l'OpenAPI pour que la confusion ne se reproduise
pas — mais si un écran chez vous lit `isOnline` comme « à distance », il se
trompe aujourd'hui, indépendamment de ce correctif.

## Votre §7 est une fausse alerte

Le `52.52 / 13.405` n'est pas une valeur par défaut posée à l'inscription :
aucune migration ne définit de `DEFAULT` sur `users.location`, et aucun code
d'inscription n'écrit de position. La valeur vient de la migration de jeu de
démonstration allemand, qui seede le compte `seyd.njoya@icloud.com` avec le
centre de Berlin. **Votre compte de test est un compte seedé** — les comptes
réels ne sont pas concernés, et il n'y a rien à corriger.

Bonne intuition néanmoins : la coordonnée avait l'air trop ronde pour être vraie,
et elle l'était.

## Ce que vous devez retester

Le changement de sélection est la partie qui peut vous surprendre :

- **Plus de résultats qu'avant** à rayon égal, puisqu'un programme entre
  désormais dès qu'une de ses séances est dans le rayon. C'est le comportement
  correct, mais vos captures de non-régression bougeront.
- **Des résultats sans coordonnées**, ce qui n'arrivait jamais avant sur un
  `program`. Vérifiez que vos cartes et vos listes n'assument pas `lat != null` —
  c'est le seul endroit où ce correctif peut vous casser quelque chose.
- **Un ordre différent**, le tri par distance portant maintenant sur les séances.

## Vérification

`SearchProgramDistanceIntegrationTest`, sept tests, avec **vos coordonnées** —
Herne, Berlin, Gelsenkirchen — plutôt que des nombres ronds : quand un test
échouera, l'écart se lira directement comme le symptôme que vous avez rapporté.
Nous avons au passage retrouvé vos 448 km au mètre près (448 484 m), ce qui
confirme que nous reproduisions bien votre cas.

| Test | Ce qu'il verrouille |
| --- | --- |
| `leProgramme_doitPorterLeLieuDeSaSeance_pasCeluiDeSonOrganisateur` | votre demande, littéralement : Gelsenkirchen et 4,2 km |
| `programmeVoisin_doitEtreTrouve_memeSiLOrganisateurEstLoin` | le défaut invisible — rayon de 10 km, séance dedans, organisateur à 448 km |
| `programmeLointain_doitResterExclu_dUnRayonSerre` | le pendant : supprimer tout filtre géographique ne passerait pas |
| `programmeAPlusieursLieux_doitEtreSitueAuPlusProche` | le choix de la séance, explicite |
| `programmeSansSeanceLocalisee_doitEtreRenduSansCoordonnees` | votre premier cas limite |
| `programmeADistance_neDoitPorterNiLieuNiDistance` | votre second, sur `locationType` |
| `laRequeteDeLieu_doitRendreUneSeuleLigneParProgramme_laPlusProche` | la requête native, éprouvée contre une vraie base |

S'y ajoutent huit tests unitaires sur le mapping. L'ensemble des suites de
recherche — 43 tests — passe : pagination, multilingue, créneaux, vignettes.

Une transparence sur la méthode : nous n'avons pas exécuté ces tests contre
l'ancien code pour les voir échouer. Ils discriminent par construction — un
organisateur à Berlin et une séance à Gelsenkirchen séparent 448 484 m de
4 210 m, et l'assertion tolère 50 m.

## Un risque que ce correctif a mis en lumière

Rien de nouveau n'a été découvert dans le code livré — mais la façon dont nous
avons trouvé nos propres erreurs mérite d'être partagée, parce qu'elle vous
concerne.

Une version intermédiaire de ce correctif contenait une `NullPointerException`
sur un `locationType` nul. Elle n'a jamais quitté notre poste : les tests l'ont
arrêtée. Ce qu'elle a montré, en revanche, est réel — les trois requêtes de
recherche full-text sont enveloppées dans un `catch (Exception)` qui **renvoie
une liste vide**. Notre bug ne s'est donc pas manifesté comme une erreur, mais
comme « aucun résultat », sur toutes les recherches à la fois. Il nous a fallu
lire les journaux serveur pour comprendre que la recherche était en panne et non
la base vide.

C'est la forme exacte du défaut que nous avons retiré de `/map/activities` le
mois dernier, et vous en aviez fait les frais là aussi. Ce `catch` est toujours
en place ; il est sur notre liste, et nous vous dirons quand il sautera. D'ici
là : si vous observez une recherche obstinément vide sur une requête qui devrait
rendre quelque chose, **dites-le-nous plutôt que de conclure à une absence de
données**. Ni vous ni nous ne pouvons distinguer les deux aujourd'hui, et c'est
précisément ce qui rend ce genre de panne long à voir.
