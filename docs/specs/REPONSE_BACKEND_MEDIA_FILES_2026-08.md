# Réponse backend — médias illisibles et duplication bloquée

> Réponse à `PROMPT_BACKEND_MEDIA_FILES_2026-08-v2.md` (relance de
> `BACKEND_MEDIA_500_PROMPT.md` du 2026-07-22). Les six points sont traités.
> Deux d'entre eux appellent une correction de vos prémisses — l'une vous
> concerne directement, l'autre change la réponse à votre question décisive.
> Elles ouvrent ce document, avant les livraisons.

---

## Correction 1 — la lecture média renvoie 404 depuis juillet, plus 500

Votre document dit : *« C'est la même absence que la lecture média transforme,
elle, en `500 INTERNAL_ERROR`. »* Ce n'est plus vrai depuis le correctif de
juillet, verrouillé par `MediaFileServingIntegrationTest`. Les deux défauts
d'alors — le wildcard `{*path}` non exploité, et le handler générique
`Exception.class` qui remplaçait tout statut voulu par un 500 — sont corrigés.

Vos 41 échecs du 2026-08-11 étaient donc très probablement des **404**, pas des
500. Le symptôme visible est identique et la cause racine aussi ; seul le code
HTTP a changé. Nous le signalons parce que votre `noticeLevelForApiError` en
dépend : il classait ces échecs en « erreur » alors qu'ils étaient déjà des
refus.

## Correction 2 — `DELETE /programs/{id}/image` **efface bien le fichier**

C'est votre question décisive, et la réponse est : oui, le fichier est supprimé
du stockage, pas seulement déréférencé en base.

