# Ce que le serveur doit livrer pour que meetDo passe l'App Store

**Date** : 2026-08-27
**Demandeur** : chantier mobile Flutter (`pair_mobile`)
**Contexte** : la soumission de la version 1.0.0 (build 5) est montée et prête —
`appstore/` dans le dépôt mobile. Trois choses l'empêchent de partir ; deux sont ici.

---

## En une page

| # | Demande | Pour qui | Ce que ça bloque | État au 27/08 |
|---|---|---|---|---|
| 1 | Finir la réparation des écritures de signalement | backend Spring | **plus rien** — voir ci-dessous | ¾ fait, mesuré |
| 2 | Publier la politique de confidentialité à une URL publique | site vitrine `meetdo.fun` | **l'envoi du formulaire** | à faire |
| 3 | Publier une page d'assistance | site vitrine `meetdo.fun` | **l'envoi du formulaire** | à faire |
| 4 | `lien.meetdo.fun` : DNS + fichier d'association | infra | rien — dégradé seulement | à faire |

**La bonne nouvelle d'abord** : le blocage qui faisait le plus peur — le signalement en
`500`, contraire à la règle App Store 1.2 — **est levé**. Mesuré ce matin contre la
production. Ce qui reste de la demande 1 est du sérieux ménage, pas un barrage.

---

## Demande 1 — le signalement : ce qui est réparé, ce qui reste

La demande du 26/08 (`PROMPT_BACKEND_ECRITURES_500_2026-08-26.md`) décrivait trois routes
d'écriture toutes en `500`. **Deux d'entre elles répondent correctement aujourd'hui.**
Relevé le **2026-08-27 à 12:28 UTC** contre `pairbackend-production-35fe.up.railway.app`,
compte `lena.mueller@web.de`.

### Ce qui marche maintenant

```bash
# Chemin nominal — c'est celui qu'Apple teste.
curl -X POST "$API/reports" -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' --data-binary '{
    "reportedEntityType":"USER","reportedEntityId":"<uuid>",
    "reason":"OTHER","description":"…"}'
```
```
HTTP 201
{"id":"063a0eb3-…","reporterId":"…","status":"PENDING","createdAt":"2026-08-27T12:28:13Z", …}
```

| appel | réponse | verdict |
|---|---|---|
| `POST /api/reports` — corps valide, cible existante | **201** + l'objet créé | ✅ réparé |
| `POST /api/reports` — cible bien formée mais inexistante | **404 `NOT_FOUND`** | ✅ réparé *(c'était `500` la veille)* |
| `POST /api/reports` — `description` de 5 caractères | 400 `VALIDATION_ERROR` | ✅ inchangé |
| `POST /api/programs/{id}/report` — programme inexistant | **404 `NOT_FOUND`** | ✅ réparé |

La résolution de l'entité fonctionne donc dans les deux routes : le code qui cassait *avant*
d'avoir cherché la cible ne casse plus.

### Ce qui reste — cinq points

**1.1 — `GET /api/reports/me` rend `500`.** C'est le dernier `500` de la famille.

```bash
curl "$API/reports/me" -H "Authorization: Bearer $T"
```
```json
{"code":"INTERNAL_ERROR","message":"Une erreur est survenue.","timestamp":"2026-08-27T12:28:12Z"}
```

L'app appelle cette route (`ReportedUsersNotifier.syncFromServer`) pour savoir qui
l'utilisateur a **déjà** signalé, et griser le geste. L'échec est avalé volontairement — rien
ne s'affiche, rien ne casse — donc **ce n'est pas bloquant pour l'App Store**. La seule
conséquence est qu'un doublon reste possible, et le serveur le refuse en `409`. Mais une
route de lecture qui rend `500` sur un compte qui possède exactement un signalement mérite
d'être regardée : il y a de bonnes chances que ce soit le **même** défaut de sérialisation
que celui qui faisait tomber les écritures.

**1.2 — Le code de succès de `POST /api/reports` contredit le contrat.** L'OpenAPI
(`/v3/api-docs`) annonce `200` ; le serveur rend `201`. L'app accepte les deux (tout `2xx`
est un succès pour Dio), donc rien ne casse — mais l'un des deux a tort. `201` est le bon
choix pour une création : c'est plutôt la spec qu'il faut corriger.
Pour mémoire, `POST /api/programs/{programId}/report` annonce déjà `201`.

