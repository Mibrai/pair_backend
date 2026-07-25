# Prompt pour Claude Code — backend meetDo

> À copier tel quel dans l'instance Claude Code ouverte sur le dépôt backend
> (`org.program.pair`). Rédigé depuis le client Flutter après intégration
> complète de l'évolution meetDo : chaque point ci-dessous a été **reproduit
> côté serveur**, en `curl`, indépendamment de l'application.
>
> Environnement de test : `https://pairbackend-production-35fe.up.railway.app`
> Spec publique : `/v3/api-docs` (sans préfixe `/api`)

---

Tu travailles sur le backend meetDo (Spring Boot, namespace `org.program.pair`).
Le client Flutter a terminé l'intégration de l'évolution meetDo (créneaux
ouverts, confirmation de présence, statistiques de pratique, alertes par
activité). Cinq problèmes bloquent ou dégradent le produit côté client. Traite-les
dans l'ordre : les deux premiers sont bloquants.

Pour chacun, écris un test qui échoue d'abord, corrige, puis vérifie que le test
passe. Ne modifie pas le contrat public au-delà de ce qui est demandé.

---

## 1. BLOQUANT — `POST /api/attendances/{scheduleId}/confirm` renvoie 500 quand `wasPresent = true`

C'est le geste central de rétention du produit (« Tu y étais ? »). Il est
inutilisable : l'utilisateur tape « Oui, j'y étais » et reçoit une erreur serveur.

### Reproduction

```bash
BASE=https://pairbackend-production-35fe.up.railway.app/api
TOKEN="<access token d'un utilisateur ayant une présence en attente>"
SCHEDULE="<un scheduleId renvoyé par GET /api/attendances/pending>"

# ÉCHOUE — 500
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"wasPresent":true}' "$BASE/attendances/$SCHEDULE/confirm"
# → {"code":"INTERNAL_ERROR","message":"Une erreur est survenue.", ...}

# PASSE — 200, AttendanceDto complet
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"wasPresent":false}' "$BASE/attendances/$SCHEDULE/confirm"
```

### Ce que ça dit du bug

`false` passe, `true` échoue. La seule différence de chemin, d'après votre propre
spec, est que `wasPresent = true` **recalcule les statistiques de pratique et
réévalue les badges**. Le défaut est donc dans cette branche, pas dans la
validation d'entrée ni dans l'écriture de l'`Attendance` elle-même.

Pistes à vérifier dans cet ordre :

1. Le recalcul de `distinct_partners_count` / `attendance_count` /
   `current_streak_weeks` sur `users` (migration V41) — division par zéro,
   `NULL` non géré, ou requête d'agrégat sur un créneau sans autre participant.
2. `BadgeService` déclenché après confirmation positive : les nouveaux codes
   (`FIRST_MEETUP`, `TEN_MEETUPS`, `FIVE_PARTNERS`, `TWENTY_PARTNERS`,
   `STREAK_4_WEEKS`, `STREAK_12_WEEKS`, `SLOT_HOST`) sont-ils tous présents dans
   `seed/data/badges.json` en base de test **et** en production ? Un code absent
   ferait échouer la résolution du badge.
3. L'envoi de notification associé — voir le point 2 ci-dessous, qui est
   probablement la même racine.

**Attendu** : `{"wasPresent":true}` renvoie `201`/`200` avec l'`AttendanceDto`,
les compteurs de `users` sont à jour, et les badges éligibles sont attribués.
Ajoute un test d'intégration couvrant le premier `true` d'un utilisateur (cas
`attendanceCount` passant de 0 à 1, où les agrégats sont les plus fragiles).

---

## 2. BLOQUANT — `notifications.payload` renvoie un dump interne de Jackson

Toute notification renvoyée par `GET /api/notifications` porte un `payload`
inexploitable :

```json
{
  "id": "f1000000-0000-0000-0000-000000000002",
  "type": "NEW_PEER_REC",
  "channel": "IN_APP",
  "payload": "{'array': false, 'bigDecimal': false, 'bigInteger': false, 'object': true, ...}",
  "isRead": true,
  "sentAt": "2026-06-20T19:23:20.756221Z"
}
```

