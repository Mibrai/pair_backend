# Module cycle — quatre demandes, dont une qui doit passer avant les trois autres

**Écrit le 2026-09-03, révisé le 2026-09-04.** Contexte :
[`PLAN_IMPLEMENTATION_2026-09-03.md`](PLAN_IMPLEMENTATION_2026-09-03.md) ·
maquette [`template/meetdo-rappel.html`](template/meetdo-rappel.html)

> **Ce que la révision du 04/09 change.** La question 4 (recherche d'activités) était posée
> ouverte ; elle a été **mesurée en HTTP** et devient une demande chiffrée. Une demande
> neuve s'ajoute (§5, la sémantique de `nextSessionAt`) : elle porte sur un champ que vous
> servez déjà, et elle vaut au-delà de ce module. Et le déclencheur de l'étape 7 gagne une
> contrainte explicite, pour la raison expliquée au §2.

L'app va guider les organisateurs le long d'un cycle en sept états — envie, programme,
créneau, publication, inscriptions, séance, clôture — et relancer ceux qui s'arrêtent au
deuxième. **L'essentiel se calcule côté client** avec ce que vous rendez déjà :
`myCreatedProgramsProvider` appelle `GET /programs`, qui rend `createdAt`, `status`,
`schedules` complets, `nextSessionAt` et `enrolledCount`. Le module n'ajoute **aucun appel
réseau** sur cet écran. Quatre choses seulement manquent.

---

## Demande 1 — Le compteur, avant tout le reste

**Ce n'est pas une route, c'est une requête à lancer une fois et à nous rendre.**

Parmi les programmes existants :

1. Combien n'ont **aucun créneau non annulé** ? (Pas « aucun créneau » : un programme dont
   l'unique créneau est passé en `CANCELLED` n'a plus de pin sur la carte, et c'est
   exactement la population qu'on cherche.)
2. Pour ceux-là, la distribution de `now() - created_at` : médiane, p75, p95.
3. Parmi les programmes qui ont **fini** par recevoir un premier créneau, la distribution
   du délai `premier_schedule.created_at - program.created_at` — médiane, p75, p95.

**Pourquoi ça passe en premier.** Les seuils de relance (J+3) et de mise en sommeil (J+7)
sont pour l'instant ceux de la demande initiale, pas ceux d'une mesure. Si la médiane de
la question 3 est de neuf jours, alors une relance à J+3 s'adresse en majorité à des gens
qui allaient le faire — et le module devient exactement le harcèlement qu'il est censé
éviter. Nous préférons livrer les seuils justes que livrer vite.

---

## Demande 2 — Un type de notification : `CYCLE_NUDGE`

Aucun des 37 types de l'énumération ne convient. `PROGRAM_REMINDER` et
`PROGRESSION_REMINDER` s'adressent aux **inscrits** d'un programme ; ici le destinataire
est son **auteur**. Les réutiliser mélangerait deux publics dans `NotificationPrefDto`,
où chacun se coupe séparément — quelqu'un qui refuse d'être relancé sur ses propres
brouillons perdrait aussi les rappels de séance des programmes qu'il a rejoints.

### Charge utile

```json
{
  "type": "CYCLE_NUDGE",
  "title": "« Course du mardi » attend sa date",
  "body": "Ton programme n'est visible sur aucune carte. Un geste suffit.",
  "data": {
    "type": "CYCLE_NUDGE",
    "stage": "2",
    "programId": "0f3a…",
    "programTitle": "Course du mardi"
  }
}
```

`data.type` est **dupliqué à l'intérieur de `data`** : c'est la clé que le routage client
lit, et son absence est précisément le défaut corrigé le 03/09 (voir
`modules/notifications/`). Rien ne casse si ce type arrive avant que le client le connaisse :
un type inconnu retombe sur `NotificationType.system` (vérifié le 04/09 dans
`notification_models.dart`).

### Déclencheurs

| `stage` | Condition | Cadence |
|---|---|---|
| `2` | programme sans **aucun créneau non annulé** depuis N jours (N = demande 1) | **une seule fois**, jamais répétée |
| `4` | publié depuis 48 h, `enrolledCount = 0`, prochaine séance à plus de 48 h | une seule fois par programme |
| `7` | **dernière occurrence terminée** depuis ≥ 24 h, aucune à venir | une seule fois par programme |

### ⚠️ L'étape 7 se mesure sur la **fin** d'une séance, jamais sur son début

C'est la contrainte ajoutée le 04/09, et elle mérite son paragraphe parce que le piège
existe des deux côtés.

Vos routes bornent « à venir » sur le **début** : `GET /slots/mine?upcoming=true` exclut un
créneau **dès l'instant où il démarre** (mesuré le 03/09 : créneau commencé depuis 45 min
absent, créneau à +2 h présent). Si le déclencheur de l'étape 7 se lit « plus aucune
occurrence à venir », alors sur un programme non récurrent il devient vrai **à la seconde
où la séance commence** — et la notification « ton cycle est bouclé, repose le même » part
au milieu du cours, à quelqu'un qui y est.

