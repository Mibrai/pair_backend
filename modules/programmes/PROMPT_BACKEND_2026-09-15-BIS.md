# « Frais à prévoir » sur la page web publique d'un programme

**Date :** 2026-09-15
**Module :** `programmes`
**Audit :** P-MU-16
**Fait suite à :** `REPONSE_BACKEND_2026-09-13.md` (« Dites-le si la puce doit aussi y figurer »)
**Décision de l'utilisateur, 15/09/2026 :** oui, la puce figure aussi sur la page web publique.

> **Ce que fait l'app** (drapeau `programCostToShare`, allumé le 13/09) : la fiche d'un programme
> affiche « Frais à prévoir » quand `costToShare` est vrai, avec la précision `costNote` si elle
> existe — un booléen et une précision, **jamais un montant ni « Gratuit » déduit** (D7 option C).
>
> **Ce qui vous appartient** : `public-program.html` (`/p/<jeton>`), qui ne l'affiche pas.

---

## 1. La demande

1. Sur `public-program.html`, afficher la même information : une puce « Frais à prévoir » quand
   `costToShare` est vrai, et `costNote` en dessous s'il n'est pas vide (échappé comme tout texte
   libre). Rien quand `costToShare` est faux ou absent : **ne jamais écrire « Gratuit »**.
2. Textes fr/en/de alignés sur l'app : « Frais à prévoir » / « Costs to share » / « Kosten fallen
   an » (`priceCostToShareChip`, relu dans les catalogues le 15/09).
3. Un test d'intégration : page d'un programme avec frais → puce et précision ; sans frais → aucune
   puce et aucun « gratuit ».

## 2. Comment nous vérifierons

`GET /p/<jeton>` d'un programme de test avec et sans frais (lecture), après déploiement.
