# meetDo — Liens publics partageables et Universal Links
## Spécification backend pour Claude Code

> **Contexte projet.** Backend Spring Boot 4.1.0 (`org.program.pair`),
> PostgreSQL 16 + PostGIS + pgvector, Flyway **V59**, 179 endpoints,
> déployé sur Railway (`pair_backend_service`, EU West Amsterdam).
> App Flutter, bundle iOS/Android `com.meetdo.app`, Team ID Apple
> `97727T64DH`.
>
> **Ce que ce document couvre.** Tout ce que le backend doit faire pour que
> le partage d'un créneau fonctionne de bout en bout : la page publique
> lisible sans compte, les métadonnées d'aperçu pour les messageries, les
> fichiers de validation Universal Links (iOS) et App Links (Android), et
> l'export calendrier.
>
> **Pourquoi c'est stratégique.** meetDo n'a aujourd'hui aucun mécanisme
> viral : chaque utilisateur doit être acquis individuellement. Les pages
> publiques sont le seul canal d'acquisition qui ne coûte rien par
> utilisateur — c'est le mécanisme qui a fait grandir Meetup.

---

## 0. État de l'infrastructure au moment de la rédaction

Configuration déjà en place, à ne pas refaire :

| Élément | État |
|---|---|
| Domaine `meetdo.fun` | Actif, hébergé chez Hostinger (site vitrine) |
| Fichier AASA sur `meetdo.fun` | Servi en `200` avec `content-type: application/json` |
| Team ID Apple | `97727T64DH` |
| Bundle / package | `com.meetdo.app` |
| Sous-domaine `lien.meetdo.fun` | À créer, à pointer vers Railway (option retenue) |

**Architecture cible :**

```
meetdo.fun            → site vitrine (Hostinger, hors périmètre de ce document)
lien.meetdo.fun       → backend Spring Boot (Railway)
lien.meetdo.fun/s/{token}                              → page publique de créneau
lien.meetdo.fun/.well-known/apple-app-site-association → validation iOS
lien.meetdo.fun/.well-known/assetlinks.json            → validation Android
```

> Le proxy Apache `[P]` a été écarté : `mod_proxy` n'est pas disponible sur
> l'offre Hostinger (retour `503`). La redirection `[R=302]` a été écartée
> à son tour car certains robots de prévisualisation ne suivent pas les
> redirections — ce qui casserait l'aperçu riche, donc l'intérêt même du
> partage.

---

## 1. Les fichiers de validation `.well-known`

### 1.1 Contrôleur

`src/main/java/org/program/pair/wellknown/WellKnownController.java`

```java
package org.program.pair.wellknown;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fichiers de validation des liens universels (iOS) et des liens
 * d'application (Android).
 *
 * <p>Apple et Google téléchargent ces fichiers pour vérifier que le
 * propriétaire du domaine autorise bien l'application à intercepter ses
 * URLs. Quatre exigences, chacune ayant déjà fait échouer une validation
 * en silence :
 *
 * <ul>
 *   <li>servis en HTTPS, sans aucune redirection — les robots ne les
 *       suivent pas ;</li>
 *   <li>{@code Content-Type: application/json} — le piège le plus
 *       fréquent, un fichier sans extension étant souvent servi en
 *       {@code text/plain} ;</li>
 *   <li>aucune extension de fichier pour l'AASA (pas de {@code .json}) ;</li>
 *   <li>accessibles sans authentification — d'où l'exception explicite
 *       dans la configuration de sécurité.</li>
 * </ul>
 *
 * <p>Un échec ici ne produit aucune erreur visible : les liens s'ouvrent
 * simplement dans le navigateur au lieu de l'application.
 */
@RestController
public class WellKnownController {

    @Value("${meetdo.links.apple-team-id:97727T64DH}")
    private String appleTeamId;

    @Value("${meetdo.links.bundle-id:com.meetdo.app}")
    private String bundleId;

    @Value("${meetdo.links.android-sha256:}")
    private String androidSha256;

    @GetMapping(
        value = "/.well-known/apple-app-site-association",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public String appleAppSiteAssociation() {
        return """
            {
              "applinks": {
                "details": [
                  {
                    "appIDs": ["%s.%s"],
                    "components": [
                      { "/": "/s/*", "comment": "Pages publiques de créneau" }
                    ]
                  }
                ]
              }
            }
            """.formatted(appleTeamId, bundleId);
    }

    @GetMapping(
        value = "/.well-known/assetlinks.json",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public String assetLinks() {
        return """
            [{
              "relation": ["delegate_permission/common.handle_all_urls"],
              "target": {
                "namespace": "android_app",
                "package_name": "%s",
                "sha256_cert_fingerprints": ["%s"]
              }
            }]
            """.formatted(bundleId, androidSha256);
    }
}
```

