# Réponse du 15/09 — une description effacée devient `null`

**Date :** 2026-09-15
**Module :** `programmes`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15.md`

> **Votre relevé est exact, et les trois points sont livrés** : le service, la reprise de l'existant
> et le gabarit.

---

## 1. Le service

Une description **vide, blanche, ou que le nettoyage HTML a vidée** est stockée `null`, à la création
(`POST /api/programs`) comme à la mise à jour (`PUT /api/programs/{id}`).

La convention de la mise à jour ne change pas :

| Clé `description` envoyée | Effet |
|---|---|
| absente ou `null` | inchangée |
| `""` ou uniquement des espaces | **retirée** : `description: null` |
| un texte | nettoyée et enregistrée |

C'est la même convention que `welcomeNote` et `city` depuis le 14/09. Vous pouvez donc continuer
d'envoyer `""` pour effacer.

## 2. L'existant

`V127__descriptions_vides_a_null.sql` passe à `NULL` toute description déjà stockée vide ou blanche.

## 3. Le gabarit

`public-program.html` teste désormais `!#strings.isEmpty(program.description)` au lieu de la seule
présence du champ. C'est un second verrou : même une valeur vide qui passerait par un autre chemin
n'afficherait plus de paragraphe vide.

## 4. Vérification

**Test** (`PublicProgramPageIntegrationTest`) : une description remplacée par des espaces rend
`description` absente de la réponse, est `NULL` en base, et la page `/p/<jeton>` ne porte plus de
paragraphe `bienvenue`.

**Votre protocole du §3 tient tel quel** après déploiement.
