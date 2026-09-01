# Réponse au QUATER — deux champs portés, deux décisions actées

**Date :** 2026-09-01
**Fait suite à :** `PROMPT_BACKEND_2026-09-01-QUATER.md`

> **Vos deux demandes sont faites.** `alertDelivery` est désormais sur la liste
> active (§1), et `GET /api/schedules/{id}/pending-arrivals` existe (§2).
>
> **Votre §0 est noté, et il nous vise autant que vous** : un contrat bien rédigé
> n'empêche pas un parseur de chercher au mauvais niveau. Nous avons ajouté le
> champ à plat sur la liste active précisément pour que le bandeau n'ait rien à
> déballer.
>
> **§4 est consigné de notre côté aussi** : le `collapse-id` remplace les trois
> relances, à dessein, et personne ne « corrigera » ce choix.

---

## 1. §1 — `alertDelivery` est sur la liste active

C'était le point le plus utile de votre document, et vous aviez raison sur toute
la ligne : le champ ne servait à rien là où il était.

`GET /api/watches/active` rend maintenant, sur chaque veille, le champ
`alertDelivery` — les mêmes valeurs que sur le détail : `NONE`, `PENDING`,
`SENT`, `DELIVERED`, `BOUNCED`, `FAILED`. Votre bandeau peut donc lire `BOUNCED`
sans jamais relire `GET /watches/{id}` — et donc sans risquer de révéler
`ESCALATED` à quelqu'un qui vient de taper un code de contrainte. Le champ vient
avec la liste, comme vous l'aviez demandé.

Il reste aussi sur `WatchDetailDto`, au même endroit qu'avant : votre correctif
du §0 n'a rien à défaire.

## 2. §2 — la liste des arrivées attendues, côté organisateur

```
GET /api/schedules/{scheduleId}/pending-arrivals
  → [{ watchId, name, since }]
  réservé à l'organisateur du créneau ; 404 (jamais 403) sinon
```

- `name` : le prénom réduit de l'inscrit. `since` : le début de la séance,
  l'heure à laquelle on l'attendait.
- **Trois champs, et le type est fermé** — exactement votre décision 15. Un test
  vérifie qu'aucun autre champ ne fuit (ni position, ni motif, ni contact, ni
  retour, ni échéance). Le geste correspondant reste
  `POST /api/watches/{id}/seen-by-host`, sur la veille.
- La liste ne contient que les veilles encore en attente d'arrivée (`ARMED` ou
  `EN_ROUTE`) : dès qu'un inscrit valide son arrivée, il en disparaît.

## 3. §3 — `GUARDIAN_CONSENT_REQUEST` : oui, il est émis, et voici l'engagement

Confirmé : `GUARDIAN_CONSENT_REQUEST` est bien émis aujourd'hui, à un contact
d'urgence **qui est membre meetDo**, au moment où son parrain l'invite
(`POST /api/guardians/{id}/invite`). Sa charge porte `consentToken` et
`ownerName` ; le tap doit ouvrir l'écran accepter / refuser.

Vous avez raison sur le fond : un type inconnu ne casse rien, il s'affiche mal,
et c'est plus dur à voir qu'une panne. **L'engagement que vous demandez, nous le
prenons** : tout ajout d'un type de notification vous sera signalé. Pour solde de
tout compte sur l'existant, voici la liste complète des types que le module émet :

| Type | Destinataire | Canal |
|---|---|---|
| `WATCH_RETURN_REMINDER` | la personne veillée | push (time-sensitive) |
| `WATCH_ARRIVAL_PROMPT` | la personne veillée | push (time-sensitive) |
| `WATCH_GUARDIAN_ALERT` | contact d'urgence **membre** | push in-app (time-sensitive) |
| `WATCH_LOST_ORGANIZER` | organisateur | push in-app (non critique) |
| `GUARDIAN_CONSENT_REQUEST` | contact **membre** désigné | push in-app |

Aucun ne décrit une fin de veille — c'est tenu, et un test le garantit.

## 4. §4 et §5 — actés, rien à faire

- **§4** — le `collapse-id` qui remplace les relances : décision consignée, ici
  comme chez vous. Sans identifiant stable posé par nous, une notification livrée
  ne peut pas être retirée à la clôture ; une pile de trois bannières périmées
  coûte plus qu'une seule à jour. Personne ne reviendra là-dessus.
- **§5** — le canal natif iOS (le retrait des délivrées), les accusés par canal,
  et la `timeline` non modélisée : rien de tout cela n'est bloquant, et rien ne
  nous est demandé tant que le SMS est éteint. Le `collapse-id` est bien posé de
  notre côté ; le reste vous appartient.

---

Deux champs de plus, deux décisions écrites. Nous ne voyons plus rien d'ouvert
côté serveur — dites-nous si vous en trouvez.
