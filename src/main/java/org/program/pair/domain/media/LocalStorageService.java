package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    /** Témoin de persistance du stockage — voir {@link #logPersistence()}. */
    private static final String MARKER_FILE = ".storage-initialized";

    private final Path rootLocation;

    public LocalStorageService(@Value("${storage.location:uploads}") String storageLocation) {
        this.rootLocation = Paths.get(storageLocation);
    }

    @Override
    public String store(MultipartFile file, UUID ownerId, MediaType mediaType) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot store empty file");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        // Generate unique filename
        String extension = "";
        int lastDot = originalFilename.lastIndexOf('.');
        if (lastDot > 0) {
            extension = originalFilename.substring(lastDot);
        }

        String filename = UUID.randomUUID().toString() + extension;
        Path destinationDir = rootLocation.resolve(mediaType.name().toLowerCase());

        if (!Files.exists(destinationDir)) {
            Files.createDirectories(destinationDir);
        }

        Path destinationFile = destinationDir.resolve(filename);

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored file: {} for owner: {}", filename, ownerId);
            return mediaType.name().toLowerCase() + "/" + filename;
        }
    }

    @Override
    public Path load(String filename) {
        return rootLocation.resolve(filename);
    }

    @Override
    public InputStream loadAsResource(String filename) throws IOException {
        Path file = load(filename);
        if (!Files.exists(file) || !Files.isReadable(file)) {
            // Un ResourceNotFoundException porteur de code, et non une
            // ResponseStatusException : celle-ci court-circuite errorFor() dans
            // GlobalExceptionHandler et produisait un corps {"code":"NOT_FOUND",
            // "message":"File not found"} — un libellé anglais non traduisible,
            // affiché tel quel à l'utilisateur faute de code pour l'identifier.
            throw new ResourceNotFoundException(
                ErrorCode.MEDIA_FILE_NOT_FOUND, "Fichier introuvable : " + filename);
        }
        return Files.newInputStream(file);
    }

    @Override
    public boolean exists(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        Path file = load(filename);
        return Files.exists(file) && Files.isReadable(file);
    }

    @Override
    public void delete(String filename) throws IOException {
        Path file = load(filename);
        if (Files.exists(file)) {
            Files.delete(file);
            log.info("Deleted file: {}", filename);
        }
    }

    @Override
    public void init() throws IOException {
        if (!Files.exists(rootLocation)) {
            Files.createDirectories(rootLocation);
        }

        for (MediaType type : MediaType.values()) {
            Path typeDir = rootLocation.resolve(type.name().toLowerCase());
            if (!Files.exists(typeDir)) {
                Files.createDirectories(typeDir);
            }
        }

        log.info("Storage initialized at: {}", rootLocation.toAbsolutePath());
        warnIfEphemeral();
        logPersistence();
    }

    /**
     * Dit, au démarrage, si le stockage a survécu au démarrage précédent.
     *
     * <p>C'est le signal qui a manqué pendant trois semaines : rien, dans les
     * journaux, ne distinguait « volume monté » de « répertoire recréé vide à
     * chaque redeploy ». Un marqueur déposé au premier démarrage et relu aux
     * suivants répond à la question en une ligne de log.
     *
     * <p>Limite assumée : un premier démarrage légitime et un volume effacé sont
     * indiscernables — les deux ne trouvent pas de marqueur. C'est la ligne
     * <i>répétée</i> à chaque redeploy qui accuse, pas la première.
     */
    private void logPersistence() {
        Path marker = rootLocation.resolve(MARKER_FILE);
        try {
            if (Files.exists(marker)) {
                log.info("Storage persisted across restarts (initialized on {})",
                    Files.readString(marker).strip());
            } else {
                Files.writeString(marker, Instant.now().toString());
                log.warn("Storage contains no persistence marker: this is either a first boot or a wiped volume. "
                    + "If this line appears on every redeploy, uploaded files are NOT persisted.");
            }
        } catch (IOException e) {
            // Diagnostic seulement : un marqueur illisible ne doit pas empêcher
            // l'application de démarrer ni de servir des médias.
            log.warn("Could not read or write the storage persistence marker at {}: {}", marker, e.getMessage());
        }
    }

    /**
     * Dit à voix haute, au démarrage, ce que l'incident du 2026-08-11 a coûté à
     * découvrir : un chemin relatif se résout dans le répertoire de travail du
     * conteneur, donc dans sa couche d'écriture éphémère. Les uploads
     * réussissent, la base garde l'URL, et le redeploy suivant efface les octets
     * — une panne dont rien, dans les journaux, ne signalait la cause.
     *
     * <p>Un avertissement et non un échec : le mode par défaut ({@code uploads}
     * relatif) reste celui du développement local et des tests, où il est
     * parfaitement légitime.
     */
    private void warnIfEphemeral() {
        if (!rootLocation.isAbsolute()) {
            log.warn("Storage path '{}' is relative — resolved to {} in the current working directory. "
                    + "In a container this is the ephemeral write layer: uploaded files WILL be lost on redeploy. "
                    + "Set STORAGE_PATH to an absolute path backed by a persistent volume.",
                rootLocation, rootLocation.toAbsolutePath());
        }
    }
}