**1.3 — L'auto-signalement est accepté.** Signaler son propre compte crée un vrai
signalement, en `PENDING`, qui occupera la file de modération :

```
reporterId       = 00000000-0000-0000-0000-000000000002
reportedEntityId = 00000000-0000-0000-0000-000000000002   ← le même
```

Un `422 BUSINESS_RULE_VIOLATION` serait plus juste. À arbitrer côté produit : ce n'est ni
un risque de sécurité ni un blocage, juste du bruit pour la modération.

**1.4 — `POST /api/programs/{programId}/report` n'a pas été confirmé en écriture nominale.**
On sait qu'il résout correctement une cible inexistante ; on n'a pas voulu créer un
signalement contre un vrai programme de démonstration pour le prouver. **Une écriture de
votre côté suffira** — ou dites-nous quel programme jetable viser et nous la ferons.

**1.5 — `POST /api/recommendations` n'a pas pu être revérifié.** La seule cible évidente,
Seyd Njoya, est déjà recommandée par Lena dans le jeu de démonstration :
`GET /api/recommendations/can-recommend/{id}` rend `false`, et `GET /api/recommendations/given`
contient déjà `d1000000-…-0001`. Il nous faudrait une paire d'utilisateurs qui ont une
présence confirmée partagée **sans** recommandation existante. Si vous en avez une sous la
main, ou si vous pouvez rejouer le `POST` vous-mêmes, cette ligne se ferme en deux minutes.

### À nettoyer chez vous

Le signalement `063a0eb3-1839-42b9-bf91-90e6dd784454` a été créé **par nous**, ce matin, pour
prouver que le chemin d'écriture fonctionne. C'est un auto-signalement du compte de
démonstration Lena Müller, description « Verification technique avant soumission App Store ».
**À purger** — il n'a aucune valeur de modération.

### Critères d'acceptation

Ce que nous rejouerons pour clore la demande :

| appel | attendu |
|---|---|
| `GET /api/reports/me` avec au moins un signalement | `200` + la page, pas `500` |
| `POST /api/programs/{id}/report` sur un programme réel | `201` + l'objet |
| `POST /api/recommendations` sur une cible éligible | `200`/`201`, puis `can-recommend` passe à `false` |
| `POST /api/reports` en doublon | `409`, pas `500` |

---

## Demande 2 — la politique de confidentialité, à une URL publique

**App Store Connect refuse l'envoi du formulaire sans elle.** Ce n'est pas une
recommandation : le champ est obligatoire et validé.

Le texte **existe déjà** — c'est celui que l'app affiche dans Réglages › Politique de
confidentialité, dix-sept sections rédigées à partir du fonctionnement réel de meetDo, avec
les bases légales du RGPD article par article, en trois langues. Il n'y a rien à écrire.
Il y a à **publier**.

### Ce que la page doit respecter

| Exigence | Pourquoi |
|---|---|
| `https://`, accessible **sans authentification** | l'examinateur ouvre l'URL sans compte |
| Pas de mur de cookies bloquant, pas de redirection vers une page de connexion | même raison |
| Une URL **stable** — elle reste dans la fiche après publication | un lien mort est un motif de retrait |
| Atteignable depuis n'importe quel pays | l'examen se fait souvent depuis les États-Unis |
| Le texte **identique** à celui de l'app | deux versions divergentes d'un document contractuel, c'est un risque juridique, pas un détail de forme |

