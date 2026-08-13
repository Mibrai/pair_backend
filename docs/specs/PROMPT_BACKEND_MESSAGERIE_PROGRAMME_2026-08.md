# Demande backend — messagerie de programme

**Date** : 2026-08-13
**Demandeur** : chantier frontend Flutter (`pair_mobile`)
**Statut** : bloquant. Trois exigences produit sont **entièrement** côté serveur ; le
frontend ne peut rien en livrer tant que ces routes et ces champs n'existent pas.

---

## Pourquoi ce document existe

Trois demandes produit ont été formulées :

1. l'auteur d'un programme peut **diffuser un message de groupe** à tous ses participants,
   visible d'eux seuls ;
2. l'auteur choisit, **programme par programme**, s'il accepte de recevoir des messages de
   ses participants. S'il refuse, les participants sont en **lecture seule** sur le fil de
   diffusion ;
3. l'en-tête d'une conversation affiche le **programme**, l'**activité** et la **date du
   créneau** qui lient les deux personnes ; la conversation **se grise** quand cette date est
   passée.

La spec OpenAPI publique (`/v3/api-docs`, relevée le 2026-08-13) a été vérifiée route par
route. Verdict :

| Besoin | État actuel | Verdict |
|---|---|---|
| Diffusion de groupe | `/api/conversations` ne connaît que des conversations à deux : `ConversationSummaryDto.otherUser`, `CreateConversationRequest = { targetUserId, activityContextId }` | **Absent** |
| Autorisation par programme | Ni `ProgramDto` ni `UpdateProgramRequest` ne portent de champ | **Absent** |
| Contexte programme + date de créneau | `ConversationSummaryDto` / `ConversationDetailDto` n'exposent que `activityContextName` | **Partiel** — l'activité oui, le programme et la date non |

Le champ `type` existe déjà sur les deux DTO de conversation mais n'est pas documenté comme
énuméré ; s'il porte aujourd'hui une valeur unique (`DIRECT`), c'est le point d'extension
naturel.

---

## 1. Diffusion de groupe

### Modèle

Une conversation de type `PROGRAM_BROADCAST`, **une par programme**, créée à la demande
(première diffusion) plutôt qu'à la création du programme — inutile de peupler la base de
fils vides.

Membres = l'auteur du programme + tous les `UserProgram` de statut `ACTIVE`. L'appartenance
doit être **dérivée**, pas recopiée : un participant qui quitte le programme perd l'accès au
fil, y compris à l'historique, et un nouvel inscrit le gagne. Recopier la liste au moment de
l'envoi ferait diverger les deux dès la première inscription.

### Routes

```
POST   /api/programs/{programId}/broadcasts
       body : { "content": "…" }
       201 → MessageDto
       403 si l'appelant n'est pas l'auteur du programme
       404 si le programme n'existe pas

GET    /api/programs/{programId}/broadcasts?page=&size=
       200 → Page<MessageDto>
       403 si l'appelant n'est ni l'auteur ni un participant ACTIF
```

Alternative acceptable, et sans doute préférable : ne pas créer de routes parallèles mais
faire apparaître le fil de diffusion dans `GET /api/conversations` avec
`type: "PROGRAM_BROADCAST"`, et laisser `POST /api/conversations/{id}/messages` refuser en
403 l'envoi par un non-auteur. Le frontend a déjà toute la plomberie des conversations ; ce
chemin lui coûterait moins et vous éviterait un second modèle de messages.

**Dans les deux cas**, `ConversationSummaryDto.otherUser` n'a pas de sens pour un fil de
groupe. Il faut soit le rendre nullable et ajouter un `title` + `memberCount`, soit typer la
réponse. Un `otherUser` rempli avec l'auteur serait un piège : la liste de messagerie
afficherait « conversation avec X » pour un fil qui compte trente personnes.

### Notifications

Une diffusion doit produire une notification aux participants. Le catalogue de types du
frontend en compte une trentaine (`notification_pref_catalog.dart`) ; il faut un type
nouveau, par ex. `PROGRAM_BROADCAST`, avec sa route de navigation dans le payload —
autrement le tap sur la notification ne saura pas où aller.

