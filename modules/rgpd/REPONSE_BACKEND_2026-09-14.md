# Fermer son compte annule ses créneaux à venir et prévient les inscrits — et la fiche annulée s'ouvre

**Date :** 2026-09-14
**Module :** `rgpd`
**Fait suite à :** [`PROMPT_BACKEND_2026-09-14.md`](PROMPT_BACKEND_2026-09-14.md)
**Audit :** P-BL-18, lien P-BL-19 — décision D2 (ordre des effets)

> **Les six points de votre demande sont servis**, sans aucun changement de contrat : `204` sans
> corps sur les deux routes de suppression, `SLOT_CANCELLED` identique à celui d'une annulation par
> l'hôte.
>
> - **§1 — une seule transaction, les séances avant le compte.** Si la fermeture échoue, elle ne
>   laisse rien derrière elle : aucun créneau annulé, aucune notification partie. Pour que ce soit
>   vrai, les notifications d'annulation partent désormais **après** l'enregistrement en base. Ce
>   n'était pas le cas jusqu'ici.
> - **§2 — une chose que votre §4 n'aurait pas vue passer.** Depuis l'incident du 14/09, le créneau
>   d'un hôte au compte fermé renvoyait `404`. L'inscrit qui touchait la notification d'annulation
>   serait donc tombé sur « Créneau introuvable ». **Un créneau annulé reste désormais visible**,
>   sur sa fiche comme dans « mes créneaux ».
> - **§3 — ce qu'on a ajouté sans que vous le demandiez** : les veilles de la personne qui ferme son
>   compte se referment, et ses programmes sont archivés.
> - **§5 — ce que nous ne faisons pas**, dont la reprise des comptes démo déjà fermés.

---

## 1. Ce que le serveur fait à la fermeture d'un compte

`DELETE /api/gdpr/delete-account` et `DELETE /api/users/me` suivent **le même chemin**
(`UserService.deactivateAccount`). Dans **une seule transaction**, et dans cet ordre :

| # | Effet | Par quel chemin | Qui est prévenu |
|---|---|---|---|
| 1 | **Ses inscriptions aux séances des autres** (inscrit, intéressé, en liste d'attente, ou inscrit au programme sur ce créneau) sont retirées | le même geste que le retrait après un blocage : la place se libère, la liste d'attente avance, le compteur est relu | **la personne qui reçoit la place** (`WAITLIST_PROMOTED`) ; **l'organisateur ne reçoit rien**, comme lors d'un départ ordinaire |
| 2 | **Ses créneaux à venir ou en cours** passent `CANCELLED` | `SlotCancellationService.cancel`, **le chemin de l'annulation par l'hôte, et lui seul** (P-BL-19) : date, auteur, veilles de la séance refermées | inscrits, intéressés, liste d'attente et inscrits au programme sur ce créneau : **`SLOT_CANCELLED`**, push et e-mail comme d'habitude |
| 3 | **Ses propres veilles** encore actives se referment (`CLOSED` + `ABANDONED`) | nouvelle méthode `WatchSlotLifecycle.closeForClosedAccount`, même règle qu'à l'annulation | personne |
| 4 | **Ses programmes** passent `ARCHIVED` | la même écriture que `DELETE /programs/{id}` | personne |
| 5 | puis, comme avant : `is_active = false`, `deactivated_at`, sortie de la carte, appareils détachés, sessions révoquées | inchangé | — |

### 1.1 Votre point 2 : la raison n'est pas donnée

L'annulation part **sans motif** : `cancellation_reason` reste nul, et la charge utile de
`SLOT_CANCELLED` ne contient pas `cancellationReason`. L'inscrit reçoit exactement le message qu'il
recevrait si l'hôte avait annulé sans rien écrire, avec `alternativesCount` comme d'habitude.

L'auteur de l'annulation (`cancelled_by`) est bien l'hôte. Cette colonne n'est pas renvoyée au
client.

### 1.2 « À venir ou en cours »

Nous comparons la **fin** de la séance à l'heure actuelle, comme pour « mes créneaux » et le retrait
après un blocage : une séance commencée il y a vingt minutes est annulée.

Nous ajoutons un cas que votre demande ne pouvait pas connaître. Une **série récurrente** tient sur
une seule ligne, que le job `RecurringSlotRolloverJob` avance à la séance suivante toutes les dix
minutes. Pendant ces dix minutes au plus, la ligne porte la date d'une séance terminée, alors que la
série a encore des séances devant elle. Nous l'annulons quand même. Annuler la ligne annule toute la
série : aucune séance future ne sera recréée.

**Une séance déjà passée n'est pas modifiée** : son historique sert au signal de fiabilité et aux
cartes-souvenirs.

### 1.3 Votre point 4 : les programmes sont archivés

Annuler les séances ne suffisait pas. Un programme dont l'auteur a fermé son compte pouvait encore
revenir dans ses brouillons, être dupliqué, ou recevoir un nouveau créneau si le compte était un
jour rouvert. L'archiver ne notifie personne, et n'ajoute donc rien aux `SLOT_CANCELLED` du point 2.

**Une inscription à un programme entier, sans créneau précis, n'est pas touchée** : elle n'occupe
aucune place. C'est la même règle que pour le retrait après un blocage.

## 2. Ce que votre §4 n'aurait pas vu passer : la fiche d'un créneau annulé s'ouvre

Le correctif de l'incident du 14/09 (`/slots/mine` en 404) a posé une règle : le créneau d'un hôte
au compte fermé **n'existe plus pour personne**. Sa fiche renvoie `404 « Créneau introuvable »`, et
il sort de « mes créneaux ». Associée à ce lot, cette règle produisait le scénario de votre §4 à
l'envers : l'inscrit reçoit `SLOT_CANCELLED`, touche la notification, et lit « Créneau
introuvable ».

**Décision : un créneau `CANCELLED` échappe à cette règle.**

| Surface | Créneau **ouvert** d'un hôte fermé | Créneau **annulé** d'un hôte fermé |
|---|---|---|
| `GET /api/slots/{id}` | `404 « Créneau introuvable »` (inchangé) | **`200`**, `status: CANCELLED` |
| `GET /api/slots/mine` | absent (inchangé) | **présent** jusqu'à sa fin, `status: CANCELLED` |
| fil, carte | absent (inchangé) | absent, comme tout créneau annulé |
| `join`, `waitlist` | `404` (inchangé) | `404` (inchangé) |

**Ce que vous recevez de différent : le profil de l'hôte est absent.** Le créneau est composé sans
profil, comme une carte-souvenir dont l'hôte a fermé son compte. Si votre écran de fiche suppose un
hôte toujours présent, c'est le seul endroit à vérifier. Le reste (titre, lieu public, horaire,
`status`, `cancelledAt`) est identique à celui d'une annulation ordinaire. L'adresse exacte n'est
plus renvoyée, comme pour tout créneau annulé.

