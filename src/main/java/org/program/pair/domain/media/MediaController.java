package org.program.pair.domain.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@Slf4j
public class MediaController {

    private final StorageService storageService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;

    @PostMapping("/upload/image")
    public MediaUploadResponse uploadImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "PROGRAM_IMAGE") org.program.pair.domain.media.MediaType mediaType) throws IOException {

        log.info("Upload request from user: {}, file: {}, size: {}",
            principal.getId(), file.getOriginalFilename(), file.getSize());

        // Validate
        mediaValidator.validateImage(file);

        // Process image (resize, re-encode, optimize)
        InputStream processedImage = imageProcessor.processImage(file);

        // Create a new MultipartFile from processed stream
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(),
            processedImage
        );

        // Store
        String filename = storageService.store(processedFile, principal.getId(), mediaType);

        String url = "/api/media/files/" + filename;

        return new MediaUploadResponse(
            url,
            null,  // TODO: Implement thumbnail generation
            filename,
            file.getSize(),
            file.getContentType()
        );
    }

    @PostMapping("/upload/avatar")
    public MediaUploadResponse uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) throws IOException {

        log.info("Avatar upload from user: {}, file: {}", principal.getId(), file.getOriginalFilename());

        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(file.getOriginalFilename(), processedImage);

        String filename = storageService.store(processedFile, principal.getId(), org.program.pair.domain.media.MediaType.USER_AVATAR);

        return new MediaUploadResponse(
            "/api/media/files/" + filename,
            null,
            filename,
            file.getSize(),
            file.getContentType()
        );
    }

    private static final Map<String, MediaType> CONTENT_TYPES_BY_EXTENSION = Map.of(
        "jpg", MediaType.IMAGE_JPEG,
        "jpeg", MediaType.IMAGE_JPEG,
        "png", MediaType.IMAGE_PNG,
        "gif", MediaType.IMAGE_GIF,
        "webp", MediaType.valueOf("image/webp")
    );

    @GetMapping("/files/{*path}")
    public ResponseEntity<InputStreamResource> serveFile(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable String path) {
        String filename = path.startsWith("/") ? path.substring(1) : path;
        try {
            InputStream inputStream = storageService.loadAsResource(filename);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(resolveContentType(filename))
                .body(new InputStreamResource(inputStream));

        } catch (IOException e) {
            log.error("Error serving file: {}", filename, e);
            throw new ResourceNotFoundException(
                ErrorCode.MEDIA_FILE_NOT_FOUND, "Fichier introuvable : " + filename);
        }
    }

    // Il n'y a volontairement plus de DELETE /api/media/files/** ici.
    //
    // La route existait et n'a jamais été appelée, ni par l'application, ni par
    // un test. Elle passait le chemin reçu à storageService.delete sans rien
    // vérifier d'autre qu'un jeton valide : l'appelant ne servait qu'à écrire la
    // ligne de journal. Or les chemins de fichiers sont publics — ils circulent
    // dans les DTO (avatar d'un profil, image d'un programme, pièce jointe d'un
    // signalement) — donc n'importe quel compte authentifié pouvait effacer le
    // fichier de n'importe qui, y compris une pièce jointe de signalement.
    //
    // La suppression n'est pas « à sécuriser » ici, car rien sur le disque ne
    // dit qui a déposé quoi : LocalStorageService.store ne garde pas d'auteur,
    // et l'identifiant qu'il reçoit est parfois celui d'un programme ou d'une
    // activité. Une garde d'autorisation n'aurait donc eu personne à comparer.
    // La table de propriété des médias, et le service qui l'interroge avant de
    // supprimer, viennent au lot suivant (fiche P-BS-01, partie B).
    //
    // En attendant, un DELETE sur ce motif rend 405 : le GET reste la seule
    // méthode déclarée. Les suppressions légitimes passent par les routes qui
    // connaissent le propriétaire de la ressource — DELETE /api/users/me/avatar
    // pour son propre avatar, par exemple.

    private MediaType resolveContentType(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0 || lastDot == filename.length() - 1) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String extension = filename.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES_BY_EXTENSION.getOrDefault(extension, MediaType.APPLICATION_OCTET_STREAM);
    }

    // Helper class for processed files
    private static class ProcessedMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final InputStream inputStream;

        ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return 0;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return inputStream.readAllBytes();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException();
        }
    }
}
