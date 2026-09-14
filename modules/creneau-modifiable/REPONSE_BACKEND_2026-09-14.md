# `SlotFeedItemDto.city` : la ville saisie, jamais déduite

**Date :** 2026-09-14
**Module :** [`creneau-modifiable/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md) — P-MU-28, livraison P2c (D8, option B)

> **En bref.**
>
> - **§2.1 — livré.** `SlotFeedItemDto.city` : chaîne nullable, même valeur que `PublicSlotView.city`,
>   servie par `/slots/{id}`, `/slots/feed`, `/slots/mine` et la réponse de `POST /quick-slots`.
> - **§2.2 — saisie seulement.** Le serveur ne déduit rien, ni de l'adresse ni des coordonnées : aucun
>   géocodage n'est branché.
> - **§2.3 — aucun contenu publié ne porte une ville et une récurrence ensemble.** `PublicSlotView`
>   n'a jamais servi de récurrence.

---

## 1. Le relevé, vérifié

Exact : `schedules.city` existe, s'écrit par `CreateScheduleRequest`, `UpdateScheduleRequest` et
`QuickSlotRequest`, et se lit par la page publique, l'affiche, la carte-souvenir, le lien de sûreté,
la page publique de veille et l'agenda (`.ics`). Seul `SlotFeedItemDto` ne la portait pas.

## 2. La demande

### 2.1 Le champ

`city` est ajouté **en fin de `SlotFeedItemDto`**. Il vaut :

- la ville telle qu'elle a été saisie, espaces de bord retirés et assainie comme tout texte reçu ;
- **`null`** quand aucune ville n'a été saisie, **et quand elle a été retirée** par un `PUT` avec
  `"city": ""` : nous rendons `null` et jamais une chaîne vide. La base peut garder `""` dans ce
  cas ; le DTO la rend `null`.

**Servie quel que soit le lieu, privé compris.** `displayAddress`, `lat` et `lng` restent soumis à
`SlotAddressVisibility`. La ville, non : c'est le grain de lieu que `Schedule` déclare diffusable sans
condition, et que la page publique sert déjà. Une ville ne situe personne.

### 2.2 Comment elle est remplie

**Seulement par saisie.** `Schedule.city` le dit depuis sa création : « nullable et jamais devinée ».
`MapService.reverseGeocode` est un bouchon, et nous ne le brancherons pas pour ça : une ville inventée
vaut moins qu'une ville absente. Tant que vos formulaires n'envoient pas `city`, les nouveaux créneaux
rendent `null`. Votre ligne météo reste donc invisible sur eux, et c'est le comportement juste.

Les trois routes l'acceptent déjà :

| Route | `city` |
|---|---|
| `POST /api/quick-slots` | facultative, 120 caractères |
| `POST /api/programs/{programId}/schedules` | facultative, 120 caractères |
| `PUT /api/programs/{programId}/schedules/{scheduleId}` | absente ou `null` : inchangée ; `""` : retirée |

### 2.3 Lieu et récurrence

`PublicSlotView` (page `/s/{id}` et aperçus) porte `city` et **ne porte aucune règle de récurrence** :
seulement `startsAt` et la fin de **la prochaine séance**. Une date isolée n'est pas un jour de semaine
récurrent. `PublicProgramView` non plus ne sert ni `recurrenceRule` ni `preferredDays`.

`SlotFeedItemDto`, lui, porte `city` **et** `recurrenceRule`, mais ce n'est pas un contenu publié :
les routes sont authentifiées, bornées par le blocage, et servaient déjà `lat`/`lng` avec la règle pour
un lieu public. La ville n'y ajoute rien de plus précis.

**Si un jour la page publique d'un créneau récurrent devait dire « chaque mardi »**, il faudra y retirer
la ville, ou ne pas écrire le jour. Nous le noterons dans la fiche le jour où la demande viendra.

## 3. Ce que nous vous suggérons

- `SlotDetailPage.weatherCityOf` peut lire `slot.city` tel quel. `null` ou absent : pas de ligne.
- Pour les formulaires, envoyer `city` comme une saisie libre. Ne pas l'extraire de l'adresse côté
  app, pour la même raison que nous ne la déduisons pas.

## 4. Vérification

- `QuickSlotIntegrationTest.laFicheDuCreneau_rendLaVilleSaisie_memeSurUnLieuPrive` (nouveau) : un
  créneau sur un lieu **privé** rend `city = "Strasbourg"` et `displayAddress = null`, à la création
  comme sur `GET /api/slots/{id}`. Après un `PUT` avec `"city": ""`, la fiche rend `city = null`.
- **Votre relevé :** `/v3/api-docs`, schéma `SlotFeedItemDto`, propriété `city`. Puis la lecture
  réelle d'un créneau créé avec une ville. Les 3 séances sur 42 du compte de test qui en portent une
  la rendent dès le déploiement.
