# Demande backend — la carte-souvenir de créneau (`slot_recaps`)

> Contexte : couche d'**agrégation et de présentation** au-dessus de données que
> le produit possède déjà (`Attendance` et sa confirmation « j'y étais »,
> `Schedule`, `UserActivity` → `Activity` → `Category`, service de stockage
> média). **Aucun nouveau domaine métier**, aucune donnée nouvelle : on
> rassemble et on rend visible ce qui existe.
>
> État vérifié le 2026-08-14 contre `/v3/api-docs` : **aucune route `recap` ni
> `vibe` n'existe**. Les 127 chemins publiés ne contiennent rien de ce document.
> Le client a donc écrit l'intégralité de la fonctionnalité **derrière un
> drapeau** (`FeatureFlags.slotRecaps = false`, cf. §8) : le code est en place,
> testé, et s'allume en changeant un booléen le jour où ces routes répondent.

---

## 0. Ce que le client fait aujourd'hui sans cette livraison

Rien n'est affiché et rien n'est appelé. Le drapeau coupe :

- les puces d'ambiance après « Oui, j'y étais » ;
- la carte-souvenir dans le fil, sur la page programme et sur le profil d'hôte ;
- les commandes de l'hôte (mot, visibilité).

Ce n'est pas une version dégradée à signaler à l'utilisateur : c'est une
fonctionnalité **absente**. Elle le reste tant que ce document n'est pas livré.

Par prudence, le client traite en plus tout `404`/`501` sur ces routes comme
« pas de carte » plutôt que comme une erreur — allumer le drapeau contre un
serveur partiellement livré ne peut donc pas casser la boucle de présence
existante. Ce filet ne remplace pas la livraison, il évite qu'une livraison
partielle se voie comme une panne.

---

## 1. Le principe qui contraint tout le reste

> **La carte porte sur le moment collectif, jamais sur les individus qui y
> étaient.**

C'est ce qui permet une trace publique attractive sans réintroduire la
comparaison sociale que le produit refuse depuis sa conception. Le but unique
de la carte : **donner envie de rejoindre le prochain créneau**.

Interdictions à tenir **côté serveur**, pas seulement côté écran — un champ
qui n'existe pas au contrat ne peut pas être affiché par erreur demain :

- ❌ aucune donnée de performance (distance, durée d'effort, vitesse, calories,
  répétitions) ;
- ❌ aucune note, aucune étoile, sur un créneau, un hôte ou un participant ;
- ❌ aucun classement, aucun palmarès, aucun tri « le plus populaire » ;
- ❌ aucun compteur de likes ni réaction publique cumulée ;
- ❌ aucun participant nommé ou photographié sans consentement explicite ;
- ❌ aucun vocabulaire compétitif dans les libellés ou les codes d'erreur
  (« score », « niveau atteint », « record », « performance »).

Merci de ne pas « compléter » le DTO de §5 avec un champ qui violerait cette
liste, même s'il paraît anodin : le client ne l'affichera pas, et sa seule
présence au contrat le fera réapparaître dans une demande ultérieure.

---

## 2. Migration `V50__slot_recap.sql`

Flyway est à **V49**. Trois tables, aucune pour les photos (voir §2.1).

```sql
-- La carte elle-même : une par créneau, créée à la demande
CREATE TABLE slot_recaps (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schedule_id        UUID NOT NULL UNIQUE REFERENCES schedules(id) ON DELETE CASCADE,
    visibility         VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
    host_note          VARCHAR(400),
    attendee_count     INTEGER NOT NULL DEFAULT 0,
    published_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_recaps_schedule   ON slot_recaps(schedule_id);
CREATE INDEX idx_recaps_visibility ON slot_recaps(visibility, published_at DESC);

-- Les mots d'ambiance choisis par chaque participant
CREATE TABLE recap_vibe_votes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recap_id    UUID NOT NULL REFERENCES slot_recaps(id) ON DELETE CASCADE,
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    vibe        VARCHAR(30) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_vibe_vote UNIQUE (recap_id, user_id, vibe)
);

CREATE INDEX idx_vibe_recap ON recap_vibe_votes(recap_id);

-- Consentement à apparaître : opt-in explicite, jamais implicite
CREATE TABLE recap_participant_consents (
    recap_id      UUID NOT NULL REFERENCES slot_recaps(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    show_identity BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (recap_id, user_id)
);
```

```java
public enum RecapVisibility {
    PRIVATE,        // visible uniquement des participants (défaut)
    PARTICIPANTS,   // idem PRIVATE — conservé pour clarté sémantique future
    PUBLIC          // visible de tous dans l'app
}
```

Le client accepte les trois valeurs et traite `PARTICIPANTS` comme `PRIVATE`,
conformément au commentaire ci-dessus.

