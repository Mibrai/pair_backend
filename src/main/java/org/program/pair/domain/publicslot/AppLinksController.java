package org.program.pair.domain.publicslot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Les deux fichiers sans lesquels un lien {@code https://} ne rouvre jamais
 * l'application.
 *
 * <p>Servis par un contrôleur et non depuis un dossier statique, pour deux
 * raisons. {@code apple-app-site-association} <b>n'a pas d'extension</b> : servi
 * comme ressource, il sortirait en {@code application/octet-stream} et Apple le
 * rejetterait. Et le chemin commence par un point, ce qu'aucune configuration de
 * ressources statiques n'expose ici — il n'y a d'ailleurs pas de dossier
 * {@code static/} dans ce projet.
 *
 * <p><b>Rien n'est servi tant que les valeurs ne sont pas fournies.</b> Elles
 * appartiennent à l'équipe mobile et aux comptes développeur : l'identifiant
 * d'application Apple ({@code TEAM_ID.BUNDLE_ID}), le nom de paquet Android, et
 * l'empreinte SHA-256 du certificat de signature — <b>de release, pas de
 * debug</b>, et différente encore si l'application passe par Play App Signing.
 *
 * <p>Publier un fichier d'association aux valeurs inventées serait pire que de
 * n'en publier aucun : Apple et Google les mettent en cache agressivement, et
 * une association fausse mémorisée par un appareil est plus longue à corriger
 * qu'une association absente.
 */
@RestController
public class AppLinksController {

    @Value("${meetdo.links.apple-team-id:}")
    private String appleTeamId;

    @Value("${meetdo.links.bundle-id:}")
    private String bundleId;

    /**
     * Les deux motifs de réinitialisation du mot de passe, derrière un
     * interrupteur éteint par défaut (décision du 14/09/2026). Voir
     * {@code meetdo.links.reinitialisation-dans-app} : ils ne rejoignent le
     * fichier que le jour où la version de l'app qui gère {@code /r/} est publiée.
     */
    @Value("${meetdo.links.reinitialisation-dans-app:false}")
    private boolean reinitialisationDansApp;

    /**
     * Empreintes SHA-256 des certificats qui signent l'app Android, séparées par
     * des virgules (P-MS-17). Avec Play App Signing, <b>deux</b> clés signent :
     * la clé d'app gérée par Google et la clé d'upload.
     */
    @Value("${meetdo.links.android-sha256:}")
    private String androidSha256;

    /** {@code AA:BB:…} — 32 octets en hexadécimal, séparés par des deux-points. */
    private static final java.util.regex.Pattern EMPREINTE =
        java.util.regex.Pattern.compile("(?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2}");

    /**
     * Servi en {@code application/json} et <b>sans redirection</b> : Apple
     * n'accepte ni l'un ni l'autre écart. Les chemins ouverts sont limités à ce
     * que la page publique utilise.
     *
     * <p><b>Tout motif absent de ce fichier est ignoré en silence par iOS.</b>
     * Livrer une adresse publique sans l'y déclarer donne une route qui répond,
     * une page qui s'affiche, et un lien qui n'ouvre jamais l'application — une
     * livraison qui paraît faite. Les motifs de programme y ont été ajoutés le
     * 2026-08-20, en même temps que les routes.
     *
     * <p>Apple sert ce fichier depuis son propre CDN et les appareils le gardent :
     * une mise à jour n'est pas instantanée pour les installations existantes.
     *
     * <p><b>Format {@code appIDs}/{@code components}</b>, celui d'iOS 13 et
     * au-delà, et non l'ancien couple {@code appID}/{@code paths}. Les deux
     * fonctionnent encore, mais seul le récent accepte un {@code comment} — un
     * fichier que personne ne relit jamais et dont les chemins n'ont aucun sens
     * hors contexte mérite de porter sa propre explication.
     */
    @GetMapping(value = "/.well-known/apple-app-site-association",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> appleAppSiteAssociation() {
        if (appleTeamId.isBlank() || bundleId.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok("""
            {
              "applinks": {
                "details": [
                  {
                    "appIDs": ["%s.%s"],
                    "components": [
                      { "/": "/s/*", "comment": "Pages publiques de créneau" },
                      { "/": "/p/*", "comment": "Pages publiques de programme" },
                      { "/": "/public/slots/*", "comment": "JSON et image d'aperçu, créneau" },
                      { "/": "/public/programs/*", "comment": "JSON et image d'aperçu, programme" },
                      { "/": "/v/*", "comment": "Vérification d'adresse e-mail" }%s
                    ]
                  }
                ]
              }
            }
            """.formatted(appleTeamId, bundleId, reinitialisationDansApp ? MOTIFS_REINITIALISATION : ""));
    }

    /**
     * Retirés le 14/09, quelques heures après leur ajout : la version publiée de
     * l'app ne sait rien de {@code /r/}, et un iPhone qui l'a installée ouvrait le
     * lien de l'e-mail dans une app qui ne faisait rien de lui.
     */
    private static final String MOTIFS_REINITIALISATION = """
        ,
                      { "/": "/r/*", "comment": "Réinitialisation du mot de passe" },
                      { "/": "/reset-password", "?": { "token": "*" }, "comment": "Réinitialisation, e-mails envoyés avant le 14/09/2026" }""";

    @GetMapping(value = "/.well-known/assetlinks.json",
        produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> assetLinks() {
        java.util.List<String> empreintes = empreintesAndroid();
        if (bundleId.isBlank() || empreintes.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok("""
            [
              {
                "relation": ["delegate_permission/common.handle_all_urls"],
                "target": {
                  "namespace": "android_app",
                  "package_name": "%s",
                  "sha256_cert_fingerprints": [%s]
                }
              }
            ]
            """.formatted(bundleId, empreintes.stream()
                .map(e -> "\"" + e.toUpperCase(java.util.Locale.ROOT) + "\"")
                .collect(java.util.stream.Collectors.joining(", "))));
    }

    /**
     * Les empreintes configurées, <b>toutes valides ou aucune</b>. Une valeur
     * provisoire ou mal copiée — « EN ATTENTE », une empreinte SHA-1 — rend le
     * fichier absent plutôt que faux : Google met la vérification en cache, et une
     * empreinte fausse ferait échouer les installations existantes jusqu'à leur
     * mise à jour suivante.
     */
    java.util.List<String> empreintesAndroid() {
        java.util.List<String> valeurs = java.util.Arrays.stream(androidSha256.split(","))
            .map(String::strip)
            .filter(v -> !v.isEmpty())
            .toList();
        if (valeurs.isEmpty() || !valeurs.stream().allMatch(v -> EMPREINTE.matcher(v).matches())) {
            return java.util.List.of();
        }
        return valeurs.stream().distinct().toList();
    }
}