C'est la sérialisation des **propriétés internes d'un `JsonNode` Jackson**, pas
son contenu. C'est très probablement la divergence `jsonb` / `varchar` que vous
avez vous-mêmes documentée en §13 de la spec d'évolution — elle ne provoque pas
qu'une erreur silencieuse à l'écriture, elle corrompt aussi la lecture.

### Conséquence produit

**Aucun deep-link de notification ne peut fonctionner.** Le client lit
`scheduleId`, `programId`, `authorId`, `categoryId` dans ce payload pour router
vers le bon écran. Tous ces champs sont inaccessibles, donc taper une
notification ne fait rien. Les six nouveaux types sont directement concernés :

| Type | Identifiant attendu dans `payload` |
|---|---|
| `SLOT_JOINED`, `ACTIVITY_ALERT_MATCH` | `scheduleId` |
| `SLOT_CANCELLED` | `scheduleId` |
| `ATTENDANCE_PROMPT` | `scheduleId` |
| `PROGRAM_CANCELLED`, `SCHEDULE_CHANGED` | `programId` |
| `STREAK_MILESTONE`, `PARTNER_MILESTONE` | — (aucun, redirige vers le profil) |

**Attendu** : `payload` est un **objet JSON** (pas une chaîne) contenant les
identifiants métier. Corrige le mapping Hibernate de la colonne `jsonb` et le
sérialiseur Jackson du DTO. Ajoute un test qui vérifie que
`GET /api/notifications` renvoie un `payload` désérialisable et contenant
`scheduleId` pour une notification `SLOT_JOINED`.

---

## 3. `NotificationDto` ne correspond pas à sa propre spec OpenAPI

La réponse réelle et le schéma publié divergent, ce qui a coûté du temps côté
client :

| Champ | `/v3/api-docs` | Réponse réelle |
|---|---|---|
| lu / non lu | `read` | `isRead` |
| date d'envoi | `createdAt` | `sentAt` |
| titre | `title` | **absent** |
| corps | `message` | **absent** |

Le client tolère les deux nommages et retombe sur un libellé par type quand
`title`/`message` manquent — il n'y a donc pas d'urgence. Mais **l'un des deux
doit devenir la vérité** : soit le DTO expose `read`/`createdAt`/`title`/`message`
comme annoncé, soit l'OpenAPI est corrigé pour refléter `isRead`/`sentAt` et
l'absence de texte.

Si vous gardez l'absence de `title`/`message` : dites-le explicitement dans la
description du schéma, pour que les clients sachent qu'ils doivent fournir les
libellés eux-mêmes.

---

## 4. `POST /api/recommendations` — rendre `rating` et `comment` facultatifs

**Cette fonctionnalité est actuellement désactivée dans l'app mobile**
(`FeatureFlags.peerRecommendations = false`) à cause de ce contrat.

Le détail complet, avec la justification produit, est dans
`ios/docs/BACKEND_PEER_RECOMMENDATION_CONTRACT.md` du dépôt mobile. En résumé :

`CreateRecommendationRequest` impose `rating` (1..5) **et** `comment`
(20..500 caractères), tous deux obligatoires. Or le principe produit — rappelé
dans votre propre spec d'évolution — est qu'**aucun écran ne note ni ne juge une
personne**. Une recommandation entre pairs est binaire : je recommande, ou je ne
fais rien.

Remplir ces champs à la place de l'utilisateur revenait à publier une note de
5/5 et un témoignage qu'il n'a jamais écrit sous son propre nom. Nous avons
retiré la fonctionnalité plutôt que de faire ça.

**Demandé** :

- `rating` facultatif. S'il est absent, **ne rien stocker** — pas de valeur par
  défaut à 5, qui recréerait exactement le biais qu'on cherche à supprimer. Si
  la colonne est `NOT NULL`, prévoir une migration la rendant nullable.
- `comment` facultatif, et **supprimer le minimum de 20 caractères** s'il est
  fourni (« Super partenaire » fait 16 caractères et est légitime). Garder la
  borne haute de 500.