### 2.1 Pas de seconde table pour les photos, ni de second chemin d'upload

Réutiliser le mécanisme de souvenir de créneau (`attendances.memory_photo_url`
+ `memory_is_public`) s'il est déjà en place ; sinon s'appuyer sur le service de
stockage média existant. **Ne pas créer un second chemin d'upload** : le client
en a déjà un qui fonctionne (multipart, champ `file`), et le doubler nous
ramènerait les incidents média d'août (`REPONSE_BACKEND_MEDIA_FILES_2026-08.md`).

Rappel utile côté client : les URL servies sous `/api/media/files/**` sont
authentifiées et chargées via Dio, pas via un `Bearer` figé. Servir les
`photoUrls` sous cette forme convient ; les servir sous une forme publique
non authentifiée convient aussi. Mélanger les deux dans une même liste convient
également — le client tranche URL par URL.

---

## 3. `SlotVibe` — vocabulaire d'ambiance, liste strictement fermée

```java
public enum SlotVibe {
    RELAXED,           // détendu
    ENERGETIC,         // énergique
    FRIENDLY,          // convivial
    TECHNICAL,         // technique
    BEGINNER_FRIENDLY, // accessible aux débutants
    GOOD_LAUGH,        // bonne humeur
    FOCUSED,           // concentré
    OUTDOORS           // en plein air
}
```

**Jamais de saisie libre.** Trois raisons, toutes structurantes :

1. l'agrégation multilingue devient triviale — une clé de traduction par valeur,
   dans les trois catalogues FR/EN/DE, et le test de complétude des trois langues
   la couvre (déjà écrit côté client, cf. §8) ;
2. aucun contenu inapproprié à modérer ;
3. la contribution reste **un tap**, pas une rédaction.

Le serveur n'a donc **aucun libellé à renvoyer** : il renvoie la valeur d'enum,
le client l'affiche dans la langue de l'utilisateur. Merci de rejeter en `422`
toute valeur hors de cette liste plutôt que de la stocker telle quelle — le
client ignore les valeurs inconnues à la lecture, mais une valeur parasite en
base fausserait `topVibes` sans que rien ne le signale.

---

## 4. Règles métier — `SlotRecapService`

**Création de la carte.** À la **première contribution** (premier vote
d'ambiance, premier mot d'hôte, ou première photo partagée). Ne pas créer de
carte vide pour tous les créneaux passés : ce serait du bruit en base sans
valeur, et le client n'affiche jamais une carte sans contenu.

**Qui peut contribuer.** Uniquement un utilisateur ayant une `Attendance` avec
`was_present = true` sur ce créneau. **Réutiliser la vérification déjà en place
pour la boucle de recommandation** — ne pas dupliquer la logique.

**Fenêtre de contribution.** Ouverte à la fin du créneau, fermée **7 jours
après**. Au-delà, la carte se fige. Deux raisons : qu'une carte ne change pas
des mois plus tard sous les yeux de quelqu'un qui l'a partagée, et une légère
incitation à contribuer vite.

**Qui peut publier.** Seul **l'hôte** passe une carte de `PRIVATE` à `PUBLIC`.
C'est lui qui porte le créneau et qui en assume la représentation publique.

**Garde-fou de publication.** Une carte ne devient `PUBLIC` que si **au moins un
participant autre que l'hôte** a confirmé sa présence. Sans cela, un hôte
pourrait publier une carte laissant croire qu'un créneau a rassemblé du monde
alors qu'il était seul. La preuve sociale doit rester vraie, sinon elle se
retourne contre le produit.

> Ce refus a besoin d'un **code d'erreur stable** — voir §5.3. Le client en fait
> une phrase compréhensible (« Attends qu'au moins une autre personne confirme
> sa présence pour rendre cette carte publique. »), pas un bandeau d'erreur brut.

**Consentement des participants.** `show_identity = false` par défaut. Un
participant qui ne s'est pas explicitement déclaré est **compté dans le total**
mais **jamais nommé ni montré**. Ses photos ne sont incluses que si
`memory_is_public = true`. Il peut retirer son consentement **à tout moment, y
compris après publication** — la carte se régénère alors sans lui.

---

## 5. Endpoints

Toutes les routes sont authentifiées et vivent sous `/api`, comme le reste.
Corps de requête en **camelCase** (règle transverse, cf. l'incident
`userProgramId` du `POST /programs/{id}/leave`).

### 5.1 Contribution — participants présents uniquement

```http
POST   /api/slots/{scheduleId}/recap/vibes      { "vibes": ["RELAXED","FRIENDLY"] }
DELETE /api/slots/{scheduleId}/recap/vibes
PATCH  /api/slots/{scheduleId}/recap/consent    { "showIdentity": true }
```