### 1.2 Configuration

`application.properties` :

```properties
meetdo.links.apple-team-id=${APPLE_TEAM_ID:97727T64DH}
meetdo.links.bundle-id=${APP_BUNDLE_ID:com.meetdo.app}
meetdo.links.android-sha256=${ANDROID_SHA256:}
meetdo.links.public-base-url=${PUBLIC_BASE_URL:https://lien.meetdo.fun}
```

> L'empreinte SHA-256 Android n'est pas encore connue — elle se génère à
> partir de la clé de signature de release (`keytool -list -v -keystore …`),
> ou se récupère dans la Play Console si Play App Signing est activé.
> Laisser vide n'empêche pas le démarrage ; `assetlinks.json` sera
> simplement invalide tant que la valeur n'est pas renseignée.

### 1.3 Spring Security

Ajouter dans `SecurityConfig`, à côté des routes publiques existantes :

```java
.requestMatchers("/.well-known/**").permitAll()
.requestMatchers("/public/**").permitAll()
.requestMatchers("/s/**").permitAll()
```

> ⚠️ **Sans cette exception, un 401 silencieux fait échouer toute la
> validation Apple et Google, sans message d'erreur exploitable.** C'est
> l'erreur la plus fréquente sur ce chantier.

---

## 2. Le partage d'un créneau — modèle de données

### 2.1 Migration `V60__public_slot_sharing.sql`

```sql
ALTER TABLE schedules
    ADD COLUMN public_share_token VARCHAR(22) UNIQUE,
    ADD COLUMN is_publicly_shareable BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN public_view_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_schedules_share_token ON schedules(public_share_token);
```

> **Vérifier le numéro de migration avant de créer le fichier.** La base
> est à V59 au moment de la rédaction ; ajuster si d'autres migrations ont
> été ajoutées entre-temps.

### 2.2 Génération du jeton

Un identifiant opaque de 22 caractères, alphabet base62, généré à la
création d'un créneau — et rétroactivement pour les créneaux existants
via la migration ou un runner ponctuel.

```java
private static final String ALPHABET =
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

private String generateShareToken() {
    SecureRandom random = new SecureRandom();
    StringBuilder sb = new StringBuilder(22);
    for (int i = 0; i < 22; i++) {
        sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return sb.toString();
}
```

> ⚠️ **Ne jamais exposer l'UUID interne du créneau dans une URL publique.**
> Un UUID permettrait de deviner d'autres ressources et lierait l'URL
> publique à la clé primaire. Le jeton opaque protège contre l'énumération
> et reste stable même si la structure interne évolue.

---

## 3. La page publique de créneau

### 3.1 Contrôleur

`src/main/java/org/program/pair/publicpage/PublicSlotController.java`

```java
@RestController
@RequiredArgsConstructor
public class PublicSlotController {

    private final PublicSlotService publicSlotService;

    /** Données brutes, pour consommation programmatique. */
    @GetMapping(value = "/public/slots/{token}",
                produces = MediaType.APPLICATION_JSON_VALUE)
    public PublicSlotDto getPublicSlot(@PathVariable String token) { ... }

    /**
     * Page HTML lisible sans compte, avec métadonnées OpenGraph.
     * C'est l'URL réellement partagée : lien.meetdo.fun/s/{token}
     */
    @GetMapping(value = "/s/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public String renderPublicPage(@PathVariable String token, Model model) { ... }

    /** Ajout du créneau à l'agenda personnel, sans compte. */
    @GetMapping(value = "/s/{token}/calendar.ics", produces = "text/calendar")
    public ResponseEntity<String> calendar(@PathVariable String token) { ... }
}
```

