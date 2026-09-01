# Livraison — Traçabilité & veille retour

**Date :** 2026-09-01
**Fait suite à :** `PROMPT_BACKEND_2026-08-31.md`, `REPONSE_BACKEND_2026-08-31.md` et son addendum.

> **Le module est livré et déployé.** Priorités 1 à 7, plus la page publique de
> statut. 886 tests. Tout est sous `/api` (authentifié) sauf les pages publiques.
>
> **Deux écarts au contrat de départ**, tous deux signalés en leur temps et
> assumés ici : `arrival` accepte un code de contrainte optionnel, et le canal
> **SMS est éteint** — les alertes partent par **e-mail** pour l'instant.
>
> **Trois responsabilités côté app** que le serveur ne peut pas tenir à votre
> place : cacher l'état sous contrainte, ne jamais clôturer depuis la page
> publique, et téléverser la pièce jointe d'incident avant de la référencer.

---

## 1. Les deux écarts au contrat

### 1.1 · `arrival` accepte un code de contrainte optionnel

La demande décrivait `POST /watches/{id}/arrival` avec un corps vide, et laissait
implicite **d'où vient le code de contrainte**. Le serveur ne peut pas l'inventer :
un code de contrainte doit être mémorisable, donc **choisi par la personne**.

Le corps de `arrival` accepte donc un champ facultatif :

```
POST /api/watches/{id}/arrival
{ "duressCode": "SESAME" }      // facultatif ; 4 à 32 caractères
```

Sans ce champ, tout se comporte comme prévu : le serveur tire le code de retour
et le rend une seule fois. Avec, il enregistre en plus l'empreinte du code de
contrainte. **Aucune contrainte sur votre écran** si vous ne l'exposez pas encore :
le champ est optionnel, et un `arrival` à corps vide reste valide.

### 1.2 · Le canal SMS est éteint — les alertes partent par e-mail

Sur votre indication, la décision d'envoyer des SMS n'étant pas tranchée, le
canal SMS est **désactivable et éteint par défaut**. En production aujourd'hui,
les messages ②③④⑤ partent **uniquement par e-mail**.

Ce que cela change pour vous : **rien à l'écran**, mais deux points à connaître.

- Un contact d'urgence **externe doit avoir un e-mail**. C'était déjà le cas :
  l'invitation d'un contact qui n'a **qu'un** téléphone est refusée
  (`GUARDIAN_SMS_NOT_AVAILABLE`). Tant que le SMS est éteint, cette règle est ce
  qui garantit qu'aucun contact accepté ne devient injoignable.
- L'infrastructure SMS est **prête** (abstraction fournisseur, outbox durable,
  gabarits SMS ②③⑤). L'allumer, le jour venu, sera une affaire de configuration
  (`WATCH_SMS_ENABLED=true` + un fournisseur), sans changement d'API ni de votre
  côté.

---

## 2. Trois responsabilités côté app

### 2.1 · Cacher l'état sous contrainte

C'est le point le plus important, et le seul que le serveur ne peut pas tenir
seul. `POST /watches/{id}/close` répond **`202`, corps vide, exactement pareil**
que le code soit le vrai ou celui de contrainte. C'est voulu : la réponse ne doit
rien trahir.

La différence est dans **l'état de la veille** — `CLOSED` pour un vrai code,
`ESCALATED` pour le code de contrainte — et cet état est visible sur
`GET /watches/{id}`.

**Donc : après un `close` qui rend `202`, affichez « c'est refermé » et ne
rallez pas relire l'état de la veille sur cet écran.** Un observateur par-dessus
l'épaule, dans la situation même que le code de contrainte existe pour couvrir,
ne doit voir aucune différence. Traitez le `202` comme un succès et quittez
l'écran de détail ; ne révélez jamais `ESCALATED` à la suite d'un `close`.

### 2.2 · La page publique ne clôture jamais

`GET /public/watch/{token}` est ouverte, sans compte. Elle porte deux boutons
d'accusé — « j'ai vu », « je l'ai eue au téléphone » — qui remontent dans l'app.
**Aucun bouton ne clôture**, et il ne doit jamais y en avoir : la page est
publique, un bouton de clôture clôturerait pour quiconque a le lien. La clôture
reste le geste authentifié de la personne veillée, avec son code.

### 2.3 · La pièce jointe d'incident se téléverse d'abord

`POST /api/incidents` prend un champ `attachmentUrl` facultatif. Le fichier lui-
même passe par le pipeline média existant : téléversez la photo via
`/api/media/upload/image`, récupérez son URL, et passez-la dans `attachmentUrl`.
L'incident n'en garde que l'URL.

---

## 3. Le contrat des routes

Toutes sous `/api` et authentifiées, sauf les pages publiques (sans préfixe).

### 3.1 · Contacts d'urgence

| Verbe | Chemin | Corps / retour |
|---|---|---|
| `GET` | `/api/guardians` | `Guardian[]` |
| `POST` | `/api/guardians` | `{memberId}` **ou** `{name?, phone?, email?}` → `201` |
| `POST` | `/api/guardians/{id}/invite` | envoie le message ①, une fois |
| `DELETE` | `/api/guardians/{id}` | `204` |
| `GET` | `/public/guardian-consent/{token}` | page HTML, deux boutons |
| `POST` | `/public/guardian-consent/{token}/accept` | applique |
| `POST` | `/public/guardian-consent/{token}/refuse` | applique, définitif |

Un contact est **soit** un membre (`memberId`), **soit** externe (au moins un
canal). `consentToken` n'est jamais rendu dans les réponses `/api` : c'est le
secret du lien.