`POST .../vibes` **remplace** la contribution de l'appelant (ce n'est pas un
ajout incrémental) : le client envoie l'ensemble de la sélection à chaque tap.
**Maximum 2 valeurs**, `422` au-delà — au-delà de deux, l'agrégation perd son
sens. Un tableau vide est équivalent au `DELETE`.

Les trois routes créent la carte si elle n'existe pas encore (§4).

### 5.2 Hôte uniquement

```http
PATCH  /api/slots/{scheduleId}/recap/note       { "note": "…" }        // max 400, sanitizé
PATCH  /api/slots/{scheduleId}/recap/visibility { "visibility": "PUBLIC" }
```

`note` doit être **sanitizée** côté serveur : elle est rendue telle quelle dans
une carte publique.

### 5.3 Codes d'erreur attendus

Le client traduit sur le `code`, jamais sur le `message` — c'est ce qui permet
d'afficher la phrase dans la langue de l'utilisateur. Merci de fournir ces
codes stables :

| Situation | Statut | `code` |
|---|---|---|
| Publication refusée, aucun participant non-hôte confirmé | `409` | `RECAP_NEEDS_ATTENDEE` |
| Fenêtre de 7 jours fermée | `409` | `RECAP_WINDOW_CLOSED` |
| L'appelant n'était pas présent (`was_present != true`) | `403` | `RECAP_NOT_ATTENDEE` |
| L'appelant n'est pas l'hôte (note, visibilité) | `403` | `RECAP_NOT_HOST` |
| Plus de 2 vibes, ou valeur hors enum | `422` | `RECAP_INVALID_VIBES` |

### 5.4 Lecture

```http
GET /api/slots/{scheduleId}/recap                   // authentifié, respecte la visibilité
GET /api/recaps/feed?lat=&lng=&radiusMeters=        // cartes publiques autour de moi
GET /api/recaps/mine                                // cartes des créneaux auxquels j'ai participé
```

`GET /api/recaps/feed` : mêmes paramètres que `/api/slots/feed`, pour que le
client réutilise sa position et son rayon partagé sans réglage supplémentaire.
Une enveloppe `Page<T>` Spring est acceptée autant qu'un tableau nu — le client
lit les deux.

**Filtrage de lecture — impératif.** Retourner **`404`, jamais `403`**, sur
`GET .../recap` si : la carte est `PRIVATE` et le demandeur n'y a pas participé ;
l'hôte est inactif ; le programme n'est plus public. Même logique que les
endpoints publics existants — **ne pas révéler l'existence d'une ressource
inaccessible**.

---

## 6. DTO de lecture

```java
public record SlotRecapDto(
    UUID scheduleId,
    String programTitle,
    String activityName,
    String categoryName,
    String categoryColorRamp,
    Instant slotStartedAt,
    String placeName,
    String cityLabel,                      // ville, jamais l'adresse exacte
    int attendeeCount,
    List<VibeCountDto> topVibes,           // 3 max, triés par nombre de votes décroissant
    List<String> photoUrls,                // 3 max, souvenirs publics uniquement
    String hostNote,
    HostSummaryDto host,                   // nom + avatar + badge vérifié
    List<UserPublicDto> visibleAttendees,  // uniquement ceux ayant consenti
    NextSlotDto nextSlot,                  // null s'il n'y a pas de prochain créneau
    String visibility,
    boolean canContribute,                 // la fenêtre de 7 jours est-elle ouverte pour moi
    List<String> myVibes                   // ce que j'ai déjà voté
) {}

public record VibeCountDto(String vibe, int count) {}

public record NextSlotDto(
    UUID scheduleId,
    Instant startsAt,
    String placeName,
    int participantCount,
    Integer maxParticipants,
    boolean alreadyJoined
) {}
```

Trois remarques, par ordre d'importance.

**`nextSlot` est le champ le plus important du DTO.** C'est lui qui convertit un
lecteur en participant, et le client en fait le bouton pleine largeur en bas de
carte. Le résoudre en cherchant le prochain créneau **`OPEN` et à venir** du
**même programme**. S'il n'y en a pas : `null`, franchement — le client propose
alors l'abonnement au programme plutôt qu'un cul-de-sac.

**`cityLabel`, jamais l'adresse.** Le lieu affiché suit les règles de visibilité
déjà en vigueur : nom du lieu et ville toujours ; **adresse exacte jamais**,
sauf si `place_type = PUBLIC` ou si l'hôte a coché `show_exact_address`. Ne
jamais reconstituer une position à partir d'autres champs — pas de `lat`/`lng`
dans ce DTO, et c'est délibéré.

