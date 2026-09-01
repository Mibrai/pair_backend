# Réponse au retour du 01/09 — c'est livré, et deux confirmations

**Date :** 2026-09-01
**Fait suite à :** `PROMPT_BACKEND_2026-09-01.md`

> **Tout ce qui était du code est fait.** Le lien public que la veille cachait à
> sa propriétaire (§2), `attemptsLeft` en entier (§4.1), la charge APNs
> time-sensitive (§5), `seen-by-host` (§1a), le journal (§1b), le `DELETE` d'un
> incident (§1c), le renommage (§4.2), et l'état de remise (§6). 898 tests.
>
> **Deux points n'étaient pas du code, mais des confirmations que vous
> attendiez** : le §3 (oui, la contrainte est bien indistinguable dans
> `/watches/active`) et la cadence des rappels (§4.3, désormais au contrat
> ci-dessous).
>
> **Votre relecture était juste sur toute la ligne.** Les trois routes du 31/08
> au soir étaient bien arrivées après le gel du périmètre, pas refusées.

---

## 1. Le point qui vous bloquait : la veille dit maintenant son lien public

`GET /api/watches/{id}` **et** `GET /api/watches/active` portent désormais, sur
l'objet `watch` :

| Champ | Contenu |
|---|---|
| `publicToken` | le jeton du lien, ou `null` tant qu'aucune alerte n'est partie |
| `publicStatusUrl` | l'URL complète de la page de statut, ou `null` |
| `guardianSeenAt` | daté, quand le contact a cliqué « j'ai vu » |
| `guardianCalledAt` | daté, quand il a cliqué « je l'ai eue au téléphone » |

Vos deux précautions sont exactement les bonnes, et le serveur les respecte :
c'est **l'absence du `publicToken`** qui fait qu'il n'y a pas de lien à montrer,
jamais l'état de la veille ; et le jeton n'existe qu'à partir de l'alerte, donc
vous ne montrerez jamais un lien avant qu'il n'existe. La révocation continue de
passer par `POST /api/watches/{id}/revoke-link`.

---

## 2. §3 — la confirmation que vous demandiez : oui, la contrainte est indistinguable

Vous demandiez de confirmer qu'une veille passée `ESCALATED` **par code de
contrainte** reste dans `/watches/active` et y est indiscernable d'une escalade
ordinaire. **Confirmé, par construction :**

- Une clôture sous contrainte pose l'état `ESCALATED`, exactement comme une
  escalade de minuteur. `ESCALATED` n'est pas terminal, donc la veille figure
  dans `/watches/active`.
- **Aucun champ ne distingue les deux.** Le `WatchDto` ne porte pas de drapeau de
  contrainte, et il n'en portera pas : l'exposer rouvrirait le canal que le §2.1
  ferme. La seule trace de la contrainte est l'empreinte du code, jamais lue en
  réponse.
- La distinction n'existe que dans la chronologie interne (`GET /watches/{id}`),
  jamais dans `/watches/active`.

**Donc votre piste 2 est la bonne, et c'est la seule qui tienne** — nous ne
ferons rien côté serveur pour distinguer les deux escalades, ce qui vous
laisserait deviner. Votre trouvaille est juste : la fuite n'était pas dans la
relecture d'un écran, mais dans la coïncidence entre le geste et le bandeau
global. La régler côté app, en taisant le bandeau pour la veille qu'on vient de
clôturer jusqu'au prochain démarrage à froid, est le bon endroit — et il n'y a
pas d'endroit côté serveur où le faire sans réintroduire la distinction.

---

## 3. §4.3 — la cadence des rappels, au contrat

La voici comme valeur stable. Tout est compté depuis l'échéance figée
(`deadlineAt`), en minutes :

```
Boucle retour                       Boucle aller (depuis le début d'occurrence)
  +15  rappel 1                       +15  demande « tu y es ? » 1
  +30  rappel 2                       +30  demande 2
  +45  rappel 3                       +45  3e demande sans réponse ⇒ perdu en chemin
  +60  alerte au contact principal
  +75  alerte au contact de secours (si le principal n'a rien ouvert)
```

Ces valeurs sont désormais des constantes serveur nommées. Si nous devions les
changer, ce serait un point de contrat, pas un ajustement silencieux — votre
écran d'armement peut s'appuyer dessus.

**Le contact de secours** n'est prévenu qu'à `+75` **et** seulement si le
principal n'a pas ouvert la page publique (`public_viewed_at`) : une ouverture
avant cette fenêtre vaut réponse, et nous ne dérangeons alors pas le secours.

---

## 4. Les autres points, faits

### 4.1 · `attemptsLeft` — un entier à côté du message

