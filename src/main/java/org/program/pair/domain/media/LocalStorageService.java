package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    /** Témoin de persistance du stockage — voir {@link #logPersistence()}. */
    private static final String MARKER_FILE = ".storage-initialized";

    /**
     * L'extension enregistrée découle du type <b>détecté</b>, jamais du nom
     * envoyé par le client.
     *
     * <p>Le nom d'origine décidait de l'extension : un fichier envoyé sous
     * {@code portrait.html} était rangé sous {@code <uuid>.html}, et
     * {@code MediaController.resolveContentType} en déduisait alors le type de
     * contenu… depuis cette même extension. La chaîne entière était donc pilotée
     * par une chaîne de caractères fournie par l'appelant (fiche P-BS-11,
     * étape 2).
     *
     * <p>Un type inattendu retombe sur {@code .bin}, donc sur
     * {@code application/octet-stream} à la lecture : jamais de rendu par le
     * navigateur. C'est un repli et non un refus — le refus des types non
     * autorisés appartient à {@code MediaValidator}, qui s'exécute avant.
     */
    private static final Map<String, String> EXTENSION_PAR_TYPE = Map.of(
        "image/jpeg", ".jpg",
        "image/jpg", ".jpg",
        "image/png", ".png",
        "image/webp", ".webp",
        "image/gif", ".gif"
    );

    private static final String EXTENSION_INCONNUE = ".bin";

    /** Assez pour tous les nombres magiques d'image ; Tika en lit quelques centaines d'octets. */
    private static final int TAILLE_ENTETE = 64 * 1024;

    private static final Tika TIKA = new Tika();

    private final Path rootLocation;
    private final MediaFileRepository mediaFileRepository;

    public LocalStorageService(@Value("${storage.location:uploads}") String storageLocation,
                               MediaFileRepository mediaFileRepository) {
        this.rootLocation = Paths.get(storageLocation);
        this.mediaFileRepository = mediaFileRepository;
    }

    /**
     * Dépose des octets et <b>enregistre qui les a déposés</b>.
     *
     * <p>{@code uploadedBy} est l'appelant — {@code principal.getId()} — et rien
     * d'autre. C'était la racine du défaut P-BS-01 : deux des quatre appelants
     * passaient ici un {@code programId} ou un {@code activityId}, de sorte que
     * l'identifiant enregistré ne désignait personne. La ligne
     * {@code media_files} est écrite ici, et non dans les contrôleurs, pour
     * qu'aucun futur chemin de dépôt ne puisse l'oublier.
     *
     * <p><b>Pourquoi l'écriture est ici et pas dans un rappel d'entité.</b>
     * Voir {@code domain/indexation/ApresCommit} : une écriture soumise pendant
     * le commit d'une autre transaction a bloqué définitivement six fils HTTP le
     * 12/09. Un service, appelé en ligne, n'a pas ce problème.
     */
    @Override
    public String store(MultipartFile file, UUID uploadedBy, MediaType mediaType) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot store empty file");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        Path destinationDir = rootLocation.resolve(mediaType.name().toLowerCase());
        if (!Files.exists(destinationDir)) {
            Files.createDirectories(destinationDir);
        }

        String path;
        try (InputStream brut = file.getInputStream();
             BufferedInputStream flux = new BufferedInputStream(brut, TAILLE_ENTETE)) {

            // Tika n'enveloppe pas un BufferedInputStream : la marque et le
            // retour en arrière portent donc sur ce flux-ci, et les octets
            // consommés pour la détection sont bien réécrits ensuite.
            flux.mark(TAILLE_ENTETE);
            String typeDetecte = TIKA.detect(flux);
            flux.reset();

            String extension = EXTENSION_PAR_TYPE.getOrDefault(typeDetecte, EXTENSION_INCONNUE);
            String filename = UUID.randomUUID() + extension;
            Path destinationFile = destinationDir.resolve(filename);

            Files.copy(flux, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            path = mediaType.name().toLowerCase() + "/" + filename;
            log.info("Stored file: {} (detected {}) for uploader: {}", path, typeDetecte, uploadedBy);
        }

        mediaFileRepository.save(
            MediaFile.depose(path, uploadedBy, MediaPurpose.ofMediaType(mediaType)));
        return path;
    }

    /**
     * Le chemin absolu d'un fichier du stockage — <b>borné à la racine</b>.
     *
     * <p>{@code rootLocation.resolve(filename)} seul ne bornait rien :
     * {@code Path.resolve} d'une chaîne <b>absolue</b> rend cette chaîne telle
     * quelle, donc {@code load("/etc/passwd")} rendait {@code /etc/passwd}, et
     * {@code load("user_avatar/../../x")} sortait de la racine. Seul le
     * {@code StrictHttpFirewall} de Spring Security s'interposait — c'est-à-dire
     * une protection qui n'appartient pas à cette couche et qui ne couvre pas
     * les appelants internes (fiche P-BS-11, étape 1).
     *
     * <p>Un chemin hors racine est <b>introuvable</b>, jamais interdit : il ne
     * doit pas se distinguer d'un fichier absent.
     */
    @Override
    public Path load(String filename) {
        return borner(filename).orElseThrow(() -> {
            log.warn("Chemin de média hors du stockage, refusé : {}", filename);
            return new ResourceNotFoundException(
                ErrorCode.MEDIA_FILE_NOT_FOUND, "Fichier introuvable : " + filename);
        });
    }

    /**
     * Le chemin borné, ou {@link Optional#empty()} s'il sort de la racine.
     *
     * <p>Exister sous cette forme permet à {@link #exists} et {@link #delete} de
     * rester silencieux là où {@link #load} doit lever : sérialiser un programme
     * dont la couverture a une URL douteuse ne doit pas faire échouer la lecture
     * du programme entier (c'est le contrat de {@code StoredImageResolver}).
     */
    private Optional<Path> borner(String filename) {
        if (filename == null || filename.isBlank()) {
            return Optional.empty();
        }
        Path racine = rootLocation.toAbsolutePath().normalize();
        if (Paths.get(filename).isAbsolute()) {
            return Optional.empty();
        }
        Path cible = racine.resolve(filename).normalize();
        return cible.startsWith(racine) ? Optional.of(cible) : Optional.empty();
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
        return borner(filename)
            .filter(file -> Files.exists(file) && Files.isReadable(file))
            .isPresent();
    }

    /**
     * Efface les octets d'un chemin du stockage.
     *
     * <p><b>Cette méthode ne vérifie aucune propriété, et ne doit donc plus être
     * appelée depuis un contrôleur.</b> La garde est dans
     * {@link MediaFileService#supprimerSiAuteur}, qui consulte la ligne
     * {@code media_files} avant de venir ici. C'est ce partage qui a manqué
     * jusqu'au lot 0 : trois routes appelaient directement cette méthode avec un
     * chemin reçu ou relu, sans personne à comparer.
     */
    @Override
    public void delete(String filename) throws IOException {
        Optional<Path> cible = borner(filename);
        if (cible.isEmpty()) {
            log.warn("Suppression refusée, chemin hors du stockage : {}", filename);
            return;
        }
        if (Files.exists(cible.get())) {
            Files.delete(cible.get());
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
