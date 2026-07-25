# Demande backend — `POST /api/recommendations` : rendre `rating` et `comment` facultatifs

**Statut côté mobile : fonctionnalité désactivée.** Le geste « Recommander » a été retiré de
l'application (drapeau `FeatureFlags.peerRecommendations = false`) en attendant cette évolution.
Il sera rétabli dès que le contrat le permettra.

---

## Le problème

Le principe produit de meetDo, rappelé dans la spec d'évolution backend elle-même, est
qu'**aucun endpoint ne classe ni ne juge les utilisateurs entre eux**. Côté frontend, la
règle est déclinée en trois interdits explicites sur la feuille de recommandation :

> Aucune note, aucune étoile, aucun avis négatif possible sur une personne.
> Le geste est purement positif et facultatif.

Or le contrat actuel de `POST /api/recommendations` impose exactement ce que le produit
interdit. `CreateRecommendationRequest` :

| Champ | Type | Obligatoire | Contrainte |
|---|---|---|---|
| `recommendedId` | UUID | **oui** | — |
| `rating` | integer | **oui** | 1..5 |
| `comment` | string | **oui** | 20..500 caractères |
| `activityContext` | UUID | non | — |
| `programContext` | UUID | non | — |

Une recommandation entre pairs est un geste binaire : soit je recommande quelqu'un, soit je ne
fais rien. Il n'y a pas de « je le recommande à 3 sur 5 ». Et exiger 20 caractères de
justification transforme un tap en rédaction.

## Pourquoi on ne peut pas simplement remplir les champs

C'est la solution qu'on avait implémentée d'abord, et qu'on a retirée : `rating` figé à `5`,
et `comment` retombant sur une phrase par défaut quand l'utilisateur n'écrit rien — ce qui
est le cas de tous les utilisateurs, puisque l'interface ne propose aucun champ de texte.

Concrètement, l'app publiait sous le nom de l'utilisateur une note de 5/5 et un témoignage
(« Super partenaire, je recommande sans hésiter. ») qu'il n'avait ni écrit ni lu. C'est
inacceptable pour deux raisons :

1. **Ça met des mots dans la bouche de l'utilisateur.** Le commentaire est stocké, attribué
   et probablement affiché sur le profil de la personne recommandée. Un texte que
   l'utilisateur n'a jamais vu ne devrait pas porter sa signature.
2. **Ça pollue les données.** Des milliers de recommandations à 5/5 avec un commentaire
   identique rendent les deux champs statistiquement inutiles, tout en donnant l'illusion
   d'une donnée qualitative.

## Ce qu'on demande

**Rendre `rating` et `comment` facultatifs** sur `POST /api/recommendations`.

```jsonc
// Suffisant pour créer une recommandation :
{ "recommendedId": "uuid" }

// Et, si l'appelant a effectivement quelque chose à dire :
{ "recommendedId": "uuid", "comment": "On a couru ensemble, super rythme." }
```

Précisions :

- **`rating` absent** → ne pas stocker de note, plutôt que d'appliquer une valeur par défaut.
  Une recommandation existe ou n'existe pas ; elle n'a pas d'intensité. Si la colonne est
  `NOT NULL` en base, une migration la rendant nullable est préférable à un défaut à 5, qui
  recréerait le biais qu'on cherche à éviter.
- **`comment` absent ou vide** → accepter. Si la valeur est fournie, garder la borne haute de
  500 caractères, mais **supprimer le minimum de 20** : « Super partenaire » fait 16
  caractères et est un commentaire parfaitement légitime.
- **Pas de nouveau endpoint** : on préfère un assouplissement du contrat existant à une route
  parallèle, pour ne pas avoir deux chemins d'écriture à maintenir.

## Point secondaire — la preuve d'interaction

La documentation de l'endpoint indique encore « Nécessite une conversation existante ». La
spec d'évolution backend annonce pourtant que la **double confirmation de présence**
(`SHARED_ATTENDANCE`) est désormais une preuve valide alternative.

Merci de confirmer que `POST /api/recommendations` accepte bien ce cas, car c'est précisément
le scénario de l'app : la feuille de recommandation s'ouvre après un « Oui, j'y étais » et ne
propose que des personnes ayant **elles aussi** confirmé leur présence sur le même créneau.
Ces deux personnes peuvent parfaitement n'avoir jamais échangé de message.

Si ce n'est pas encore le cas, l'endpoint renverra `403` sur la quasi-totalité des
recommandations issues de ce parcours.

## Ce que ça débloque côté mobile

Une fois les deux champs facultatifs :

1. `FeatureFlags.peerRecommendations` repasse à `true` ;
2. le repli sur `defaultComment` est supprimé de `RecommendationRepository` ;
3. la feuille retrouve son geste d'un seul tap, sans note, sans texte imposé, conforme au
   principe produit.

Aucun autre changement n'est nécessaire de notre côté : le parcours, l'écran et l'appel
existent déjà et sont testés.

---

## Contexte technique (référence)

- Fichiers concernés côté mobile : `lib/features/recommendations/data/recommendation_repository.dart`,
  `lib/features/attendance/presentation/widgets/recommendation_sheet.dart`,
  `lib/core/config/feature_flags.dart`.
- Contrat inspecté sur `https://pairbackend-production-35fe.up.railway.app/v3/api-docs`
  (schéma `CreateRecommendationRequest`).
- Décision associée : la note globale d'auteur affichée sur le profil public (moyenne
  pondérée des avis de ses programmes, rendue en étoiles) a également été retirée de l'app.
  Les avis restent attachés aux **programmes**, qui sont des contenus — pas à leur auteur.
  Aucun endpoint backend n'est concerné, cet agrégat était calculé côté client.
