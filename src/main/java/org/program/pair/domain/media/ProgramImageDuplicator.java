package org.program.pair.domain.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/**
 * Copie physique de l'image d'un programme vers un autre.
 *
 * <p>Physique, et non par référence : deux programmes pointant le même fichier,
 * supprimer l'image de l'un ({@code DELETE /programs/{id}/image} efface le
 * fichier) casserait silencieusement celle de l'autre.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProgramImageDuplicator {

    private static final String URL_PREFIX = "/api/media/files/";

    private final StorageService storageService;

    /**
     * Copie le fichier derrière {@code sourceUrl} pour le programme
     * {@code targetProgramId}.
     *
     * @return l'URL à poser sur la copie : {@code null} si la source n'a pas
     *         d'image, l'URL du nouveau fichier si elle en a une gérée ici, ou
     *         l'URL d'origine telle quelle si elle est externe
     * @throws IllegalStateException si la copie échoue — l'appelant est
     *         transactionnel, et une duplication sans son image serait
     *         exactement le résultat à moitié copié que l'endpoint promet
     *         d'empêcher
     */
    public String duplicate(String sourceUrl, UUID targetProgramId) {
        if (sourceUrl == null) {
            return null;
        }
        if (!sourceUrl.startsWith(URL_PREFIX)) {
            // URL externe (seed, CDN) : rien à copier, aucun risque de suppression
            // croisée puisque nous ne savons pas l'effacer. La référence est partagée.
            return sourceUrl;
        }

        String sourceFilename = sourceUrl.substring(URL_PREFIX.length());
        try (InputStream source = storageService.loadAsResource(sourceFilename)) {
            String copiedFilename = storageService.store(
                new StreamedFile(sourceFilename, source), targetProgramId, MediaType.PROGRAM_IMAGE);
            return URL_PREFIX + copiedFilename;
        } catch (IOException e) {
            throw new IllegalStateException(
                "La copie de l'image du programme a échoué : " + e.getMessage(), e);
        }
    }

    /** Adaptateur minimal InputStream -> MultipartFile pour StorageService.store. */
    private record StreamedFile(String originalFilename, InputStream inputStream)
        implements MultipartFile {

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return "image/jpeg"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return 0; }
        @Override public byte[] getBytes() throws IOException { return inputStream.readAllBytes(); }
        @Override public InputStream getInputStream() { return inputStream; }
        @Override public void transferTo(File dest) { throw new UnsupportedOperationException(); }
    }
}