---

## 2. Autorisation des messages, par programme

### Champ

Sur `ProgramDto`, `CreateProgramRequest` et `UpdateProgramRequest` :

```
allowParticipantMessages : boolean   // défaut : true
```

`true` par défaut, et c'est un choix : le produit met des gens en relation, un programme
muet par défaut prendrait tout le monde à contre-pied. L'auteur restreint, il n'ouvre pas.

### Conséquences serveur

Le champ ne doit **pas** être un simple drapeau d'affichage. Le refus s'applique côté
serveur, sinon il ne s'applique pas du tout :

- `POST /api/conversations` avec un `activityContextId` rattaché à un programme dont
  `allowParticipantMessages = false`, demandé par un participant vers l'auteur → **403**,
  code métier dédié (par ex. `PROGRAM_MESSAGES_DISABLED`) pour que le frontend puisse le
  traduire au lieu d'afficher un message serveur brut ;
- `POST /api/conversations/{id}/messages` sur un fil de diffusion par un non-auteur → **403**,
  même traitement ;
- l'auteur, lui, garde le droit d'écrire dans les deux sens, en toutes circonstances.

### Ce que fait le frontend une fois le champ livré

Un interrupteur dans l'écran d'édition du programme, l'action « Envoyer un message » masquée
côté participant, et le fil de diffusion rendu en lecture seule — composeur retiré, pas
seulement désactivé.

---

## 3. Contexte de conversation : programme, activité, date de créneau

### Champs demandés

Sur `ConversationSummaryDto` **et** `ConversationDetailDto` :

```
programId        : uuid    (nullable)
programTitle     : string  (nullable)
activityName     : string  (nullable)   // aujourd'hui activityContextName
scheduleId       : uuid    (nullable)
scheduleStartsAt : date-time (nullable) // heure UTC de la séance qui lie les deux personnes
scheduleEndsAt   : date-time (nullable)
```

`activityContextName` peut rester tel quel et être simplement doublé par `activityName` ;
l'important est que le **programme** et la **date** arrivent.

### Pourquoi la date, précisément

L'exigence est : *« quand la date du créneau concerné est dépassée, la messagerie se grise »*.
Le frontend sait griser — c'est déjà ce qu'il fait pour un créneau annulé
(`SlotCard`, opacité 0,55) et pour un programme terminé (`program_expiry.dart`). Ce qui lui
manque est uniquement **la date à comparer à maintenant**. Sans elle, la règle n'a aucun
déclencheur, et aucune approximation client n'est acceptable : deviner la séance à partir du
seul nom d'activité désignerait la mauvaise date dès qu'une personne suit deux programmes de
la même activité.

Préférer `scheduleEndsAt` à `scheduleStartsAt` pour la comparaison quand les deux existent :
une conversation ne doit pas se figer pendant la séance qu'elle sert à organiser.

### Nullabilité

Tous ces champs sont nullables et doivent le rester : une conversation peut naître hors de
tout programme (depuis un profil). Le frontend affiche alors l'en-tête sans contexte et ne
grise rien — ce qui est déjà son comportement.

---

## Ce que le frontend a livré en attendant

- L'activité, seul contexte aujourd'hui disponible (`activityContextName`), peut être posée
  dans l'en-tête dès maintenant ; le programme et la date attendent ce document.
- Le reste du lot produit est livré et ne dépend de rien : programmes expirés grisés et non
  rejoignables (`lib/features/programs/domain/program_expiry.dart`), voyant de
  géolocalisation fixe, adresses cliquables vers Google Maps
  (`lib/shared/widgets/address_link.dart`), ouverture sur la carte avec dézoom automatique
  jusqu'à la première activité visible.

## Ordre de livraison suggéré

1. **§3, les champs de contexte** — c'est une extension de DTO, sans nouvelle route ni
   nouveau modèle, et elle débloque à elle seule un écran entier.
2. **§2, le champ d'autorisation** — un booléen et trois refus serveur.
3. **§1, la diffusion** — le plus gros morceau, et le seul qui demande un modèle.

Chaque point est indépendant des deux autres : rien n'oblige à les livrer ensemble.