Nous avions écrit la même faute côté client, et nous l'avons corrigée : c'est la famille de
défaut que l'app vient de réparer trois fois en trois jours (« Ton programme du jour » le
03/09, `SlotFeedItem.isPast` le 04/09). D'où la formulation du tableau : **`ends_at`
dépassé de 24 h**, et une séance sans `ends_at` déclaré n'est **jamais** réputée terminée.

### Plafond

Une notification `CYCLE_NUDGE` par utilisateur par **48 h**, toutes étapes confondues, et
**trois au maximum pour un même programme sur toute sa vie**. Les heures calmes existantes
s'appliquent. Le client tient le même plafond de son côté ; il ne remplace pas le vôtre,
parce que lui ne voit que ce qui arrive jusqu'à l'app.

---

## Demande 3 — Le sommeil (`DORMANT`), et pas la suppression

### Ce qu'on demande

Un statut supplémentaire sur `ProgramDto.status` : `DORMANT`, posé par un job sur un
programme resté sans **aucun créneau non annulé** M jours après sa création (M = demande 1,
≈ J+7).

| Route | Un programme `DORMANT` doit… |
|---|---|
| `GET /programs?lat&lng` — onglet **Activités** de la carte | **en sortir** |
| `GET /activities/browse`, recherche | **en sortir** |
| `GET /slots/bounds`, `GET /slots/feed` — onglet **Créneaux** | sans objet (il n'a aucun créneau vivant), mais à vérifier |
| `GET /users/me/programs` | **y rester** — son auteur doit continuer à le voir |
| `GET /programs/{id}` | répondre normalement à son auteur |
| `POST /programs/{id}/join` | refuser |

La troisième ligne est neuve depuis le 03/09 : la carte a deux onglets et deux géométries
depuis que `GET /slots/bounds` est allumée (04/09). Un programme dormant ne devrait rien y
avoir par construction — nous le signalons pour que ce ne soit pas découvert autrement.

Sortie du sommeil : `PATCH /programs/{id}` avec `{"status": "ACTIVE"}`, réservé à
l'auteur. En pratique le client la déclenchera automatiquement dès qu'un premier créneau
est posé sur un programme dormant — mais la route explicite est nécessaire pour le bouton
« Le réveiller ».

**Rien à changer côté client à la livraison** : `status` est déjà une `String` libre dans
`program_models.dart` (revérifié le 04/09), une valeur inconnue ne casse rien.

**À ne livrer qu'après la demande 1.** Une échéance annoncée dans l'app que le serveur
n'applique pas est pire que pas d'échéance du tout — c'est d'ailleurs pour ça que le
sommeil a son propre drapeau côté client, séparé de celui du guide : nous pouvons taire
l'échéance sans rien déployer si le job tombe.

### Ce qu'on ne demande pas, et pourquoi

**Aucune suppression automatique.** La demande initiale prévoyait un effacement à J+7. Il
n'aura pas lieu :

- une suppression silencieuse est une perte de données sans consentement, et
  « j'avais créé mon cours, il a disparu » est un ticket qu'on ne peut pas fermer ;
- le sommeil rend **tout** le service attendu — retirer les coquilles vides des listes,
  de la carte et des recherches — sans le coût ;
- après trente jours de sommeil, l'app affiche un bouton « Supprimer définitivement » qui
  appelle le `DELETE /programs/{id}` existant. C'est un geste humain, jamais une échéance.

**Et surtout : pas de `DELETE /activities/{id}`.** Cette route n'existe pas (revérifié le
04/09 sur `/v3/api-docs`) et **elle ne doit pas être créée**. `ActivityDto` est un
référentiel partagé sans propriétaire : supprimer « Padel » parce qu'un utilisateur n'a
jamais posé son programme le retirerait des quarante autres personnes qui l'ont sur leur
profil, et casserait leurs programmes en cascade. Le seul effacement légitime de cette
chaîne est `DELETE /users/me/activities/{userActivityId}` — le lien personnel, déjà livré.

