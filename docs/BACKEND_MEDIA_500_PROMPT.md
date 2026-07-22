# Prompt à coller dans le Claude Code du dépôt backend

---

Bug de production à corriger : **`GET /api/media/files/**` renvoie systématiquement `500 INTERNAL_ERROR` pour tout média uploadé** (avatars ET images de programme). Les uploads réussissent et l'URL est persistée, mais **aucun média uploadé n'est lisible**, donc plus aucune image (photo de profil, image de couverture de programme) ne s'affiche dans l'app mobile.

## Symptôme

Requête authentifiée sur un fichier média fraîchement uploadé :

```
GET /api/media/files/program_image/727b0bfa-3718-452a-87ab-f25a886fadce.png
Authorization: Bearer <token valide>

HTTP/2 500
content-type: application/json
{"code":"INTERNAL_ERROR","message":"Une erreur est survenue.","timestamp":"2026-07-22T19:45:32.687597645Z"}
```

Le même 500 se produit pour les avatars (`/api/media/files/user_avatar/<uuid>.jpg`) — ce n'est **pas** spécifique à `program_image`.

## Ce qui marche vs ce qui casse

- ✅ **Écriture** : `POST /api/programs/{id}/image/upload` et `POST /api/users/me/avatar` (multipart, champ `file`) répondent `200` et renvoient bien l'entité avec l'URL `/api/media/files/...`.
- ✅ **Sécurité** : la même URL **sans** `Authorization` renvoie `401` — le filtre d'auth passe donc correctement.
- ❌ **Lecture** : avec un token valide, le service du fichier échoue en `500`. Le contrôle d'accès réussit (sinon 401), c'est **la lecture/streaming du fichier qui plante**.

Conclusion : le bug est dans le **handler de service des médias** (`GET /api/media/files/**`), pas dans l'auth ni dans l'upload.

## Repères pour retrouver les logs (Railway)

Requête de repro capturée le **2026-07-22T19:45:32Z** :
- `x-railway-request-id: 6-iKuLcYQ8y0tzw1YqVb7A`
- `x-hikari-trace: ams1.b55h`
- `server: railway-hikari`, `x-railway-edge: ams1`

Cherche la **stacktrace complète** de ce request-id (le body `INTERNAL_ERROR` générique masque l'exception réelle via le `@ControllerAdvice` / handler global).

## Ta mission

1. **Localise** le contrôleur/handler qui sert `GET /api/media/files/**` (probablement un `MediaController` / `FileController`, ou un `ResourceHttpRequestHandler` custom) et le service de stockage associé.
2. **Récupère la vraie exception** derrière le `500 INTERNAL_ERROR` (logs du request-id ci-dessus, ou reproduis en local). Hypothèses courantes à vérifier :
   - fichier introuvable sur le volume de stockage (chemin/volume Railway non monté ou réinitialisé après un redeploy → `FileNotFoundException`/`NoSuchFileException` remontée en 500 au lieu d'un 404) ;
   - mauvaise résolution du `Content-Type`/`Resource` (probe MIME, `Files.probeContentType` null) ;
   - `Path` construit à partir de l'URL de façon incorrecte (double préfixe `/api`, séparateur, ou `MalformedURLException`) ;
   - régression récente : compare avec le dernier déploiement où la lecture fonctionnait (le frontend confirme que les avatars uploadés se chargeaient encore le 2026-07-21).
3. **Corrige** la cause racine. Renvoie un `404` propre quand le fichier n'existe vraiment pas (au lieu d'un 500), et un `200` avec le bon `Content-Type` et le flux binaire quand il existe.
4. **Ajoute un test** (intégration ou `@WebMvcTest`) qui uploade puis relit un média et vérifie `200` + `Content-Type` image + corps non vide — ce cas n'était manifestement pas couvert.
5. **Vérifie en repro** : uploade une petite image, relis-la avec un token valide, confirme `200` + octets d'image, et confirme qu'un uuid inexistant donne `404` et non `500`.

Ne touche pas au chemin d'upload ni au filtre d'auth : ils fonctionnent. Concentre-toi sur le service de lecture des fichiers médias.

## Comment reproduire de bout en bout (contre l'API déployée)

```bash
BASE="https://pairbackend-production-35fe.up.railway.app/api"
HOST="https://pairbackend-production-35fe.up.railway.app"
TOKEN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  -d '{"email":"<compte de test>","password":"<mot de passe>"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

# uploader un avatar puis relire l'URL renvoyée :
IMG=$(curl -s -X POST "$BASE/users/me/avatar" -H "Authorization: Bearer $TOKEN" \
  -F "file=@une_image.png;type=image/png" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['avatarUrl'])")

curl -i "$HOST$IMG" -H "Authorization: Bearer $TOKEN"   # -> 500 actuellement, attendu 200
curl -i "$HOST$IMG"                                      # -> 401 (normal, auth requise)
```