### Ce que nous fournissons

Pour garantir le dernier point, l'app exportera elle-même ses documents en HTML statique :
un script Dart qui parcourt `lib/core/legal/` et écrit un fichier par langue. Vous recevrez
donc des pages prêtes à déposer, régénérables à chaque modification du texte. **Rien à
recopier à la main** — c'est précisément ce qu'il faut éviter sur un document versionné.

### Le préalable, et il n'est pas chez vous

Les documents contiennent aujourd'hui **huit marqueurs non remplis**, tous entre crochets,
rassemblés dans `lib/core/legal/legal_placeholders.dart` :

```
[Nom de l'éditeur, forme juridique]      [Rue, code postal, ville, pays]
[contact@example.com]                    [Registre du commerce, numéro, n° de TVA]
[représentant légal]                     [délégué à la protection des données, coordonnées]
[autorité de contrôle compétente]        [juridiction compétente]
```

Ce sont exactement les mentions que les articles 13(1)(a) et (b) du RGPD rendent
**obligatoires**. Tant qu'elles manquent, le document n'est pas publiable — et il ne l'est
pas non plus *dans l'app*, où il s'affiche avec ses crochets. Ces informations ne peuvent
venir que de l'éditeur de meetDo. **C'est le vrai chemin critique de cette demande.**

### URL suggérées

| Langue | URL | Champ App Store Connect |
|---|---|---|
| fr | `https://meetdo.fun/confidentialite` | localisation fr-FR |
| en | `https://meetdo.fun/privacy` | localisation en-US |
| de | `https://meetdo.fun/datenschutz` | localisation de-DE |

Une seule suffit pour envoyer ; les trois valent mieux, l'app étant trilingue.

---

## Demande 3 — une page d'assistance

Champ **obligatoire** lui aussi (« URL d'assistance »). Elle n'a pas à être élaborée. Le
minimum qui satisfait Apple et sert vraiment :

- ce qu'est meetDo, en trois lignes ;
- **une adresse de contact que quelqu'un relève** — la même que le contact RGPD des
  documents juridiques, ce qui règle deux problèmes d'un coup ;
- un lien vers la politique de confidentialité et vers les CGU ;
- une poignée de réponses aux questions prévisibles : comment supprimer son compte, comment
  signaler quelqu'un, comment couper les notifications.

URL suggérée : `https://meetdo.fun/aide`.

⚠️ L'adresse de contact doit être **réelle**. Apple écrit parfois dessus, et une adresse qui
rebondit se retourne contre la fiche.

---

## Demande 4 — `lien.meetdo.fun` *(non bloquant)*

Rappel, parce que le déploiement des pages ci-dessus est le bon moment pour le faire.
Le sous-domaine des liens de partage n'existe toujours pas : DNS absent, donc
`/.well-known/apple-app-site-association` n'est servi par personne.

**Conséquence aujourd'hui** : un lien de créneau partagé ouvre Safari sur une page
inexistante au lieu de rouvrir l'app. L'entitlement iOS déclare déjà le domaine
(`Runner.entitlements`), l'app émet déjà les `https://lien.meetdo.fun/s/…`. Il ne manque
que l'hébergement.

Quand ce sera servi : `Content-Type: application/json`, **sans redirection** et **sans
extension** `.json` dans l'URL — iOS n'accepte pas autre chose, et ne réessaie qu'au
lancement suivant de l'app.

---

## Ce que nous faisons pendant ce temps

Côté app, un seul chantier bloque encore, et il est chez nous : les tuiles « Continuer avec
Google / Apple » du premier écran annoncent une fonctionnalité qui n'existe pas, ce qui est
le motif de refus n° 1 d'Apple (règle 2.1). Elles passent derrière un drapeau. Rien à faire
de votre côté — mais **quand OAuth existera côté serveur, dites-le nous** : c'est ce drapeau
qui se rallumera, et rien d'autre à déployer.
