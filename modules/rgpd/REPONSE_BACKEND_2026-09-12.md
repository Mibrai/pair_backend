# `DELETE /gdpr/delete-account` va enfin faire quelque chose — et votre écran doit déconnecter, puis dire la vérité

**Date :** 2026-09-12
**Module :** [`rgpd/`](.) — nouveau module, ouvert par ce document
**Origine :** audit du 10/09 — fiche **P-BL-01** du
`audit/PLAN_BACKEND_LOGIQUE_METIER_2026-09-11.md`, **Lot 0** (sous 48 h).
**Décision de cadrage :** `audit/DECISIONS_BACKEND_2026-09-12.md` § `P-BL/D2` — **option 1**
retenue, mais **l'avis juridique manque** (§3).

> **Un aveu, une livraison, et un texte à réécrire.**
>
> - **§0 — nous ne déploierons pas ce correctif seul**, et la raison porte sur les « 30 jours » de
>   votre écran : la purge compte aujourd'hui depuis la **dernière connexion**, une date qui peut
>   avoir des mois. À lire avant le reste.
> - **§1 — la route que votre écran de suppression appelle ne faisait rien.** Elle rendait `204` et
>   retournait, sur trois lignes de commentaires. Depuis qu'elle est publiée côté app, **chaque
>   personne qui a supprimé son compte a vu un écran de confirmation et gardé son compte.** C'est
>   notre défaut, entièrement.
> - **§2 — c'est corrigé au Lot 0, sans aucun changement de contrat.** `204` sans corps, comme
>   avant. Votre app 1.1.0+16 devient conforme à sa propre documentation **sans nouvelle version**.
>   Une seule chose à vérifier chez vous : que l'écran **déconnecte** après le `204`.
> - **§3 — votre texte, en revanche, promet deux choses que personne n'a tranchées** : « anonymisé
>   au bout de 30 jours » et l'idée qu'on puisse changer d'avis. Le délai et la réversibilité
>   attendent le **juridique**. Nous vous disons ce qui est vrai aujourd'hui, pour que votre écran
>   cesse d'affirmer ce qui ne l'est pas.
> - **§4 — ce que nous ne promettons pas.**

---

## 0. Une condition de mise en production, chez nous, qui touche ce que votre écran peut promettre

**Nous ne déploierons pas P-BL-01 seul.** La raison vous concerne, parce qu'elle porte sur les
« 30 jours » que votre écran annonce.

`GdprPurgeJob` sélectionne les comptes à anonymiser par
`is_active = false AND last_active_at < now() - 30 jours`. Or **`last_active_at` n'est écrit qu'à la
connexion** — et avec une session de 30 jours glissants, quelqu'un peut rester connecté des mois
sans jamais repasser par le login. Sa date est donc périmée.

Conséquence si nous branchions la route sans rien d'autre : une personne qui demande la suppression
de son compte aujourd'hui verrait **son compte anonymisé dès la nuit suivante**, sans aucun des
30 jours annoncés. Le correctif du Lot 0, pris seul, remplacerait un défaut qui ne supprimait rien
par un défaut qui supprime trop vite.

**Ce qui a été décidé le 2026-09-12** (`audit/DECISIONS_BACKEND_2026-09-12.md` §5.3) :

> **La purge ne compte ses 30 jours que depuis `deactivated_at`, et ignore toute ligne qui n'en
> porte pas. Et P-BL-01 ne part pas en production sans ce volet — ou avec la purge éteinte d'ici
> là.**

**Ce que cela veut dire pour vous, concrètement :**

- **la désactivation immédiate arrive bien au Lot 0** (§2) — c'est la moitié de votre texte qui
  devient vraie, et elle le devient sans condition ;
- **le délai de 30 jours, lui, n'est pas encore un fait**. Il le sera quand `deactivated_at`
  existera, et sa **longueur** attend toujours le juridique (§3). D'ici là, votre écran ne doit pas
  le chiffrer ;
- **rien ne sera anonymisé prématurément** pendant la fenêtre où la purge est éteinte : c'est
  précisément l'objet du garde-fou.

---

## 1. Le défaut, dit en clair

`GdprController.requestAccountDeletion` contenait **trois commentaires** puis
`return ResponseEntity.status(HttpStatus.NO_CONTENT).build();`. Rien d'autre. Pas de désactivation,
pas de trace d'audit, rien en base.

La désactivation n'était branchée que sur **`DELETE /users/me`** — et **aucun écran de l'app ne
l'appelle**. Votre `ApiConstants.gdprDeleteAccount = '/gdpr/delete-account'` pointe sur
`UserRepository.requestAccountDeletion`, dont la documentation promet « désactivé immédiatement,
anonymisé au bout de 30 jours ».