### 3.2 · Veilles

| Verbe | Chemin | Retour |
|---|---|---|
| `POST` | `/api/watches` | `201 Watch` — `{scheduleId, guardianId, backupGuardianId?, deadlineAt?}` |
| `GET` | `/api/watches/active` | `Watch[]` |
| `GET` | `/api/watches/{id}` | `{watch, timeline}` |
| `POST` | `/api/watches/{id}/arrival` | `{watch, returnCode}` — code en clair, **une fois** |
| `POST` | `/api/watches/{id}/close` | `202` / `409` — `{code, enteredAt}` |
| `POST` | `/api/watches/{id}/still-coming` | `Watch` |
| `POST` | `/api/watches/{id}/abandon` | `Watch` |
| `POST` | `/api/watches/{id}/interrupt` | `Watch` — `{reason?, alreadyHome, travelMinutes?}` |
| `POST` | `/api/watches/{id}/snooze` | `Watch` |
| `POST` | `/api/watches/{id}/panic` | `Watch` |
| `POST` | `/api/watches/{id}/resend-code` | `{watch, returnCode}` — `{password}` |
| `POST` | `/api/watches/{id}/revoke-link` | `204` |
| `DELETE` | `/api/watches/{id}` | `204` — désarmement avant départ |

`deadlineAt` est **facultatif** à l'armement : par défaut, fin du créneau + 1 h.
Sur un créneau sans fin déclarée, c'est `début + 3 h` — affichez la même heure que
celle que le serveur retiendra. `travelMinutes` (interruption) : 15 à 240, défaut
45 ; le serveur applique la valeur, il n'estime rien.

### 3.3 · Incidents & suivi

| Verbe | Chemin | Retour |
|---|---|---|
| `POST` | `/api/incidents` | `201 Incident` — `{target, note?, scheduleId?, targetUserId?, reason?, attachmentUrl?}` |
| `GET` | `/api/incidents/me` | `Incident[]` |
| `GET` | `/api/reports/me` | `Page<ReportSummary>` — servi depuis le 27/08 |

Cibles d'incident : `PERSON`, `PLACE`, `ORGANISATION`, `TRANSIT`, `SELF`. Seule
`PERSON` rejoint la modération (elle exige `targetUserId` et une `note` d'au moins
10 caractères), et apparaît alors **aussi** dans `/api/reports/me`. Les autres
restent dans `/api/incidents/me`, séparées.

`GET /reports/me` rend `ReportSummary { id, targetType, state, createdAt,
updatedAt }`, `state ∈ RECEIVED · RESOLVED · DISMISSED`. **Trois états, pas
quatre** : comme convenu, `IN_REVIEW` n'est pas servi tant qu'aucun geste de
modération ne l'écrit ; votre lecture tolérante le recevra sans casser le jour où
il existera.

### 3.4 · Page publique de statut

`GET /public/watch/{token}` — rendu serveur, `meta refresh` 60 s, `ETag` +
`Cache-Control: max-age=20` (répond `304` sur `If-None-Match`). Six états :

```
EN_TRAJET · SUR_PLACE · REPARTIE_PLUS_TOT ·
RETOUR_A_CONFIRMER · ALERTE_ENVOYEE · RENTREE
```

Deux `POST` d'accusé : `/public/watch/{token}/seen` et `/called`. Le lien naît
**à l'alerte**, pas à l'armement ; expire 24 h après clôture ; révocable par
`POST /api/watches/{id}/revoke-link`.

---

## 4. Les codes d'erreur à traiter

Rendus dans `{ code, message }`. Les `409` sont des **états**, pas des refus de
droit — traitez-les comme tels (stabiliser l'affichage, pas afficher une erreur).

- **Contacts** : `GUARDIAN_INVALID_CONTACT`, `GUARDIAN_SELF`,
  `GUARDIAN_ALREADY_DESIGNATED` (409), `GUARDIAN_CONTACT_REFUSED`,
  `GUARDIAN_ALREADY_INVITED` (409), `GUARDIAN_ALREADY_RESPONDED`,
  `GUARDIAN_SMS_NOT_AVAILABLE`.
- **Veilles** : `WATCH_GUARDIAN_NOT_ACCEPTED`, `WATCH_ALREADY_ACTIVE` (409),
  `WATCH_DEADLINE_PAST`, `WATCH_ARRIVAL_NOT_EXPECTED`, `WATCH_NO_CODE_TO_CLOSE`,
  `WATCH_CODE_WRONG` (409, avec le nombre d'essais restants dans le message),
  `WATCH_CODE_LOCKED` (409), `WATCH_NOT_OUTBOUND` (409), `WATCH_NOT_ON_SITE` (409),
  `WATCH_RESEND_ALREADY_USED` (409), `WATCH_PASSWORD_REQUIRED`,
  `WATCH_NOT_DISARMABLE` (409).
- **Incidents** : `INCIDENT_PERSON_TARGET_REQUIRED`, `INCIDENT_DESCRIPTION_REQUIRED`.

---

## 5. Ce qui reste, et qui ne vous bloque pas

- **Le vrai envoi SMS** (Twilio, accusés de remise, SLO 30 s) attend votre
  décision produit. Rien à faire de votre côté d'ici là.
- Quand vous voudrez l'allumer, dites-le-nous : c'est de la configuration, et
  votre app n'aura rien à changer.

Vos écrans peuvent se construire derrière les drapeaux, comme prévu : toutes les
routes répondent en production dès maintenant, SMS mis à part.
