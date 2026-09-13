# App Links Android : un `assetlinks.json` à servir, mais pas avant les vraies empreintes

**Date :** 2026-09-13
**Module :** `partage`
**Audit :** P-MS-17 (`audit/PLAN_MOBILE_SECURITE_2026-09-11.md`), décision commune avec P-BS-14

> **Ce que l'app a déjà fait, sans vous** : un lien `meetdo://` ne porte plus aucun code
> d'invitation, et un jeton de vérification reçu par ce schéma — que n'importe quelle app peut
> enregistrer — ne déclenche plus rien. Les liens `https://lien.meetdo.fun/...` sont la voie
> normale. Le filtre Android `autoVerify` est écrit, **en commentaire**.
>
> **Ce qui vous appartient** : servir `assetlinks.json`, et faire accepter à
> `AppLinksController` **plusieurs** empreintes. Mais **rien ne doit être publié avant que les
> empreintes réelles existent** (P-MS-08).

---

## 1. Relevé du 13/09/2026, 15 h 53

| URL | Réponse |
|---|---|
| `https://lien.meetdo.fun/.well-known/apple-app-site-association` | **200** `application/json`, appID `97727T64DH.com.meetdo.app`, motifs `/s/*`, `/p/*`, `/public/slots/*`, `/public/programs/*`, `/v/*` |
| la même, sur le CDN d'Apple | identique |
| `https://lien.meetdo.fun/.well-known/assetlinks.json` | **404** |

`AppLinksController` n'accepte aujourd'hui qu'**une** empreinte (`meetdo.links.android-sha256`).

## 2. Apple : ne rien changer

Le fichier est conforme (200, `application/json`, sans redirection) :

```json
{"applinks":{"details":[{"appIDs":["97727T64DH.com.meetdo.app"],"components":[
  {"/":"/s/*"},{"/":"/p/*"},{"/":"/public/slots/*"},{"/":"/public/programs/*"},{"/":"/v/*"}]}]}}
```

## 3. Android : la demande

1. **`AppLinksController` accepte une liste** d'empreintes au lieu d'une seule valeur. Avec Play
   App Signing (décision D6, option A), deux clés signent l'app : la clé d'app gérée par Google,
   et la clé d'upload.
2. **`/.well-known/assetlinks.json`**, servi en **200 `application/json`, sans redirection**, sur
   `lien.meetdo.fun` :

   ```json
   [{
     "relation": ["delegate_permission/common.handle_all_urls"],
     "target": {
       "namespace": "android_app",
       "package_name": "com.meetdo.app",
       "sha256_cert_fingerprints": [
         "<EN ATTENTE P-MS-08 : SHA-256 de la clé d'app Play App Signing>",
         "<EN ATTENTE P-MS-08 : SHA-256 de la clé d'upload>"
       ]
     }
   }]
   ```

3. **Ne publiez pas de valeur provisoire.** Google met en cache le résultat de la vérification :
   une empreinte inventée ferait échouer la vérification des installations existantes jusqu'à
   leur mise à jour suivante.
4. Le même contrôleur continue de servir `/s/`, `/p/` et `/v/` (P-BS-14). `verify-email.html`
   garde son `meetdo://verify` **nu**, sans jeton.

## 4. Comment nous vérifierons

Quand P-MS-08 aura produit les deux empreintes et que le fichier sera servi : relevé daté de
`assetlinks.json` en 200 ; décommenter le bloc `autoVerify` du manifeste, avec le test qui le
garde en commentaire ; installation Android, puis `adb shell pm get-app-links com.meetdo.app`.