`409 WATCH_CODE_WRONG` porte désormais un champ `attemptsLeft` (entier), en plus
de `code` et `message`. Vous n'avez plus à parser la chaîne. Sur `WATCH_CODE_LOCKED`,
`attemptsLeft` vaut `0`.

### 4.2 · `note`, et le code aligné

Le champ fait foi : c'est `note`. Le code d'erreur s'appelle désormais
`INCIDENT_NOTE_REQUIRED` (au lieu de `…DESCRIPTION…`), pour que les deux
concordent.

### 4.3 · `seen-by-host`, `history`, `DELETE /incidents/{id}`

- `POST /api/watches/{id}/seen-by-host` — l'organisateur repousse la relance de
  15 min. Il ne valide pas l'arrivée, ne crée aucun code. Réservé à
  l'organisateur du créneau ; une veille qu'il n'organise pas lui est
  **introuvable** (404), pas interdite. `204` en cas de succès.
- `GET /api/watches/history` — les veilles terminées, **sans aucune coordonnée** :
  `{id, state, activityName, placeName, city, occurrenceStartsAt, closedAt,
  alertSent}`. `alertSent` vous laisse compter les alertes sans deviner.
- `DELETE /api/incidents/{id}` — retire l'incident de votre journal (`204`). Le
  signalement de modération qu'un incident `PERSON` avait engendré **n'est pas
  touché** : les deux registres sont séparés, l'agrégat de sécurité est préservé.
  C'est votre préférence, tenue : « retirer de mon journal » retire du journal, et
  ne promet rien de plus.

### 4.4 · §6 — l'état de remise

`GET /api/watches/{id}` porte un champ `alertDelivery` : `NONE` (aucune alerte),
`PENDING`, `SENT`, `FAILED`. Avec un seul canal, c'est le retour qui vous
manquait pour dire « le message n'est pas parti » plutôt que de laisser croire
qu'il l'est.

**Honnêtement : ce n'est pas encore le « délivré » plein.** `SENT` veut dire
« accepté par le fournisseur d'e-mail », pas « arrivé dans la boîte ». Le vrai
délivré — et les rebonds — demanderait de brancher les webhooks du fournisseur,
comme le DLR l'aurait fait pour le SMS. C'est la brique qui reste, et nous vous
la signalons comme telle : `alertDelivery` couvre l'échec d'envoi, pas encore le
rebond silencieux. Si le point de défaillance unique de l'e-mail vous inquiète
au point de vouloir les rebonds, dites-le et nous priorisons le webhook Resend.

---

## 5. §5 — la charge APNs

C'est fait, pour les seuls types de veille (`WATCH_RETURN_REMINDER`,
`WATCH_ARRIVAL_PROMPT`, `WATCH_GUARDIAN_ALERT`) :

- **`interruption-level: time-sensitive`** dans l'`aps`. C'était votre point
  critique, et vous aviez raison de le distinguer d'`isCritical()` : le premier
  décide d'envoyer malgré le silence serveur, le second décide si iOS affiche
  malgré un mode Concentration. Les deux sont posés.
- **`apns-collapse-id: watch-<watchId>`** — les relances se remplacent, et vous
  pouvez retirer celles déjà délivrées à la clôture.
- **`watchId`** dans le `data`, et jamais évincé par la logique de budget de
  charge.
- Android : priorité maximale côté serveur. Le franchissement effectif du
  Ne-pas-déranger dépend aussi de l'importance du canal, que vous posez côté
  client — nous poussons au maximum ce que nous contrôlons.

Vos deux types (`WATCH_RETURN_REMINDER`, `WATCH_ARRIVAL_PROMPT`) sont les bons.
S'y ajoutent, côté serveur : `WATCH_GUARDIAN_ALERT` (alerte in-app à un contact
qui est membre) et `WATCH_LOST_ORGANIZER` (in-app à l'organisateur, « perdu en
chemin »). Aucun ne décrit une **fin** de veille — c'est votre §5.5, tenu, et un
test parcourt l'enum pour le garantir : jamais « close », « alerte envoyée » ni
« levée » dans un type de notification.

---

## 6. Le SMS — pris acte

Décision enregistrée : le SMS reste éteint, e-mail seul, rien à provisionner. Le
garde-fou `GUARDIAN_SMS_NOT_AVAILABLE` reste tel quel — c'est lui qui garantit
que personne ne devient injoignable sans qu'on le sache à l'invitation. Le champ
téléphone d'un contact externe reste accepté et sera resservi tel quel le jour où
le canal s'allumera, sans changement d'API.

La seule chose que cette décision rend nécessaire, et que nous vous devons
encore, est le **retour de remise plein** (les rebonds e-mail, cf. §4.4). Nous
attendons votre signal pour le prioriser.
