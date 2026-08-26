# Réponse — les trois routes d'écriture rendent 500

> Réponse à `PROMPT_BACKEND_ECRITURES_500_2026-08-26.md`.
>
> **Corrigé.** Votre intuition de les regarder ensemble était la bonne : les
> trois routes partageaient une seule ligne de code, répétée dans trois
> services. Votre relevé nous a menés droit dessus — la panne est bien après la
> validation, rien ne la fait varier, et le `404` manquant n'était pas une
> conséquence du bug.
>
> **Il y en avait une quatrième**, que vous n'aviez pas testée :
> `POST /api/reviews`. Même ligne, même 500. Elle est corrigée avec les autres.

---

## 1. La cause, et les traces que vous demandiez

Nous ne vous rendons pas les traces de production mais mieux : le défaut est
reproduit en test d'intégration, contre le schéma réel, et il tient en une
ligne.

Les trois services posaient eux-mêmes l'identifiant sur une entité dont l'id est
`@GeneratedValue` :

```java
Report report = Report.builder()
    .id(UUID.randomUUID())   // ← la ligne
    .reporterId(reporterId)
```

Un id déjà posé rend `save()` non-« new » pour Spring Data, qui appelle donc
`merge()` au lieu de `persist()`. Hibernate 7 — celui de Spring Boot 4.1 —
refuse de fusionner une instance détachée dont la ligne n'existe pas :

```
org.springframework.orm.ObjectOptimisticLockingFailureException:
  Row was already updated or deleted by another transaction
  for entity [org.program.pair.domain.report.Report with id '28297e8b-…']
Caused by: org.hibernate.StaleObjectStateException: …
```

Rien n'attrape cette exception, d'où le `500 INTERNAL_ERROR` générique.

Deux choses expliquent que ce soit passé inaperçu jusqu'ici. Les versions
antérieures d'Hibernate retombaient **silencieusement** sur un `INSERT` : le
code était déjà faux, il marchait quand même. Et aucun test ne couvrait ces
routes de bout en bout — les seuls tests existants mockaient le dépôt, donc la
base n'était jamais touchée.

Cela explique chacune de vos observations :

| Ce que vous avez observé | Pourquoi |
|---|---|
| La validation du corps est propre, la panne est après | Elle est à l'écriture, dernière étape du service |
| Rien ne fait varier le 500 | Ni le motif ni le type d'entité n'atteignent la ligne fautive |
| Rien n'est écrit, pas même partiellement | L'exception précède l'`INSERT` |
| Le `422` court-circuite la panne sur `/recommendations` | Le contrôle de preuve est en amont |

## 2. Le correctif, et sa vérification par les routes de lecture

La ligne est retirée des quatre routes. Un test d'intégration les couvre, et va
chaque fois **jusqu'à la route de lecture** — un `2xx` qui n'écrit rien est
exactement le symptôme à exclure, et c'est la preuve que vous demandiez :

| Route | Rend | Vérifié par |
|---|---|---|
| `POST /api/reports` | `201` | `GET /api/reports/me` contient le signalement |
| `POST /api/programs/{id}/report` | `201` | `GET /api/reports/me` contient le signalement |
| `POST /api/recommendations` | `201` | `GET /api/recommendations/given` le contient, et `can-recommend` bascule à `false` |
| `POST /api/reviews` | `201` | `GET /api/reviews/me` contient l'avis |

Le test garde vraiment : en remettant la ligne en place, les quatre méthodes
échouent sur `Status expected:<201 CREATED> but was:<500 INTERNAL_SERVER_ERROR>`
— votre symptôme, à l'identique.

## 3. Le `404` sur une cible inexistante

Il n'était pas cassé : **il n'existait pas**. `ReportService` ne cherchait
jamais l'entité signalée, quel que soit son type. Un identifiant orphelin allait
jusqu'à l'insertion.

Le contrôle est posé sur les quatre valeurs de l'énumération, avant tout le
reste :

| `reportedEntityType` | Résolu contre |
|---|---|
| `USER` | `users` |
| `PROGRAM` | `programs` |
| `MESSAGE` | `messages` |
| `REVIEW` | `reviews` |

Une cible introuvable rend maintenant `404 NOT_FOUND`, et n'écrit rien. Votre
`report_failure.dart` peut donc en tirer « cette personne n'est plus joignable »
comme prévu.

## 4. Les trois routes ensemble

Vous aviez raison, et c'est ce qui a rendu le diagnostic rapide : une cause,
trois services, quatre routes. Les deux signalements passent par le même
`ReportService` — c'est pourquoi ils tombaient exactement de la même façon.

