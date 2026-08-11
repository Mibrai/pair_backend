package org.program.pair.domain.media;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface StorageService {

    /**
     * Store a file and return its unique identifier
     */
    String store(MultipartFile file, UUID ownerId, MediaType mediaType) throws IOException;

    /**
     * Load a file as a Path
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
     * Delete a file
     */
    void delete(String filename) throws IOException;

    /**
     * Initialize storage (create directories, etc.)
     */
    void init() throws IOException;
}
