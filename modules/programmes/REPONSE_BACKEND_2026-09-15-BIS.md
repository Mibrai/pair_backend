# Réponse du 15/09 (BIS) — « Frais à prévoir » sur la page web publique d'un programme

**Date :** 2026-09-15
**Module :** `programmes`
**Fait suite à :** `PROMPT_BACKEND_2026-09-15-BIS.md`
**Audit :** P-MU-16

> **Livré tel que demandé.** La page `/p/<jeton>` affiche « Frais à prévoir » quand `costToShare` est
> vrai, avec la précision en dessous. Rien sinon, et **jamais « gratuit »**.

---

## 1. La page

Dans `public-program.html`, sous les faits (quand, où, participants), un encart :

- **« Frais à prévoir »** quand `costToShare` est vrai ;
- **`costNote`** en dessous s'il n'est pas vide, échappé comme tout texte libre ;
- **rien** quand `costToShare` est faux.

Aucune formulation ne suggère la gratuité : faux veut dire que rien n'a été annoncé.

## 2. Les textes

`public.program.costToShare`, langue de la page selon `Accept-Language`, comme le reste de la page :

| fr | en | de |
|---|---|---|
| Frais à prévoir | Costs to share | Kosten fallen an |

## 3. Le JSON public

`PublicProgramView`, servi par `GET /public/programs/{token}`, porte aussi `costToShare` et `costNote`,
avec la même règle : `costNote` est `null` dès que `costToShare` est faux. Jamais un montant.

## 4. Vérification

**Test** (`PublicProgramPageIntegrationTest.lesFrais_doiventFigurerSurLaPage_etJamaisGratuit`) :

- programme avec frais : « Frais à prévoir » et la précision sur la page, la précision échappée (une
  balise saisie ne sort pas en HTML), « Costs to share » en anglais ;
- programme sans frais : ni la puce ni l'encart ;
- dans les deux cas, le mot « gratuit » n'apparaît nulle part dans la page.

**Votre protocole du §2** : `GET /p/<jeton>` d'un programme de test avec et sans frais, après
déploiement.
