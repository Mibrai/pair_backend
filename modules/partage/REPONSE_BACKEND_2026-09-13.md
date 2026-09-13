# `assetlinks.json` : prêt pour deux empreintes, et rien de publié

**Date :** 2026-09-13
**Module :** [`partage/`](.)
**En réponse à :** [`PROMPT_BACKEND_2026-09-13.md`](PROMPT_BACKEND_2026-09-13.md) — P-MS-17

> **En bref.**
>
> - **§1 — `AppLinksController` accepte une liste d'empreintes.**
> - **§2 — rien n'est publié** : la variable reste vide jusqu'à P-MS-08, et le fichier rend `404`.
> - **§3 — Apple** : inchangé.

---

## 1. Plusieurs empreintes

`meetdo.links.android-sha256` (variable `ANDROID_SHA256`) accepte désormais une **liste séparée par des
virgules** : clé d'app Play App Signing, puis clé d'upload. Le fichier servi :

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.meetdo.app",
    "sha256_cert_fingerprints": ["<clé d'app>", "<clé d'upload>"]
  }
}]
```

en `200 application/json`, sans redirection, sur `lien.meetdo.fun`.

## 2. Garde-fou contre une valeur provisoire

**Toutes les empreintes valides, ou aucun fichier.** Chaque valeur doit avoir la forme SHA-256
`AA:BB:…` (32 octets). Une seule valeur mal formée — « EN ATTENTE », une empreinte SHA-1, une coquille —
laisse le fichier en **404** : exactement votre point 3, tenu par le code et pas seulement par la
discipline.

**Aucune variable n'est posée en production.** Quand P-MS-08 aura produit les deux empreintes, dites-le
nous : nous posons `ANDROID_SHA256` et vous relevez le `200`.

## 3. Apple

Inchangé. `/s/`, `/p/` et `/v/` restent servis par le même contrôleur, et `verify-email.html` garde son
`meetdo://verify` nu.
