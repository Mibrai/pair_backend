package org.program.pair.domain.media;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface StorageService {

    /**
     * Dépose un fichier et rend son chemin de stockage
     * ({@code « user_avatar/<uuid>.jpg »}).
     *
     * <p><b>{@code uploadedBy} est toujours l'appelant</b>, c'est-à-dire
     * {@code principal.getId()}. Ce paramètre s'appelait {@code ownerId} et ne
     * servait qu'à une ligne de journal ; deux des quatre appelants y passaient
     * donc un {@code programId} ou un {@code activityId} sans que rien ne le
     * signale. C'était la racine du défaut P-BS-01 : le seul identifiant
     * conservé ne désignait personne, et aucune suppression ne pouvait dès lors
     * être autorisée. L'implémentation en tire maintenant la ligne de propriété
     * {@code media_files} — un chemin de dépôt qui passerait autre chose que
     * l'appelant rendrait ce fichier supprimable par un tiers.
     */
    String store(MultipartFile file, UUID uploadedBy, MediaType mediaType) throws IOException;

    /**
     * Le chemin absolu d'un fichier du stockage, <b>borné à la racine</b>.
     *
     * <p>Un chemin absolu, ou remontant hors de la racine, lève
     * {@code ResourceNotFoundException(MEDIA_FILE_NOT_FOUND)} : introuvable, et
     * jamais interdit — un refus distinct dirait à qui sonde les chemins
     * lesquels existent.
     */
    Path load(String filename);

    /**
     * Load a file as an InputStream
     */
    InputStream loadAsResource(String filename) throws IOException;

    /**
     * Le fichier est-il réellement lisible sur le stockage ?
     *
     * <p>Existe pour les <b>références orphelines</b> : une ligne en base peut
     * pointer un fichier disparu du stockage (cf. l'incident du 2026-08-11), et
     * l'appelant a besoin de le savoir <b>sans</b> déclencher le refus que
     * {@link #loadAsResource} lève. Sérialiser une couverture cassée doit rendre
     * une image nulle, pas faire échouer la lecture du programme entier.
     */
    boolean exists(String filename);

    /**
     * Efface les octets d'un chemin, <b>sans vérifier aucune propriété</b>.
     *
     * <p>À n'appeler que depuis {@link MediaFileService#supprimerSiAuteur}, qui
     * porte la garde. Un appel direct depuis un contrôleur est exactement le
     * défaut que la fiche P-BS-01 a fermé.
     */
    void delete(String filename) throws IOException;

    /**
     * Initialize storage (create directories, etc.)
     */
    void init() throws IOException;
}
