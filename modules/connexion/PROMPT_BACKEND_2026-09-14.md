# Le lien « mot de passe oublié » tombe sur UNAUTHORIZED

**Date :** 2026-09-14
**Module :** `connexion`
**Signalé par :** l'utilisateur, le 14/09/2026 — le lien de réinitialisation affiche
`UNAUTHORIZED — Authentification requise ou token invalide.`

> **Ce que l'app a déjà fait, sans vous** (branche `fix/lien-reinitialisation`) : elle reconnaît
> désormais `https://lien.meetdo.fun/reset-password?token=…` et ouvre l'écran « nouveau mot de
> passe » avec le jeton ; un tel jeton reçu par `meetdo://` est ignoré (audit P-MS-17). L'écran et
> son `POST /api/auth/reset-password` existaient déjà.
>
> **Ce qui vous appartient** : que ce lien mène quelque part. Aujourd'hui, **personne ne peut
> réinitialiser son mot de passe depuis l'e-mail**, ni dans l'app ni dans un navigateur.

---

## 1. Relevé du 14/09/2026

**Reproduit en production, sans jeton réel** :

```
GET https://lien.meetdo.fun/reset-password?token=essai
→ 401 application/json
{"code":"UNAUTHORIZED","message":"Authentification requise ou token invalide.", …}
```

Même réponse sur l'hôte Railway de l'API.

**Code serveur, commit `4c3d38b`** :
- `EmailService.java:286` compose `baseUrl + "/reset-password?token=" + token`.
- **Aucun contrôleur ne sert `GET /reset-password`** — le commentaire de `EmailService.java:290-294`
  le dit lui-même : « ce chemin, lui, n'a toujours pas de page ».
- `SecurityConfig.java` n'ouvre que `POST /api/auth/forgot-password` et
  `POST /api/auth/reset-password` : un `GET /reset-password` retombe sur `anyRequest().authenticated()`
  et sort en `401` par le point d'entrée JSON (`SecurityConfig.java:262-270`).
- **L'AASA servi sur `lien.meetdo.fun` ne liste pas `/reset-password`** (composants `/s/*`, `/p/*`,
  `/public/slots/*`, `/public/programs/*`, `/v/*`) : sur iPhone, le lien s'ouvre donc dans Safari et
  jamais dans l'app, même installée.
- Le corps attendu par la route existante est bien celui que l'app envoie :
  `ResetPasswordRequest(token, newPassword 8-100)` ↔ `{"token", "newPassword"}`.

C'est le même défaut que la vérification d'adresse avant `/v/{token}`
(`VerificationLinkController`), et que le lien d'invitation `/i/**` réparé le 12/09.

## 2. La demande

1. **Une page publique pour le lien**, ouverte sans session comme `/v/**` et `/i/**` :
   - de préférence un chemin court à jeton, `GET /r/{token}`, sur le modèle de `/v/{token}` — et
     l'e-mail qui l'emploie ; `GET /reset-password?token=` reste servi pour les e-mails déjà envoyés
     (30 minutes de validité, mais un lien peut être ouvert en retard) ;
   - la page propose **« Ouvrir dans meetDo »** et, pour qui n'a pas l'app sur cet appareil, **un
     formulaire** (nouveau mot de passe, confirmation) qui envoie
     `POST /api/auth/reset-password` ;
   - un jeton expiré ou déjà utilisé : une page claire qui renvoie vers « mot de passe oublié »,
     jamais un JSON brut ;
   - aucun jeton dans les journaux ni dans une en-tête `Referer` sortante (`Referrer-Policy:
     no-referrer`), comme pour la vérification.
2. **L'AASA** : ajouter le chemin retenu (`/r/*`, et `/reset-password` avec
   `"?": {"token": "*"}` pour les e-mails déjà partis). Côté Android, le même chemin rejoindra le
   filtre `autoVerify` quand P-MS-08 aura produit les empreintes
   (`modules/partage/PROMPT_BACKEND_2026-09-13.md`).
3. **Dites-nous le chemin retenu** : si c'est `/r/{token}`, l'app ajoutera cette forme à côté de
   `/reset-password?token=` (quelques lignes dans `deep_links.dart`).

## 3. Comment nous vérifierons

Relevé de l'AASA et de `GET /r/<jeton-bidon>` (page d'expiration, pas de 401) ; puis, avec le compte
de test : « mot de passe oublié » depuis l'app, lien de l'e-mail ouvert sur l'iPhone → l'app s'ouvre
sur « nouveau mot de passe » → connexion avec le nouveau mot de passe ; et le même lien ouvert dans
un navigateur de bureau → formulaire web qui aboutit.