`ProgramController.deleteProgramImage` enchaîne deux opérations : vider
`image_url` en base (qui rend l'URL précédente), puis `storageService.delete()`
sur cette URL, qui fait un `Files.delete` réel.

**Votre contournement ne produisait donc pas d'orphelin.** Les octets copiés
étaient bien réclamés. Trois réserves, qui laissent votre demande entièrement
fondée :

1. **L'écriture avait quand même lieu.** Chaque duplication écrivait le fichier
   complet pour l'effacer trois requêtes plus tard. Sur un stockage objet
   facturé à la requête, ce coût-là ne disparaît pas avec l'octet.
2. **L'échec du `DELETE`, lui, laissait un vrai orphelin.** Vous indiquez qu'un
   échec devient un simple avertissement à l'écran : dans ce cas les octets
   restent, sans référence. Le contournement n'était fiable qu'autant que le
   réseau l'était.
3. **Une fenêtre de doublement subsistait** entre la duplication et le `DELETE`.

Bref : le contournement tenait, mais au prix d'une écriture inutile et d'une
garantie conditionnelle. Il n'a plus lieu d'être — voir le point 4.

---

## Point 1 — les traces, et pourquoi il n'y a pas de stacktrace à trouver

Nous n'avons pas accès à vos journaux de production depuis ce dépôt : la
recherche par `X-Request-Id` est à lancer côté Railway. Ce que l'audit du code
permet d'affirmer, en revanche, c'est **ce que vous y trouverez**, et ce que
vous n'y trouverez pas.

Les deux symptômes butent bien sur la même absence de fichier. Le chemin est le
même à une ligne près :

```
LocalStorageService.loadAsResource()   ← le fichier n'existe pas
        ↑                                        ↑
MediaController.serveFile()          ProgramImageDuplicator.duplicate()
   GET /api/media/files/**              POST /programs/{id}/duplicate
```

Dans le chemin de duplication, l'exception levée n'était **pas** une
`IOException` — c'était une `ResponseStatusException`. Le `catch (IOException)`
du duplicateur ne l'attrapait donc pas : elle traversait le service, annulait la
transaction, et remontait telle quelle au `@ControllerAdvice`, qui la rendait en
`404 {"code":"NOT_FOUND","message":"File not found"}`. Exactement ce que vous
avez mesuré.

**Il n'y aura pas de stacktrace** : ce handler journalise les 4xx en `WARN` sans
trace d'appel — c'est un refus explicite, pas un plantage. Vous trouverez une
ligne, et elle suffit :

```
WARN [rid:6b979d51edb76727] ResponseStatusException: 404 NOT_FOUND - File not found
```

La corrélation par `rid:` fonctionne depuis B8 (lot 7) : `RequestIdFilter`
dépose l'identifiant dans le MDC et le motif de journalisation le sort sur
chaque ligne.

## Point 2 — stockage persistant

`STORAGE_PATH` valait par défaut `uploads`, **un chemin relatif**. Dans le
conteneur il se résolvait en `/app/uploads`, c'est-à-dire dans la couche
d'écriture éphémère : les téléversements réussissaient, la base gardait l'URL,
et le redeploy suivant effaçait les octets. Votre hypothèse n° 1 de juillet
était la bonne.

Livré :

- le `Dockerfile` fixe `STORAGE_PATH=/data/uploads` — chemin **absolu**, hors de
  `/app`, prêt à recevoir un volume ;
- `RAILWAY_ENV_VARS.md` documente le montage (Service > Settings > Volumes,
  mount path `/data`) ;
- **deux signaux au démarrage**, parce que le silence est ce qui a coûté trois
  semaines :
  - `Storage path 'uploads' is relative — …` si `STORAGE_PATH` n'est pas posé ;
  - un **témoin de persistance** : un marqueur est écrit au premier démarrage et
    relu aux suivants. `Storage persisted across restarts (initialized on …)`
    signifie que le volume a survécu ; `Storage contains no persistence marker`
    signifie premier démarrage **ou** volume effacé — et la même ligne à chaque
    redeploy accuse sans ambiguïté.

**Ce point n'est pas clos par le code.** Le montage du volume est une action
dans le dashboard Railway, que ce dépôt ne peut pas effectuer. Tant qu'il n'est
pas fait, les nouveaux téléversements disparaîtront comme les précédents. La
ligne de démarrage vous dira laquelle des deux situations vous êtes.

Migration ultérieure vers un stockage objet (S3/R2/GCS) : `StorageService` est
une interface et `LocalStorageService` sa seule implémentation, aucun appelant
ne la contourne. Une seconde implémentation suffira.

## Point 3 — références orphelines

Une couverture dont le fichier a disparu se résout désormais en **image nulle**
dans `ProgramDto`, au lieu d'une URL qui répondrait 404 à chaque affichage.

Ce que ce garde-fou ne fait pas, volontairement : **il ne modifie pas la base**.
La colonne garde sa valeur, et un stockage restauré redevient lisible sans
migration ni rattrapage. Nous ne détruisons pas une référence à cause d'une
panne d'infrastructure.

**Portée** — `Program.imageUrl` uniquement, servi par `GET /api/programs/**`.
Les avatars et les galeries ne sont pas vérifiés, et les **vignettes de
recherche** non plus : chaque vérification est un accès disque par entité
sérialisée, qui deviendrait un aller-retour réseau facturé sur un stockage
objet. Sur une liste de résultats, la note serait salée pour un bénéfice
cosmétique — une image qui répond 404 s'affiche déjà comme une absence d'image
chez vous. Dites-nous si l'un de ces cas vous gêne réellement à l'usage.

## Point 4 — la copie d'image est supprimée

`POST /programs/{id}/duplicate` **ne copie plus la couverture**. La copie naît
avec `imageUrl == null`, et **aucun octet n'est écrit** sur le stockage pour
elle. `ProgramImageDuplicator` est supprimé du code.

C'est un revirement assumé du contrat B4 (lot 7), qui documentait explicitement
le choix inverse. Votre arbitrage produit du 2026-08-11 le motive, et la
robustesse le confirme : un fichier manquant ne doit pas empêcher de copier des
métadonnées et des créneaux qui, eux, vont parfaitement bien.

**Vous pouvez retirer votre `DELETE /programs/{id}/image` post-duplication.** Il
n'a plus d'objet : il n'y a plus de couverture à retirer, et plus rien à
rattraper si l'appel échoue. `DuplicateProgramRequest` garde son seul champ
`title`.

## Point 5 — code d'erreur sur les refus fichiers

Les refus liés aux fichiers portent désormais `MEDIA_FILE_NOT_FOUND` :

```http
GET /api/media/files/program_image/<disparu>

404 Not Found
{
  "code": "MEDIA_FILE_NOT_FOUND",
  "message": "Ce fichier n'est plus disponible.",
  "timestamp": "…"
}
```

Le message est traduit selon `Accept-Language` (fr par défaut, en, de) ; le code
ne l'est jamais et ne changera pas de nom, comme tous les membres de
`ErrorCode`. Plus de « File not found » anglais affiché tel quel.

**À noter pour votre couche d'erreurs, au-delà de ce cas** : toute
`ResponseStatusException` non nommée produit encore un `code` égal au nom du
statut HTTP (`PAYLOAD_TOO_LARGE`, `UNSUPPORTED_MEDIA_TYPE`, …), qui n'appartient
pas à l'énumération `ErrorCode`. Que celui du fichier manquant ait été
`NOT_FOUND` était une coïncidence de nommage. Si vous rencontrez d'autres refus
sans code exploitable, signalez-les : ils se nomment un par un.

## Point 6 — les tests qui manquaient

`POST /programs/{id}/duplicate` n'avait **aucun test d'intégration** : le
contrat B4 a été livré au lot 7 sans couverture. `ProgramDuplicationIntegrationTest`
en ajoute cinq :

| Test | Ce qu'il verrouille |
| --- | --- |
| `duplication_devraitCopierMetadonneesEtCreneaux` | le nominal, jamais couvert : titre suffixé, statut `DRAFT`, non public, créneaux copiés |
| `duplication_devraitUtiliserLeTitreDemande` | le champ `title` du corps |
| `duplication_devraitRendreUneCopieSansCouverture_etNEcrireAucunOctet` | vos deux exigences : `imageUrl == null` **et** le répertoire de stockage inchangé avant/après |
| `duplication_devraitReussir_quandLeFichierDeCouvertureADisparuDuStockage` | l'incident lui-même : fichier supprimé du stockage, référence toujours en base, duplication **réussie** |
| `lectureProgramme_devraitRendreImageNulle_quandLeFichierADisparu` | le garde-fou du point 3, et le fait que la colonne en base n'est pas touchée |

Plus, dans `MediaFileServingIntegrationTest`, deux assertions sur le code
`MEDIA_FILE_NOT_FOUND` et sa traduction.

---

## Ce qui reste ouvert de votre côté

1. **Monter le volume Railway sur `/data`** — sans quoi rien de ce qui précède
   n'empêchera les prochains téléversements de disparaître. C'est le seul point
   que le code ne peut pas fermer.
2. **Retirer le `DELETE /programs/{id}/image` post-duplication** (point 4).
3. **Nous dire si les vignettes de recherche et les avatars orphelins gênent à
   l'usage** (point 3), auquel cas nous étendrons le garde-fou avec un cache.
4. Les fichiers perdus avant le montage du volume **ne sont pas récupérables** :
   les octets n'existent plus. Les programmes concernés apparaîtront désormais
   sans couverture, proprement, et leurs auteurs pourront en téléverser une
   nouvelle.