### 3.2 DTO public — filtrage strict

```java
public record PublicSlotDto(
    String shareToken,
    String programTitle,
    String activityName,
    String categoryName,
    String categoryColorRamp,
    String level,
    String format,
    Instant startsAt,
    Instant endsAt,
    String placeName,            // toujours affiché
    String displayAddress,       // null si lieu privé non partagé
    String cityLabel,            // ville approximative, jamais l'adresse exacte
    Integer participantCount,
    Integer maxParticipants,
    String welcomeNote,
    String organizerDisplayName, // prénom seul, jamais l'e-mail
    String organizerAvatarUrl,
    Boolean organizerIsVerified,
    String imageUrl,
    String primaryLanguage
) {}
```

> ⚠️ **Ne jamais inclure dans ce DTO** : e-mail, téléphone, UUID
> d'utilisateur ou de créneau, coordonnées exactes d'un lieu privé, liste
> des participants, identifiants de conversation. Cet objet est lisible par
> n'importe qui sur Internet.

### 3.3 Règles de filtrage — impératives

Retourner **`404`, jamais `403`**, si l'une de ces conditions est vraie :

- `is_publicly_shareable = false`
- l'organisateur est inactif (`users.is_active = false`)
- le programme n'est pas `ACTIVE` ou n'est pas public
- l'activité de l'organisateur a `visible_on_map = false`
- le créneau est passé depuis plus de 24 heures
- le créneau est annulé (`CANCELLED`)

> **Pourquoi 404 et non 403 :** un 403 révélerait l'existence de la
> ressource. Le même principe s'applique déjà ailleurs dans le produit
> (profils bloqués, ressources privées) — il doit rester cohérent.

### 3.4 Résolution de l'adresse affichée

Réutiliser exactement la logique de visibilité déjà en place, sans la
réécrire :

```java
private String resolveDisplayAddress(Schedule schedule) {
    if (schedule.getPlaceType() == PlaceType.PUBLIC) {
        return schedule.getAddressPublic();
    }
    if (Boolean.TRUE.equals(schedule.getShowExactAddress())) {
        return schedule.getAddressPublic();
    }
    return null; // lieu privé non partagé : nom du lieu et ville uniquement
}
```

---

## 4. Les métadonnées OpenGraph — le point décisif

C'est **la** raison d'être de la page HTML. Sans métadonnées, un lien collé
dans WhatsApp s'affiche comme une URL nue et ne convertit pas. Avec, il
affiche un aperçu riche avec image, titre et description.

C'est littéralement la différence entre un canal d'acquisition qui
fonctionne et un qui ne fonctionne pas — à ne pas traiter comme un détail
cosmétique.

### 4.1 Gabarit Thymeleaf

`src/main/resources/templates/public-slot.html`

```html
<!DOCTYPE html>
<html th:lang="${lang}">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title th:text="${slot.programTitle} + ' · meetDo'">meetDo</title>

  <!-- OpenGraph : WhatsApp, Facebook, LinkedIn, Signal, Telegram -->
  <meta property="og:title"       th:content="${slot.programTitle}">
  <meta property="og:description" th:content="${ogDescription}">
  <meta property="og:image"       th:content="${ogImage}">
  <meta property="og:url"         th:content="${canonicalUrl}">
  <meta property="og:type"        content="website">
  <meta property="og:site_name"   content="meetDo">
  <meta property="og:locale"      th:content="${ogLocale}">

  <!-- Twitter Card -->
  <meta name="twitter:card"        content="summary_large_image">
  <meta name="twitter:title"       th:content="${slot.programTitle}">
  <meta name="twitter:description" th:content="${ogDescription}">
  <meta name="twitter:image"       th:content="${ogImage}">

  <!-- Découverte de l'app -->
  <meta name="apple-itunes-app" th:content="'app-id=' + ${appleAppId}">

  <link rel="canonical" th:href="${canonicalUrl}">
</head>
<body>
  <!-- Structure minimale : visuel, informations, UN bouton -->
</body>
</html>
```

### 4.2 Description générée

Elle doit être **concrète et donner envie**, pas générique :