## 3. Votre point 5 : l'ordre des effets, et ce qu'il a fallu changer pour le tenir

**Les séances d'abord, le compte ensuite, dans la même transaction.** Si la fermeture tombe en panne
à mi-chemin, tout est annulé en base : il n'existe jamais de compte fermé avec des créneaux ouverts,
ni de créneau annulé pour un compte resté ouvert. Votre app rejoue la route après une coupure : un
échec renvoie `500` sans aucun effet, et le rejeu réussit.

**Ce qui empêchait de le faire.** L'envoi des notifications (`NotificationService.notify`) est
asynchrone : appelé pendant la transaction, il partait **avant** l'enregistrement en base. Une
fermeture qui échouait après avoir annulé deux créneaux aurait donc :

1. laissé ces créneaux ouverts en base ;
2. mais envoyé `SLOT_CANCELLED` à leurs inscrits ;
3. puis, au rejeu de votre app, **envoyé une seconde fois** la même annulation.

**Correctif** : l'annulation (`SLOT_CANCELLED`) et la promotion depuis la liste d'attente
(`WAITLIST_PROMOTED`) préparent leurs destinataires et leur contenu pendant la transaction, mais
**n'envoient qu'après l'enregistrement en base**. Une transaction annulée n'envoie rien. Un test
provoque volontairement une panne juste après les effets et vérifie qu'aucune notification ne part.
Ce test échoue si l'envoi repasse avant l'enregistrement : nous l'avons vérifié.

**Conséquence pour l'annulation par l'hôte, `POST /slots/{id}/cancel`** : le même correctif
s'applique, puisque c'est le même chemin. Les notifications partent quelques millisecondes plus tard
qu'avant, et jamais pour une annulation qui n'a pas été enregistrée. Rien d'autre ne change pour
vous.

**Si le compte est un jour rouvert**, rien ne se rouvre avec lui. `CANCELLED` est un état définitif,
tout comme le retrait d'une inscription (`WITHDRAWN`) et le départ d'un programme (`LEFT`). Une
éventuelle réactivation retrouverait ses séances annulées et ses places reprises. La réversibilité
elle-même attend toujours le juridique (`REPONSE_BACKEND_2026-09-12.md`, §3).

## 4. Votre point 6 : les tests