**Les trois plafonds sont au serveur.** `topVibes` ≤ 3 trié décroissant,
`photoUrls` ≤ 3, `visibleAttendees` limité aux consentants. Le client les
retronque par sécurité, mais un plafond appliqué seulement côté client
laisserait passer des données que l'utilisateur n'a pas accepté de publier.

---

## 7. Ordre suggéré, et ce qui bloque quoi

```
1. Migration V50 + entités SlotRecap, RecapVibeVote, RecapParticipantConsent
2. Enum SlotVibe
3. SlotRecapService : création à la première contribution, vérification de
   présence (réutiliser l'existant), fenêtre de 7 jours
4. Endpoints de contribution (vibes, consent, note)      ← débloque les puces
5. Endpoint de lecture + résolution de nextSlot + visibilité  ← débloque la carte
6. Garde-fou de publication
7. Endpoint /api/recaps/feed géolocalisé                 ← débloque le fil
```

Les étapes 4 et 5 suffisent à allumer l'essentiel côté client. `/recaps/feed`
(7) n'ouvre que le troisième emplacement d'affichage.

---

## 8. Ce que le client a déjà écrit, et comment vérifier la livraison

Côté client, tout est en place et testé :

- modèles tolérants (`lib/models/recap_models.dart`) — un champ manquant, nul ou
  inconnu ne fait jamais échouer une liste ;
- dépôt et providers (`lib/features/recaps/data/`) ;
- puces d'ambiance **dans la feuille de confirmation existante**, sans étape
  supplémentaire, « Passer » toujours visible ;
- `SlotRecapCard` + commandes de l'hôte ;
- clés i18n des 8 valeurs de `SlotVibe` et pluriels de « N personnes s'y sont
  retrouvées » dans les **trois** catalogues, verrouillés par
  `test/l10n_completeness_test.dart` ;
- tests de contrat sur les plafonds et le tri.

Pour allumer : `FeatureFlags.slotRecaps = true`
(`lib/core/config/feature_flags.dart`), qui documente cette dépendance.

Les tests côté serveur que le client considère comme la définition du « livré » :

```
SlotRecapServiceTest
- un non-participant ne peut pas voter d'ambiance
- un participant avec was_present=false ne peut pas contribuer
- la contribution est refusée au-delà de 7 jours après le créneau
- maximum 2 vibes par utilisateur et par créneau
- la carte se crée à la première contribution, pas avant
- seul l'hôte peut modifier la note et la visibilité
- publication refusée si aucun participant non-hôte n'a confirmé
- un participant sans consentement est compté mais jamais nommé
- retirer son consentement après publication le retire de la carte

SlotRecapVisibilityTest
- GET recap PRIVATE par un non-participant retourne 404 (pas 403)
- une carte dont l'hôte est inactif n'apparaît jamais dans le feed
- l'adresse exacte n'est jamais présente pour un lieu privé non partagé
- aucune photo d'un participant sans memory_is_public n'est incluse

SlotRecapDtoTest
- nextSlot est null si aucun créneau futur OPEN sur ce programme
- topVibes est limité à 3 et trié par nombre de votes décroissant
- photoUrls est limité à 3
```

---

## 9. Deux sujets volontairement hors périmètre

**L'image PNG partageable, générée côté serveur.** À **ne pas** implémenter dans
un premier temps : elle demande une bibliothèque de rendu (Thymeleaf + navigateur
sans tête, ou composition d'image Java), ce qui alourdit l'image Docker et la
mémoire — or le service a déjà rencontré un `Deploy Ran Out of Memory` avec le
modèle ONNX. Si le besoin se confirme, la voie sobre sera une page HTML avec
métadonnées OpenGraph, laissant les réseaux sociaux générer eux-mêmes l'aperçu.

**Ce qu'il ne faudra jamais ajouter**, à conserver comme garde-fou pour toute
évolution future de la carte :

| Tentation | Pourquoi la refuser |
|---|---|
| Notes en étoiles sur le créneau | Réintroduit la notation, incohérent avec les avis limités aux programmes |
| Compteur de likes sur la carte | Transforme la carte en concours de popularité |
| « Créneau le plus populaire de la semaine » | Classement déguisé entre hôtes |
| Statistiques d'effort (durée, distance) | meetDo n'est pas un tracker — c'est la frontière avec Strava |
| Commentaires publics sous la carte | Ouvre une surface de modération que le produit n'a pas les moyens d'assumer |
| Partage automatique sans action de l'hôte | Viole le principe de consentement explicite |

Le test à appliquer à toute évolution : **est-ce que ça aide quelqu'un à
rejoindre le prochain créneau, ou est-ce que ça encourage à regarder plus
longtemps ?** Seule la première réponse justifie l'ajout.