```java
private String buildOgDescription(PublicSlotDto slot, Locale locale) {
    // Résultat attendu :
    // "Samedi 14 juin, 9h · Yoga · Studio Lumière, Strasbourg · 3 inscrits"
    // "Saturday 14 June, 9am · Yoga · Studio Lumière, Strasbourg · 3 joined"
    // "Samstag 14. Juni, 9 Uhr · Yoga · Studio Lumière, Straßburg · 3 dabei"
}
```

> Les libellés passent par les catalogues i18n existants, dans les trois
> langues. Attention aux pluriels (« 1 inscrit » / « 3 inscrits ») —
> utiliser le mécanisme de pluralisation, jamais une concaténation.

### 4.3 Image d'aperçu

Ordre de repli, du plus spécifique au plus générique :

1. l'image du programme si elle existe ;
2. sinon une image générique par catégorie (8 à 10 visuels statiques) ;
3. sinon le visuel de marque meetDo.

**Ne jamais renvoyer une URL d'image nulle** : un aperçu sans image
convertit nettement moins bien.

### 4.4 Langue de la page

Déduire la langue du créneau dans cet ordre : `primary_language` du
créneau s'il est renseigné, sinon l'en-tête `Accept-Language` de la
requête, sinon le français par défaut.

---

## 5. Structure de la page HTML

Volontairement minimale — la conversion se fait dans l'app, pas sur le web.

```
[Bandeau image]
[Pastille de catégorie]  [Niveau]  [Langue]
[Titre du programme]
[Date et heure, formatées dans la langue de la page]
[Nom du lieu · Ville]        ← jamais l'adresse exacte d'un lieu privé
[Organisateur : prénom, avatar, badge vérifié]
[Mot d'accueil]
[N inscrits · M places]

[ ► Rejoindre sur meetDo ]   ← UN SEUL bouton, dominant

[Ajouter à mon agenda]       ← lien discret vers /s/{token}/calendar.ics
[Qu'est-ce que meetDo ? →]   ← lien discret vers meetdo.fun
```

**Aucun formulaire, aucune inscription sur la page web.** Le bouton
principal ouvre l'app via Universal Link si elle est installée, sinon
redirige vers le store — le comportement natif des Universal Links s'en
charge sans code supplémentaire.

---

## 6. Comptage des vues

Incrémenter `public_view_count` sur `/s/{token}` uniquement, **de façon
asynchrone**, sans bloquer le rendu.

```java
@Async
public void incrementViewCount(UUID scheduleId) { ... }
```

Utile pour mesurer l'efficacité réelle du canal de partage — une
information qui manquera cruellement au moment d'arbitrer les
investissements d'acquisition.

Ne pas compter les requêtes des robots identifiables via `User-Agent`
(`facebookexternalhit`, `WhatsApp`, `Twitterbot`, `TelegramBot`), sinon
un seul partage produirait plusieurs vues fictives.

---

## 7. Export calendrier (ICS)

`ical4j` est **déjà présent** dans le projet (utilisé par
`RecurrenceExpander`) — aucune dépendance à ajouter.

```java
// GET /api/slots/{scheduleId}/calendar.ics   authentifié, participant ou hôte
// GET /api/slots/mine/calendar.ics           tous mes créneaux à venir
// GET /s/{token}/calendar.ics                public, si partageable
```

Contenu de l'événement : titre du programme, début et fin, nom du lieu,
description avec le lien vers la page publique, et **une alarme de rappel
2 heures avant**.

> ⚠️ Même filtrage qu'en §3.3 pour la version publique : ne jamais placer
> l'adresse exacte d'un lieu privé non partagé dans le champ `LOCATION` de
> l'ICS. Nom du lieu et ville uniquement.

**Pourquoi c'est important** : un créneau rejoint mais oublié est une
rencontre qui n'a pas lieu. C'est l'un des rares ajouts qui agit
directement sur le taux de présence réelle.

---

## 8. Endpoints authentifiés complémentaires

```java
// PATCH /api/slots/{scheduleId}/shareable   { isPubliclyShareable: true }
//   → l'organisateur active ou désactive le partage public

// GET   /api/slots/{scheduleId}/share-link
//   → retourne l'URL complète prête à partager :
//     https://lien.meetdo.fun/s/{token}
//   → construite à partir de meetdo.links.public-base-url,
//     jamais codée en dur
```