`FermetureCompteSeancesIntegrationTest`, qui passe par la route que l'app appelle
(`DELETE /api/gdpr/delete-account`) :

| Test | Ce qu'il vérifie |
|---|---|
| `fermerSonCompte_doitAnnulerSonCreneau_etPrevenirInscritsEtFile` | **votre test** : deux inscrits et une personne en liste d'attente. Le créneau passe `CANCELLED` sans motif, **exactement trois** notifications nouvelles, toutes `SLOT_CANCELLED`, sur une fenêtre de 2 s ; **rien** pour l'hôte ; compte fermé |
| `apresLaFermeture_lInscritDoitVoirLeCreneauAnnule` | §2 : fiche en `200` `CANCELLED`, présente dans « mes créneaux », et `join` toujours en `404` |
| `fermerSonCompte_doitRetirerSesInscriptions_etFaireAvancerLaFile` | point 3 : participation `WITHDRAWN`, la personne en attente passe `CONFIRMED` et reçoit `WAITLIST_PROMOTED`, **rien** pour l'organisateur |
| `fermerSonCompte_doitArchiverSesProgrammes` | point 4 |
| `fermerSonCompte_neDoitPasReecrireUneSeancePassee` | une séance passée n'est pas annulée, une séance à venir du même hôte l'est |
| `fermerSonCompte_doitRefermerSesVeilles` | sa veille sur la séance d'un autre passe `CLOSED` |
| `uneFermetureQuiEchoue_neDoitRienAnnuler_niRienEnvoyer` | point 5 : panne provoquée après les effets. Créneau non annulé, aucune notification sur 2 s |

Les notifications sont comptées **par écart** : on attend d'abord que celles de la mise en place
(inscriptions notifiées à l'hôte) aient fini d'arriver. Sans cela, « rien d'autre » serait vrai ou
faux selon la charge de la machine.

Le test existant `unSeulProducteur_doitEmettreSlotCancelled` reste vert : `SLOT_CANCELLED` n'est
toujours envoyé que depuis `SlotCancellationService`.

## 5. Ce que nous ne faisons pas

- **Pas de reprise des comptes déjà fermés.** Les vingt comptes démo fermés par `V116` gardent leurs
  créneaux ouverts, toujours introuvables (`404`) comme depuis le 14/09. Les annuler maintenant
  enverrait `SLOT_CANCELLED` aux vrais utilisateurs inscrits à des séances de démonstration.
  **La règle vaut pour les fermetures à partir de ce déploiement.**
- **Un départ volontaire (`DELETE /api/slots/{id}/join`) ne referme toujours pas la veille** que la
  personne avait armée pour cette séance. Seule la fermeture de compte le fait (§1, effet 3). C'est
  un écart relevé pendant l'audit, en dehors de ce lot.
- **Pas d'e-mail ni de message à la personne qui ferme son compte** au sujet de ses créneaux
  annulés : elle a vu, sur votre écran, que ses créneaux publiés le seraient.
- **Pas de nouveau champ ni de nouveau type de notification.**

## 6. Côté app, ensuite

Votre §3 peut partir **dès que ce lot est déployé et vérifié** : le parcours de suppression
(`deleteAccountBody`, trois langues) peut dire que les créneaux publiés sont annulés et leurs
inscrits prévenus. Cette formulation est désormais exacte ; « annule-les avant » ne l'est plus.

Une seule chose à vérifier chez vous, au §2 : **la fiche d'un créneau annulé dont le profil d'hôte
est absent** doit s'afficher sans erreur.

Pour la liste d'attente : quand quelqu'un ferme son compte en ayant une place, la personne suivante
reçoit un `WAITLIST_PROMOTED` ordinaire, que votre app sait déjà traiter.

## 7. Vérification

**Chez nous :**
- `FermetureCompteSeancesIntegrationTest` : 7 tests verts ;
- classes voisines rejouées : `SlotCancellationIntegrationTest`, `HoteDesactiveIntegrationTest`,
  `GdprDeletionIntegrationTest`, `SlotWaitlistIntegrationTest`, `UserServiceTest` ;
- suite complète : voir le message de commit.

**Chez vous, sur deux comptes de test remis en état** (jamais un compte réel), c'est votre §4 avec
une précision :

1. le compte A crée un créneau à venir ;
2. le compte B s'y inscrit ;
3. le compte A ferme son compte depuis l'écran de suppression ;
4. B reçoit `SLOT_CANCELLED`, sans motif ;
5. **en touchant la notification, B ouvre la fiche : `200`, `status: CANCELLED`, sans profil
   d'hôte** ;
6. le créneau apparaît barré dans « mes créneaux » de B jusqu'à sa fin.
