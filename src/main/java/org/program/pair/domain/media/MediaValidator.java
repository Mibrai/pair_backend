package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class MediaValidator {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp"
    );
    private static final List<String> ALLOWED_VIDEO_TYPES = Arrays.asList(
        "video/mp4",
        "video/quicktime",
        "video/x-msvideo"
    );

    private final Tika tika = new Tika();

    /**
     * Refuse tout ce qui n'est pas une image de l'un des trois formats servis.
     *
     * <p><b>Le type réellement détecté ne part plus dans la réponse</b> (P-BS-17,
     * absorbée par P-BA-10). Le refus disait « … Detected: application/pdf », ou
     * « … Detected: application/x-dosexec » : le contenu est reniflé par Tika et
     * non lu dans l'en-tête du client, donc cette phrase apprend à qui téléverse
     * ce que le serveur a <b>compris</b> de son fichier. Répétée sur une série de
     * fichiers, elle fait de cette route un service de détection de type gratuit,
     * et elle renseigne sur la bibliothèque employée. Le client, lui, n'a rien à
     * en faire : il a besoin de savoir que ce format n'est pas accepté, ce que
     * dit le code {@code MEDIA_TYPE_NOT_ALLOWED}.
     *
     * <p>Le type détecté et le nom d'origine restent au journal — ils sont ce
     * qui permet de comprendre un refus dont on nous dit qu'il est injuste. Le
     * {@code log.info} de la reconnaissance est passé en {@code debug} : il
     * portait le nom de fichier d'origine à chaque téléversement réussi, donc
     * dans les journaux de production, pour une information qui ne sert qu'au
     * diagnostic (P-BS-19).
     */
    public void validateImage(MultipartFile file) throws IOException {
        validateFileNotEmpty(file);
        validateFileSize(file);

        String detectedType = tika.detect(file.getInputStream());
        log.debug("Detected MIME type: {} for file: {}", detectedType, file.getOriginalFilename());

        if (!ALLOWED_IMAGE_TYPES.contains(detectedType)) {
            log.warn("Téléversement refusé : type détecté {} (attendus {})",
                detectedType, ALLOWED_IMAGE_TYPES);
            throw new ValidationException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                "Seules les images JPEG, PNG et WebP sont acceptées.");
        }
    }

    /** Même règle que {@link #validateImage}, pour les trois formats vidéo. */
    public void validateVideo(MultipartFile file) throws IOException {
        validateFileNotEmpty(file);
        validateFileSize(file);

        String detectedType = tika.detect(file.getInputStream());
        log.debug("Detected MIME type: {} for file: {}", detectedType, file.getOriginalFilename());

        if (!ALLOWED_VIDEO_TYPES.contains(detectedType)) {
            log.warn("Téléversement refusé : type détecté {} (attendus {})",
                detectedType, ALLOWED_VIDEO_TYPES);
            throw new ValidationException(ErrorCode.MEDIA_TYPE_NOT_ALLOWED,
                "Seules les vidéos MP4, MOV et AVI sont acceptées.");
        }
    }

    private void validateFileNotEmpty(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
    }

    private void validateFileSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "File size exceeds maximum allowed size of 10MB"
            );
        }
    }
}