---

## 9. Ordre d'implémentation

```
1. WellKnownController + exception Spring Security   (§1)
   → déployer et vérifier AVANT toute déclaration côté Xcode
2. Migration V60 + génération des jetons             (§2)
   → prévoir le remplissage rétroactif des créneaux existants
3. PublicSlotService + filtrage strict               (§3)
4. Gabarit Thymeleaf + OpenGraph                     (§4, §5)
5. Comptage de vues asynchrone                       (§6)
6. Export ICS                                        (§7)
7. Endpoints authentifiés                            (§8)
```

---

## 10. Vérifications après déploiement

```bash
# Validation Apple — attendu : 200 + application/json
curl -I https://lien.meetdo.fun/.well-known/apple-app-site-association

# Validation Android — attendu : 200 + application/json
curl -I https://lien.meetdo.fun/.well-known/assetlinks.json

# Page publique — attendu : 200 + text/html
curl -I https://lien.meetdo.fun/s/<un-token-réel>

# Jeton inexistant — attendu : 404, jamais 403 ni 500
curl -I https://lien.meetdo.fun/s/inexistant

# Métadonnées OpenGraph réellement présentes
curl -s https://lien.meetdo.fun/s/<token> | grep 'og:'
```

**Le test décisif reste manuel** : coller un lien réel dans WhatsApp,
Telegram et Instagram, et vérifier que l'aperçu riche s'affiche avec image
et description. C'est la seule preuve que le canal d'acquisition
fonctionne — les en-têtes HTTP ne la donnent pas.

---

## 11. Tests

```
WellKnownControllerTest
- l'AASA retourne application/json et contient le Team ID configuré
- assetlinks.json retourne application/json
- les deux routes sont accessibles sans authentification

PublicSlotVisibilityTest
- créneau non partageable                  → 404
- organisateur inactif                     → 404
- programme non public                     → 404
- activité masquée sur la carte            → 404
- créneau passé depuis plus de 24 h        → 404
- créneau annulé                           → 404
- jeton inexistant                         → 404 (jamais 403 ni 500)

PublicSlotDtoTest
- lieu PRIVATE sans showExactAddress : displayAddress null, cityLabel présent
- lieu PUBLIC : displayAddress renseignée
- aucun e-mail, téléphone ni UUID utilisateur dans la sérialisation JSON
- la liste des participants n'est jamais présente

PublicPageRenderTest
- les balises og:title, og:description, og:image, og:url sont présentes
- og:image n'est jamais vide (repli catégorie puis marque)
- la page est rendue en FR, EN et DE selon primary_language
  et Accept-Language

PublicCalendarTest
- le champ LOCATION ne contient jamais l'adresse d'un lieu privé non partagé
- l'alarme de rappel à −2 h est présente
```

---

## 12. Configuration hors backend — pour mémoire

Ces étapes ne relèvent pas du code mais conditionnent son fonctionnement.

**Hostinger** — créer le sous-domaine `lien.meetdo.fun`, puis simplifier
le `.htaccess` racine, la redirection `/s/*` devenant inutile :

```apache
RewriteEngine On
RewriteRule ^\.well-known/ - [L]
```

**Railway** — `pair_backend_service` → Settings → Networking →
**Custom Domain** → `lien.meetdo.fun`, puis créer l'enregistrement CNAME
fourni dans la zone DNS Hostinger. Attendre la propagation et l'émission
automatique du certificat HTTPS.

**Apple** — developer.apple.com → Identifiers → `com.meetdo.app` → cocher
**Associated Domains** → Save (les profils de provisioning existants sont
invalidés, c'est normal).

**Xcode** — target Runner → Signing & Capabilities → **+ Capability** →
Associated Domains → ajouter `applinks:lien.meetdo.fun`.

**Android** — `AndroidManifest.xml`, ajouter l'`intent-filter`
correspondant avec `android:autoVerify="true"` sur l'hôte
`lien.meetdo.fun` et le chemin `/s/`.

> Apple met le fichier AASA en cache jusqu'à **24 heures**. Pendant le
> développement, activer *Réglages → Développeur → Associated Domains
> Development* sur l'appareil de test pour contourner ce cache.
