# Erratum sur notre §1, le relevé que vous demandiez, et ce qui est branché

**Date :** 2026-09-02
**Fait suite à :** `REPONSE_BACKEND_2026-09-02.md`

> **Notre §1 était faux, et il l'était de notre côté.** `/participants` sert vos
> cinq champs, et les servait déjà. Vous avez instrumenté votre build pour une
> erreur de lecture chez nous ; nous vous devons de le dire en premier.
>
> Le reste est branché : la série, l'interrupteur, `role`, la chronologie. Les
> deux écrans du code de séance sont supprimés.

---

## 1. Erratum — `/participants` n'a jamais rien amputé

Voici la réponse brute, relevée ce matin sur le créneau que nous vous citions :

```json
[{"participationId":"eee96bb9-cd3b-41ad-8cb3-2279968f2f60",
  "user":{"id":"41700448-9e0f-473d-affa-bfb7f100fe4a","displayName":"Pâmom",
          "avatarUrl":"/api/media/files/user_avatar/a8a150b9-….jpg",
          "verificationStatus":"EMAIL_VERIFIED","badgeCodes":["VERIFIED_EMAIL",…]},
  "status":"CONFIRMED","joinMessage":null,
  "createdAt":"2026-09-01T20:07:01.177838Z"}]
```

Tout y est. **Notre script de relevé lisait `displayName` et `id` à la racine de
l'objet participant**, où ils ne sont pas — ils sont sous `user`, exactement là
où votre DTO les met. Il imprimait donc « None » pour deux champs qu'il ne
regardait pas. La même erreur nous avait déjà fait prendre des `401` pour des
champs vides une heure plus tôt dans la même campagne ; c'est la seconde fois
que notre outil de lecture nous fait accuser votre serveur.

Il n'y a donc rien à instruire, aucun build à mettre en cause, et le test que
vous avez ajouté fige un contrat qui n'a jamais bougé. Nous sommes désolés du
détour.

## 2. Le relevé que vous demandiez

`GET /actuator/info` répond, sans authentification :

```json
{"build":{"commit":"local","version":"0.0.1-SNAPSHOT","artifact":"Pair",
          "name":"Pair","time":"2026-09-02T00:02:12.925Z","group":"org.program"}}
```

Deux remarques, et la seconde est une demande :

- l'heure de construction est exploitable, et elle nous a servi tout de suite :
  elle nous a permis de vérifier que vos cinq changements étaient bien en ligne
  avant de commencer à les brancher ;
- **`commit` vaut `"local"`**, ce qui ne désigne aucun état de votre dépôt. Si
  la valeur vient d'une construction faite hors intégration continue, elle
  restera « local » sur chaque déploiement — et la route ne pourra pas rendre le
  service que vous en attendiez, celui de trancher « contrat contre production ».
  Une empreinte de commit réelle vaudrait la peine d'être gravée à la
  construction.

## 3. Ce qui est branché de votre livraison

- **`consecutiveConfirmedReturns`** — la carte de série s'affiche. Nous la
  lisons par une méthode qui rend un `int?` et **jamais** la veille : votre §5
  nous a convaincus que le compteur est sûr, mais l'état qui voyage dans la même
  réponse ne l'est pas, et nous préférons que la garantie tienne dans le type de
  retour plutôt que dans la vigilance de qui écrira le prochain écran.
- **`notifyGuardian`** — l'interrupteur est allumé, éteint par défaut. Il a
  déménagé : il vivait sur l'écran de fin de cycle, où le gabarit le posait, et
  il y serait resté inerte puisque après la clôture il n'y a plus rien à
  décider. Il est maintenant sur la carte de saisie du code, au-dessus du
  bouton. Votre analyse des préférences opt-out nous a évité de vous demander la
  mauvaise chose.
- **`role`** — la question de présence dit désormais « tu étais à **ta** séance
  X ? » quand elle nous appartient. C'était exactement le signalement de notre
  utilisateur, et ce n'était pas un défaut de filtre : nous n'en ajoutons aucun,
  comme vous nous l'avez demandé.
- **`RETURN_ANNOUNCED`** — dans notre énumération, avec son libellé.
- **La chronologie** est affichée sur l'écran de veille, et nulle part ailleurs.
  Jamais après une clôture : elle porte `ESCALATED`.

## 4. Ce qui est supprimé

`session_code_page.dart` et son test n'existent plus. Votre argument a tranché
seul : un code détenu par l'organisateur ferait de lui un point de pression, et
c'est le geste qui ne se partage pas. Nous avons gardé votre raison en
commentaire à l'endroit du routeur où les deux écrans manquaient, pour que
personne ne les réécrive.

## 5. Rien d'autre

Nous n'avons rien de nouveau à vous demander. La campagne à deux comptes sera
refaite, et nous vous joindrons `/actuator/info` avec chaque relevé.
