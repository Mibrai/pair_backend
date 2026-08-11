package org.program.pair.domain.media;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Résout une URL d'image en la vérifiant sur le stockage : l'URL telle quelle si
 * le fichier répond, {@code null} s'il a disparu.
 *
 * <p>Raison d'être : l'incident du 2026-08-11 a laissé en base des références
 * <b>orphelines</b> — des {@code image_url} pointant des octets que le stockage
 * n'a plus. Rendre l'URL malgré tout ne coûte rien au serveur mais donne au
 * client une adresse qui répondra 404 à chaque affichage, indéfiniment. Rendre
 * {@code null} dit la vérité : ce programme n'a pas de couverture consultable,
 * et l'app affiche son état « sans image » plutôt qu'une erreur.
 *
 * <p><b>Ce que ce garde-fou ne fait pas.</b> Il ne répare pas la base : la
 * colonne garde sa valeur, et un stockage restauré la rendra de nouveau
 * lisible sans migration. Il ne fait qu'éviter d'exposer une référence morte.
 *
 * <p><b>Portée volontairement étroite</b> — {@code Program.imageUrl}, servi par
 * {@code ProgramService.toDto}. Étendre la vérification aux avatars et aux
 * galeries multiplierait les accès disque par entité sérialisée sur des listes
 * entières ; sur un stockage objet (S3/R2), chacun deviendrait un aller-retour
 * réseau facturé. Les vignettes de recherche gardent donc l'URL brute : une
 * couverture manquante y reste un 404 silencieux, ce que l'app traite déjà comme
 * une absence d'image.
 */
@Component
@RequiredArgsConstructor
public class StoredImageResolver {

    private static final String URL_PREFIX = "/api/media/files/";

    private final StorageService storageService;

    /**
     * @param url l'URL enregistrée en base, éventuellement {@code null}
     * @return {@code null} si l'URL désigne un fichier que nous gérons et qui a
     *         disparu ; l'URL inchangée dans tous les autres cas — y compris les
     *         URL externes (seed, CDN), dont l'existence ne nous regarde pas et
     *         que nous ne saurions pas vérifier sans appel réseau
     */
    public String resolveOrNull(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return url;
        }
        return storageService.exists(url.substring(URL_PREFIX.length())) ? url : null;
    }
}