## 5. « Déjà recommandé » : c'est un `409`

Vous demandiez de trancher. **Le `409` porte « c'est déjà fait », le `422` porte
« vous n'avez pas le droit ».** C'est votre lecture actuelle, et c'est la bonne
— c'est le serveur qui avait tort.

Les deux refus « déjà fait » passaient par un `422 BUSINESS_RULE_VIOLATION`.
Ils rendent désormais un `409` avec un code nommé :

| Cas | Avant | Maintenant |
|---|---|---|
| Signaler deux fois le même élément | `422 BUSINESS_RULE_VIOLATION` | **`409 REPORT_ALREADY_SUBMITTED`** |
| Recommander deux fois la même personne | `422 BUSINESS_RULE_VIOLATION` | **`409 RECOMMENDATION_ALREADY_GIVEN`** |
| Recommander sans preuve d'interaction | `422 BUSINESS_RULE_VIOLATION` | **inchangé** — c'est bien un refus de droit |
| Se recommander soi-même | `422 BUSINESS_RULE_VIOLATION` | **inchangé** |

> **Écart de contrat assumé, à signaler.** Un client branché sur le `422` pour
> détecter « déjà fait » cessera de le voir. Le vôtre lisait déjà le `409` dans
> ce sens : le changement le remet d'aplomb au lieu de le casser. Les deux codes
> sont traduits en français, anglais et allemand.

Le précédent est `ALREADY_SUBSCRIBED`, traité de la même façon : le client
stabilise l'affichage sur l'état voulu — « Signalé », « Recommandé » — sans
bandeau d'erreur, puisque l'état voulu est en base.

## 6. Une contrainte manquante, trouvée en chemin

L'entité `Report` déclare depuis toujours une contrainte d'unicité sur
`(reporter_id, reported_entity_type, reported_entity_id)`. Le schéma étant géré
par migrations, elle n'a **jamais** été créée en base : le refus « déjà
signalé » ne tenait qu'à une lecture préalable, que deux requêtes simultanées
franchissent toutes les deux.

La migration `V81` la pose, après avoir dédoublonné les lignes déjà écrites (le
premier signalement de chaque triplet est conservé, avec sa description
d'origine). Les deux services rattrapent la violation et la rendent en `409`,
le même que ci-dessus : la contrainte n'ouvre donc aucun nouveau chemin d'erreur
côté client, elle rend seulement le refus fiable.

---

## Ce que nous n'avons pas changé

**`description` reste requise** sur `POST /api/reports`, entre 10 et 500
caractères. Votre demande de la rendre facultative reste ouverte — nous la
traiterons séparément, elle ne se mélange pas à un correctif de panne. Votre
phrase de remplissage n'a jamais été en cause, vous l'aviez établi.

**Le corps de réponse de `POST /api/reports` reste l'entité brute.** Elle expose
les champs de modération (`status`, `reviewedBy`, `reviewedAt`,
`resolutionNotes`), vides à la création mais remplis dans `GET /api/reports/me`
une fois le signalement traité — `reviewedBy` étant l'identifiant d'un
modérateur. Nous le signalons plutôt que de le corriger ici : passer à un DTO
change le contrat de deux routes, et ce n'est pas le sujet de ce lot. À arbitrer
ensemble.

**L'auto-signalement reste accepté.** Vous l'aviez testé et il rendait `500`
comme le reste ; il rend maintenant `201`. Si vous voulez qu'il soit refusé,
dites-le — c'est une règle à décider, pas un défaut à corriger.

---

## Vérification

- Suite complète au vert : **786 tests**, aucun échec.
- `SignalementRecommandationAvisIntegrationTest` — 8 méthodes :
  - les quatre routes rendent `201` et la ligne apparaît dans la route de
    lecture correspondante ;
  - une cible inexistante rend `404` sur les quatre types, sans rien écrire ;
  - un second signalement identique rend `409 REPORT_ALREADY_SUBMITTED`, et il
    n'y a toujours qu'un signalement ;
  - une seconde recommandation rend `409 RECOMMENDATION_ALREADY_GIVEN` ;
  - une recommandation sans preuve d'interaction reste en `422`.
- Le défaut a été reproduit avant correction, et le test vérifié en remettant le
  bug en place.

## Ce qu'il vous reste à faire

Rien pour le signalement — vous l'aviez dit, et c'était juste.

Pour la recommandation, vérifiez que le `409` est bien traité comme « déjà
fait » sur les **deux** domaines : votre lot corrigeait déjà ce point côté
recommandation, le signalement le rejoint maintenant.
