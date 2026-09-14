# Fermer son compte doit annuler ses créneaux à venir et prévenir les inscrits

**Date :** 2026-09-14
**Module :** `rgpd`
**Audit :** P-BL-18 (désactivation sans suite pour créneaux et inscrits), lien P-BL-19
**Fait suite à :** `creneau-modifiable/REPONSE_BACKEND_2026-09-14-BIS.md`, §2
**Décision de l'utilisateur, 14/09/2026 :** à la fermeture d'un compte, ses créneaux à venir sont
**annulés**, et leurs inscrits reçoivent la notification d'annulation habituelle.

> **Pourquoi maintenant.** Depuis le correctif de l'incident `/slots/mine` (14/09), le créneau d'un
> organisateur au compte fermé **disparaît** de « mes créneaux », du fil, de la carte, et sa fiche
> rend `404`. C'est cohérent, mais un inscrit voit désormais sa séance **s'évaporer sans un mot** :
> il peut se déplacer pour rien. Vous l'avez écrit vous-mêmes : « au premier utilisateur réel qui
> ferme son compte en ayant des inscrits ».

---

## 1. Constat (vos réponses du 12/09 et du 14/09)

- `deactivateAccount` et la demande RGPD posent `is_active = false`, **sans effet sur les créneaux
  ni sur les inscrits** (`rgpd/REPONSE_BACKEND_2026-09-12.md`, §3.2 et « ce qui ne change pas »).
- P-BL-18 est au Lot 2 de votre plan, marqué bloqué sur l'« ordre des effets » (D2).
- L'app affiche aujourd'hui, dans le parcours de suppression : « Les créneaux que tu as publiés ne
  sont pas annulés pour autant : annule-les avant si des gens y sont inscrits. » C'est honnête, mais
  c'est un palliatif.

## 2. La demande

1. **À la fermeture du compte** (`deactivateAccount` **et** la demande RGPD, même chemin) :
   annuler chaque créneau **à venir ou en cours** dont la personne est l'organisatrice, par **le
   même chemin que l'annulation par l'hôte** (`status: CANCELLED`, notification `SLOT_CANCELLED`
   aux inscrits et à la liste d'attente) — un seul chemin, pas un second qui divergerait (P-BL-19).
2. **Ne pas nommer la raison** aux inscrits : ni « compte supprimé », ni le motif. Le texte
   d'annulation habituel suffit ; la fermeture d'un compte ne regarde que son titulaire.
3. **Ses propres inscriptions** aux créneaux des autres : les retirer (la place se libère, la
   liste d'attente avance), sans prévenir l'organisateur d'autre chose qu'un départ ordinaire.
4. **Les programmes** dont la personne est l'autrice : dites-nous ce que vous retenez (archivés, ou
   leurs séances futures annulées par le point 1 suffisent).
5. **Ordre des effets (D2)** : annuler et notifier **avant** de poser `is_active = false`, dans la
   même transaction ou avec reprise, pour qu'une panne à mi-chemin ne laisse pas des créneaux
   ouverts d'un compte fermé. Si la réversibilité retenue par le juridique permet de rouvrir le
   compte, les créneaux annulés **ne se rouvrent pas**.
6. Un test d'intégration : un organisateur avec deux inscrits et une personne en liste d'attente
   ferme son compte → le créneau passe `CANCELLED`, trois `SLOT_CANCELLED` émis, rien d'autre.

## 3. Côté app, ensuite

Quand ce sera déployé et relevé, l'app retirera la phrase « annule-les avant » du parcours de
suppression (`deleteAccountBody`, trois langues) et dira à la place que les créneaux publiés sont
annulés et leurs inscrits prévenus. Rien d'autre ne change : une annulation reçue se traite déjà.

## 4. Comment nous vérifierons

Votre test d'intégration, puis sur un compte de test **remis en état** (jamais un compte réel) :
créer un créneau, y inscrire le second compte de test, fermer le premier compte → le second reçoit
`SLOT_CANCELLED` et voit le créneau annulé.
