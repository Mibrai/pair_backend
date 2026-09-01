# Après le câblage — un défaut de notre côté, et quatre demandes

**Date :** 2026-09-01
**Fait suite à :** `REPONSE_BACKEND_2026-09-01-BIS.md` et `-TER.md`

Tout ce que vous avez livré est branché : `alertDelivery`, `attemptsLeft`, la cadence, `seen-by-host`, `history`, `DELETE /incidents/{id}`, la charge APNs, et le renommage en `note`. 2 711 tests verts de notre côté.

Quatre points restent, dont un qui rend une de vos livraisons invisible.

---

## 0. D'abord, un défaut chez nous, que votre contrat disait pourtant

`GET /api/watches/{id}` rend `WatchDetailDto = {watch, timeline, alertDelivery}`. Notre parseur cherchait `id` **à la racine** : contre le vrai serveur, cette route levait donc une erreur de forme. Aucun test ne l'a vu — ils simulent tous un corps à plat.

Vous l'aviez écrit noir sur blanc dès le 01/09 (« `{watch, timeline}` »). Personne chez nous n'a rapproché la phrase du code. C'est corrigé, et nous le signalons parce que c'est le genre d'écart qu'un contrat bien rédigé ne suffit pas à empêcher.

---

## 1. `alertDelivery` n'est pas sur `/watches/active` — et c'est là qu'il servirait

Le champ existe sur `WatchDetailDto`. Il n'est **pas** sur `WatchDto`, donc pas sur `GET /api/watches/active`.

Or c'est `active` qui alimente le bandeau global affiché sur **toutes** les pages de l'app. Ce bandeau disait jusqu'ici « Message d'urgence envoyé — à Camille ». Sur un `BOUNCED`, c'est faux : le message n'est jamais arrivé. Nous l'avons corrigé — il dit désormais « Le message n'est pas arrivé · contacte Camille toi-même, son adresse est peut-être fausse ».

**Ce code est écrit, testé, et dormant** : le bandeau ne verra jamais `BOUNCED`, faute du champ sur la liste active.

Nous n'irons pas le chercher par un appel supplémentaire : relire `GET /watches/{id}` depuis le bandeau serait exactement la relecture de veille que votre §2.1 interdit après une clôture — c'est ce qui révélerait `ESCALATED` à quelqu'un qui vient de taper un code de contrainte. Le champ doit venir avec la liste, ou pas du tout.

**Demande :** porter `alertDelivery` sur `WatchDto`, ou au moins sur la réponse de `/watches/active`.

C'est le point le plus utile de ce document : avec un seul canal, `BOUNCED` est la seule information qui dise que le proche n'a pas été joint, et elle n'atteint aujourd'hui aucun écran que la personne regarde spontanément.

---

## 2. Rien ne dit à un organisateur qui n'est pas arrivé

Vous avez livré le **geste** — `POST /api/watches/{id}/seen-by-host` — sans la **liste**. Aucune route ne dit à l'organisateur d'un créneau quelles veilles de ses inscrits attendent encore une arrivée.

`/watches/active` ne rend que les veilles de l'appelant. Nous en servir aurait affiché à l'organisateur ses propres veilles sous le nom de ses inscrits : une information fausse, au pire endroit possible. Nous ne l'avons pas fait.

L'écran A7 est donc posé, câblé sur `seen-by-host`, et alimenté par une liste vide.

**Demande**, dans une forme qui ne dépasse pas la décision 15 (l'organisateur voit un nom, une absence, une heure — jamais une position, un motif, un proche, ni un retour) :

```
GET /api/schedules/{scheduleId}/pending-arrivals
  → [{ watchId, name, since }]
  réservé à l'organisateur ; 404 (jamais 403) sinon
```

**Aucun autre champ.** Notre type `HostAbsence` est écrit pour ne pas pouvoir en accueillir davantage, et un test inventorie les cinq seules phrases que le bandeau a le droit de rendre.

---

## 3. `GUARDIAN_CONSENT_REQUEST` arrive sans que l'app le connaisse

En relevant vos types de notification sur `/v3/api-docs`, nous en avons trouvé un que nous n'avions jamais demandé et que l'app ignore : **`GUARDIAN_CONSENT_REQUEST`**, la demande d'accord au contact d'urgence.

Il arrive donc chez les membres désignés comme un type inconnu : sans icône, sans destination, rangé en « système ». C'est-à-dire que le message le plus important du parcours de contact — « quelqu'un vous a désigné, acceptez-vous ? » — est celui qui s'affiche le moins bien.

Nous le traiterons de notre côté. Nous le signalons pour deux raisons : confirmer qu'il est bien émis aujourd'hui, et vous demander de **nous prévenir quand un type de notification est ajouté**. Un type inconnu ne casse rien chez nous — il s'affiche simplement mal, ce qui est plus difficile à remarquer qu'une panne.

---

## 4. Le `collapse-id` supprime la pile des trois relances — nous l'assumons

Vous avez posé `apns-collapse-id: watch-<watchId>`, comme nous l'avions demandé. Effet de bord que nous n'avions pas énoncé : les trois relances se **remplacent** au lieu de s'empiler. Quelqu'un qui rallume son téléphone ne verra qu'un rappel là où il en a manqué trois.

**Nous le gardons ainsi**, et voici pourquoi, pour que personne ne « corrige » ce choix plus tard : sans identifiant stable posé par vous, une notification livrée par APNs ne peut pas être retirée à la clôture — donc quelqu'un qui vient de refermer sa veille continuerait de lire « dernier rappel » sur son écran verrouillé. Une pile de trois bannières périmées coûte plus qu'une seule bannière à jour.

Rien à faire de votre côté. C'est une décision consignée, pas une demande.

---

## 5. Ce qui reste chez nous, pour information

- **Le retrait des notifications délivrées à la clôture ne marche pas encore sur iOS.** Le greffon `flutter_local_notifications` ne transporte qu'un entier là où il faudrait votre `watch-<watchId>` : la brique manquante est un canal natif dans `ios/Runner`. Votre `collapse-id` est bien posé, c'est notre côté qui n'aboutit pas.
- **Les accusés par canal** (SMS remis, e-mail ouvert, contact en train de regarder la page publique) restent des valeurs locales, sans champ serveur. `alertDelivery` couvre le besoin essentiel ; nous ne demandons rien de plus tant que le SMS est éteint.
- **La `timeline` de `WatchDetailDto`** n'est ni modélisée ni affichée chez nous. Elle n'est bloquante pour rien aujourd'hui.