---

## Demande 4 — `GET /activities?search=` doit ignorer les accents

**C'était une question le 03/09. Elle a été mesurée le 04/09** (la route est publique,
aucune session nécessaire), et la réponse est non :

| Requête | Résultat |
|---|---|
| `course` | 1 — `Course à pied` ✅ |
| `cours` | 1 ✅ |
| `COURSE` | 1 — insensible à la casse ✅ |
| `yog` | 4 — `Hatha Yoga`, `Vinyasa Yoga`, `Yin Yoga`, `Yoga` : **infixe** ✅ |
| **`course a pied`** | **0** ❌ — alors que `Course à pied` rend 1 |
| `corse` | 0 — aucun rapprochement phonétique |

La casse et l'infixe sont bons. **L'accent ne l'est pas**, et c'est le cas le plus banal du
français : quelqu'un qui tape « course a pied » ne voit aucune suggestion, et crée le
cinquième doublon de « Course à pied » au catalogue — précisément ce que le module existe
pour empêcher.

**Demandé :** normalisation Unicode des deux côtés de la comparaison (`unaccent`, ou
`lower(unaccent(name)) LIKE lower(unaccent(:q))` — vous saurez mieux que nous), pour que
`course a pied`, `COURSE À PIED` et `Course à pied` rendent la même chose.

**Souhaité, non bloquant :** un rapprochement flou (trigramme ou `SOUNDEX`) pour attraper
la faute de frappe. Nous avions cru pouvoir compter sur le LIKE/SOUND noté ailleurs sur la
recherche de programmes : `corse → 0` montre qu'il ne s'applique pas ici.

En attendant, le client replie sur une normalisation locale appliquée au catalogue déjà
chargé. Ça marche, c'est moins bon — la normalisation locale ne connaît que ce que la page
courante a ramené — et nous préférons le dire que le laisser découvrir.

---

## Demande 5 — Une question sur `nextSessionAt`, qui dépasse ce module

Le contrat définit `nextSessionAt` comme « la plus proche occurrence **à venir** ». Nous
avons besoin de savoir ce que « à venir » veut dire pendant qu'une séance a lieu :

> **Pour un programme non récurrent dont l'unique séance est en cours (démarrée il y a
> 20 minutes, `ends_at` dans 40 minutes), que vaut `ProgramDto.nextSessionAt` ?**
> `null`, ou l'heure de début de la séance en cours ?

Ce n'est pas une curiosité. Si la réponse est `null`, alors **`programIsExpired()` de l'app
rend déjà `true` pendant la séance** (`schedules` non vide + `nextSessionAt == null`) — et
ce prédicat commande trois choses : le programme quitte la carte, ses tuiles se grisent, et
le bouton « Rejoindre » disparaît. Cela se produirait **au moment précis où la séance a
lieu**. Nous préférons vous poser la question plutôt que de deviner et de patcher au
jugé.

Si la réponse est `null`, notre demande — et elle est petite — est que `nextSessionAt`
reste **la séance en cours tant qu'elle n'est pas terminée**, avec la même règle que
partout ailleurs dans ce document : sans `ends_at` déclaré, on ne conclut pas qu'elle est
finie.

Le module, lui, s'en protège déjà : il ne lit `nextSessionAt` que pour une seule frontière,
« ce programme a-t-il un pin sur la carte ? », qui porte sur le futur et reste juste dans
les deux cas.
