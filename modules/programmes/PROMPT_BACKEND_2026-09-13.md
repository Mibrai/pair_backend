# « Frais à prévoir » — un booléen et une précision, jamais un prix

**Date :** 2026-09-13
**Module :** `programmes`
**Audit :** P-MU-16 (`audit/PLAN_MOBILE_UX_DESIGN_2026-09-11.md`), décision D7 en option C

> **Ce que l'app a déjà fait, sans vous** (commit `8cdefc1`) : l'interrupteur « Des
> frais sont à prévoir » et sa précision dans les formulaires de programme, la puce
> « Frais à prévoir » sur la carte de résultat, la fiche programme et la fiche
> créneau. Le tout derrière un drapeau éteint, `programCostToShare`.
>
> **Ce qui vous appartient** : porter ces deux champs. Sans eux, rien ne s'affiche.

---

## 1. Le constat, relevé le 13/09/2026

Aucun champ de coût n'existe côté serveur : ni `price`, ni `cost`, ni `fee` dans un
schéma de `/v3/api-docs`, ni dans `src/main` (Java et migrations). L'app lisait un
`price` qui n'était jamais servi ; il est retiré.

Un participant découvre donc sur place qu'il faut payer le terrain ou la salle.

## 2. Pourquoi pas un prix

La doctrine refuse les classements. Un montant exact devient vite un tri par prix,
et un « 0 € » par défaut une promesse. D'où la forme retenue : **un booléen** que
l'organisateur coche lui-même, et **une précision libre**. L'app n'affiche jamais
« Gratuit » par déduction — un test l'interdit.

## 3. La demande

1. **Deux colonnes sur le programme** : `cost_to_share boolean not null default
   false`, `cost_note varchar(80) null`.
2. **Acceptées** à la création et à la modification du programme (`POST`,
   `PUT` et `PATCH /api/programs/{programId}`), clés JSON `costToShare` et
   `costNote`. `costNote` est ignorée — ou remise à `null` — quand `costToShare`
   vaut `false`.
3. **Rendues** par `ProgramDto` (les deux), `SearchResultDto` et `SlotFeedItemDto`
   (au moins `costToShare`).
4. **Pas de tri ni de filtre** sur ces champs.

## 4. Comment nous vérifierons

Relevé de `/v3/api-docs` **et** création réelle avec le compte de test : un
programme « Tennis » avec frais « Location du terrain », relu par la fiche, par
`/search` et par `/slots/feed` ; un programme sans frais qui ne rend ni l'un ni
l'autre. Puis bascule dans un commit séparé ; le drapeau reste l'interrupteur si le
serveur régresse.