Vous appeliez la bonne route. C'est la route qui était vide.

**Deux conséquences que nous assumons :**

1. **C'est une infraction à l'article 17 à chaque tap.** Le délai de réponse d'un responsable de
   traitement n'est pas « jamais ».
2. **Les demandes passées sont invisibles en base**, puisque rien n'était écrit — pas même un audit.
   Nous les cherchons dans les journaux HTTP Railway, par un runbook
   ([`docs/runbooks/RUNBOOK_DEMANDES_SUPPRESSION_PERDUES_2026-09-12.md`](../../docs/runbooks/RUNBOOK_DEMANDES_SUPPRESSION_PERDUES_2026-09-12.md)),
   en recoupant les `204` avec les comptes dont `last_active_at` s'arrête dans la minute de l'appel.
   C'est un **faisceau, pas une preuve**, et la fenêtre de rétention des journaux le borne. La
   communication aux personnes concernées est **soumise au juridique** ; elle ne partira pas de
   notre initiative.

---

## 2. Ce que le serveur fait à partir de ce déploiement

| Geste | Avant | Après |
|---|---|---|
| `DELETE /api/gdpr/delete-account`, authentifié | `204`, **aucun effet** | `204`, et le compte est `is_active = false` |
| la connexion suivante | fonctionnait | refusée (`AuthService` filtre `isActive`) |
| `POST /api/auth/refresh` avec l'ancien jeton | — | refusé |
| le compte dans le fil et les recherches | présent | disparaît |
| la trace de la demande | aucune | une ligne `audit_logs` de type **`GDPR_DELETE_REQUEST`**, horodatée |
| **un second appel** sur un compte déjà inactif | `404` | **`204`** — la route devient **idempotente** |
| `DELETE /users/me` | désactivait | fait exactement la même chose, audit compris : **les deux routes sont indiscernables** |

**Contrat inchangé : `204`, sans corps.** Il n'y a rien à mettre à jour dans vos modèles, et aucune
nouvelle version d'app n'est requise.

### 2.1 L'idempotence, et pourquoi c'est pour vous

Le passage de `404` à `204` sur un compte déjà inactif est là **pour votre couche réseau** : si
votre appel se perd après que le serveur a traité la demande, **vous pouvez rejouer sans traiter un
`404` comme un échec**. C'est le même raisonnement que celui qui a conduit à la rotation tolérante
du jeton de rafraîchissement (`P-BS/D4`, module [`session/`](../session)) : une réponse perdue ne
doit pas produire une erreur visible par la personne.

### 2.2 La vérification que nous vous demandons : déconnecter après le `204`

**C'est la seule chose que ce document attende de vous, et elle n'est pas cosmétique.**

Jusqu'ici, l'écran pouvait afficher sa confirmation et laisser la session ouverte : le compte
restait actif, et l'app continuait de fonctionner. **Ce n'est plus vrai.** Après ce déploiement, un
compte désactivé qui garde sa session en mémoire va, au premier appel authentifié, rencontrer un
refus — et selon votre garde des `401`, cela peut produire une tentative de rafraîchissement qui
échoue, ou un écran d'erreur, là où la personne attend un écran de départ paisible.

**Donc** : après le `204`, l'écran de suppression **déconnecte** — purge des jetons, purge du
trousseau, retour à l'écran de connexion. Le geste est un adieu, il doit ressembler à un adieu.

> Deux points d'appui, dans vos propres notes : **P-MS-05** (« la déconnexion laisse les codes de
> retour, le code de contrainte et les veilles refermées dans le trousseau ») et **P-MS-04**
> (« une session refusée par le serveur ne désenregistre pas le jeton push »). Une suppression de
> compte est le pire endroit pour laisser traîner l'un ou l'autre : le jeton push d'un compte
> supprimé qui reste enregistré fera arriver les notifications de quelqu'un d'autre sur cet
> appareil — c'est exactement **P-BL-12**.

---

## 3. Votre texte : ce qui est vrai aujourd'hui, et ce qui attend une décision

Votre documentation et votre écran promettent « **désactivé immédiatement, anonymisé au bout de 30
jours** ». La première moitié devient vraie avec ce déploiement. **La seconde n'est tranchée par
personne.**