- Pas de nouvel endpoint : assouplir l'existant, pour ne pas maintenir deux
  chemins d'écriture.

Corps minimal qui doit suffire :

```json
{ "recommendedId": "uuid" }
```

**Question associée, à confirmer explicitement** : la documentation de
l'endpoint indique encore « Nécessite une conversation existante », alors que la
spec d'évolution annonce la double confirmation de présence
(`SHARED_ATTENDANCE`) comme preuve d'interaction alternative. Le parcours mobile
est exactement celui-là : la feuille de recommandation s'ouvre après un « Oui,
j'y étais » et ne propose que des personnes ayant **elles aussi** confirmé leur
présence sur le même créneau — deux personnes qui n'ont souvent jamais échangé
de message. Si `SHARED_ATTENDANCE` n'est pas encore accepté, l'endpoint
renverra `403` sur la quasi-totalité de ce parcours.

---

## 5. `Category.colorRamp` mélange trois formats incompatibles

Relevé sur les 45 catégories renvoyées par `GET /api/categories` :

| Format | Occurrences | Exemples |
|---|---|---|
| Nom de rampe | 30 | `orange-red`, `purple-violet`, `sky-blue` |
| Hexadécimal | 10 | `#EF4444`, `#8B5CF6` |
| `null` | 5 | — |

Un client qui prend ce champ pour une couleur — ce que la spec frontend
suggérait explicitement — obtient une valeur invalide dans **35 cas sur 45**.

Le client mobile s'en sort en traitant `colorRamp` comme une *intention de
teinte* qu'il résout dans sa propre palette, donc ce n'est pas bloquant. Mais le
champ n'est pas exploitable en l'état par un nouveau client.

**Demandé** : normaliser sur **un seul** format et le documenter dans le schéma
`CategoryDto`. Notre préférence va au nom de rampe (`orange-red`) plutôt qu'à
l'hexadécimal : il laisse chaque client appliquer sa propre identité visuelle,
ce qui est exactement l'usage. Prévoir une migration pour les 10 valeurs
hexadécimales et les 5 nulles.

---

## 6. Jeu de données de démonstration — aucun créneau à venir

Non bloquant, mais ça fait passer le produit pour cassé lors des démos.

Les 3 créneaux du seed sont **tous dans le passé** :

```
Hatha Yoga für Einsteiger — München   2026-07-11   status=PAST
Hatha Yoga für Einsteiger — München   2026-07-13   status=PAST
Yoga für Fortgeschrittene München     2026-07-12   status=PAST
```

Or `GET /api/slots/feed` interroge la fenêtre « maintenant → +7 jours ». Le feed
« Autour de toi » — l'écran d'accueil de l'app, le cœur du nouveau produit — est
donc systématiquement vide, y compris avec une position correcte à Munich.

**Demandé** : que le seed génère des créneaux **relatifs à la date d'exécution**
(par exemple J+1, J+2, J+5, à des heures variées) plutôt qu'à des dates figées,
et qu'au moins deux d'entre eux aient `isOpenToPartners = true` et des
coordonnées valides à Munich. Ajouter aussi un créneau appartenant à un
**autre** utilisateur que le compte de démonstration : le feed exclut
volontairement les créneaux de l'appelant, donc un seed mono-utilisateur reste
vide même avec des dates futures.

---

## Ce qui fonctionne, pour information

Vérifié en `curl` sur l'environnement de production, aucun problème :

- `GET /api/slots/feed`, `/slots/mine`, `/slots/{id}`, `/slots/{id}/join`
- `GET /api/attendances/pending`, `/attendances/{id}/co-participants`
- `GET|POST /api/alerts`, `PATCH|DELETE /api/alerts/{id}`
- `GET /api/users/me/practice-stats` et `/api/users/{id}/practice-stats`
- Les 30 valeurs de `NotificationType`, dont les 6 nouvelles
- Les nouveaux champs de `ScheduleDto` (`isOpenToPartners`, `status`,
  `participantCount`, `welcomeNote`) et de `CreateScheduleRequest`

Le client mobile est prêt et branché sur tous ces endpoints.