`P-BL/D2` retient l'option 1 — `CASCADE` sur les lignes rattachées, et un délai de 30 jours comptés
**depuis la demande** et non depuis la dernière activité. Mais la décision porte la mention « à
faire valider », et le plan nomme qui doit valider : **le juridique RGPD**, sur le délai, sur la
réversibilité et sur le contenu. **Cet avis n'a pas été donné.**

Le garde-fou du §0 règle le **point de départ** du décompte : depuis `deactivated_at`, et jamais
depuis une date de dernière connexion périmée. Il ne règle **ni la longueur du délai, ni la
réversibilité** — les deux choses que votre texte affirme aujourd'hui.

### 3.1 Ce qui est vrai, et que votre texte peut dire

| Affirmation | Vraie aujourd'hui ? |
|---|---|
| « votre compte est désactivé immédiatement » | **oui**, à partir de ce déploiement |
| « vous ne pourrez plus vous connecter » | **oui** |
| « votre profil disparaît du fil et des recherches » | **oui** |
| « votre demande est enregistrée » | **oui** — ligne `audit_logs`, horodatée |

### 3.2 Ce que votre texte ne doit **pas** dire

| Affirmation | Pourquoi elle est fausse ou prématurée |
|---|---|
| « **vous pouvez encore changer d'avis** », « réversible pendant 30 jours » | **La rétractation n'existe pas.** Un compte désactivé ne peut plus se connecter : il n'y a aucun chemin pour revenir. La décision dit : *« soit on l'écrit (réactivation par e-mail pendant 30 jours), soit on raccourcit le délai »* — et rien n'a été écrit ni raccourci |
| « **anonymisé au bout de 30 jours** » | Le délai et son point de départ attendent le juridique (§0 en règle seulement le point de départ **technique**). Et la purge elle-même est **P-BL-03**, qui n'est pas au Lot 0 : aujourd'hui, elle **échoue** sur quelqu'un qui a déjà rejoint un créneau — et si on la laissait passer sans le garde-fou du §0, elle frapperait **trop tôt** au lieu d'échouer |
| « vos données sont supprimées » | Non : elles sont **conservées**, compte désactivé, jusqu'à la purge. Ce n'est pas la même promesse |
| « vos créneaux sont annulés », « vos inscrits sont prévenus » | **P-BL-18**, hors Lot 0. Aujourd'hui, désactiver son compte **laisse ses créneaux et ses inscrits en plan** |

### 3.3 Ce que nous suggérons, sans décider à votre place

Un texte qui n'affirme que §3.1, et qui renvoie le reste à une phrase non chiffrée du genre « vos
données seront supprimées par la suite, selon notre politique de confidentialité » — **jusqu'à ce
que le juridique ait tranché**. C'est moins engageant que ce que vous affichez aujourd'hui, et c'est
précisément la raison de le faire : ce que vous affichez aujourd'hui était faux sur les deux
moitiés, et il n'en reste qu'une à corriger.

**Nous vous écrirons à nouveau dans ce module dès que `P-BL/D2` sera tranchée**, avec le délai et la
réversibilité réellement retenus. C'est le moment où votre texte deviendra définitif, pas avant.

---

## 4. Ce que nous ne promettons pas

- **Pas de purge, pas d'anonymisation au Lot 0.** Seulement la désactivation et la trace. La purge
  est **P-BL-03**, et elle attend `P-BL/D2`.
- **Pas de colonne `deactivated_at` au Lot 0.** Elle arrive avec P-BL-03, et le §0 explique
  pourquoi nous ne déploierons pas la route sans elle (ou sans éteindre la purge). En Lot 0, c'est
  la ligne d'audit qui porte la date de la demande. Vous n'avez rien à lire de cela : ce n'est pas
  au contrat, et ce ne le sera pas.
- **Pas d'effet sur les créneaux ni sur les inscrits** (P-BL-18, Lot 2).
- **Pas de révocation immédiate des sessions déjà ouvertes ailleurs.** Le refus au login et au
  refresh suffit à fermer la porte, mais un jeton d'accès en cours de validité reste valide jusqu'à
  son expiration : la vraie révocation est **P-BS-03** (module [`session/`](../session), Lot 1).
- **Pas d'export enrichi.** L'export RGPD omet l'essentiel de l'activité (**P-BL-13**, Lot 2).
- **Aucune communication n'est partie** aux personnes dont la demande a été perdue. Elle est
  **suspendue à la validation du juridique** (§1), et nous ne l'enverrons pas sans.
- **`DELETE /users/me` ne disparaît pas.** Elle devient un **alias documenté** de la route RGPD
  (P-BA-23) — vous n'avez pas à commencer à l'appeler, et vous n'avez pas à craindre qu'elle change.
