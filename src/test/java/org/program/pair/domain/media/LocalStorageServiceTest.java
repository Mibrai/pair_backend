package org.program.pair.domain.media;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Le stockage local : ce qu'il refuse de résoudre, et ce qu'il enregistre.
 *
 * <p><b>Les deux défauts fermés ici</b> (fiche P-BS-11).
 *
 * <p>1. {@code load()} faisait {@code rootLocation.resolve(filename)} sans
 * {@code normalize()} ni vérification de racine. {@code Path.resolve} d'une
 * chaîne <b>absolue</b> rend cette chaîne telle quelle : {@code load("/etc/passwd")}
 * rendait donc {@code /etc/passwd}, et {@code load("user_avatar/../../x")}
 * sortait du stockage. Seul le {@code StrictHttpFirewall} de Spring Security
 * s'interposait — une protection d'une autre couche, qui ne couvre aucun
 * appelant interne, et dont dépendre revenait à confier au cadre HTTP une règle
 * de stockage.
 *
 * <p>2. L'extension enregistrée venait du <b>nom envoyé par le client</b>, et le
 * type de contenu servi était ensuite déduit de cette même extension. Toute la
 * chaîne était donc pilotée par une chaîne de caractères fournie par l'appelant.
 *
 * <p>Un test unitaire et non d'intégration : ces deux règles sont entièrement
 * dans cette classe, elles ne touchent ni la base ni HTTP, et les éprouver ici
 * coûte quelques millisecondes au lieu d'un contexte Spring.
 */
class LocalStorageServiceTest {

    @TempDir Path racine;

    /**
     * Les trois formes de sortie de racine, y compris celle qui ne ressemble pas
     * à une attaque : un chemin qui commence bien par un répertoire légitime.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "/etc/passwd",
        "../x",
        "../../etc/passwd",
        "user_avatar/../../x",
        "user_avatar/../../../etc/passwd"
    })
    void load_devraitRefuserLeChemin_quandIlSortDuStockage(String chemin) {
        LocalStorageService service = service();

        assertThatThrownBy(() -> service.load(chemin))
            .isInstanceOf(ResourceNotFoundException.class)
            .extracting(e -> ((ResourceNotFoundException) e).getErrorCode())
            // Introuvable, jamais interdit : un refus distinct dirait à qui sonde
            // les chemins lesquels existent.
            .isEqualTo(ErrorCode.MEDIA_FILE_NOT_FOUND);
    }

    /**
     * Le corollaire qui protège les appelants internes : {@code exists} ne doit
     * pas <i>lever</i> sur un chemin douteux, seulement répondre non. C'est le
     * contrat de {@code StoredImageResolver} : sérialiser un programme dont la
     * couverture a une URL abîmée ne doit pas faire échouer la lecture du
     * programme entier.
     */
    @Test
    void exists_devraitRepondreNonSansLever_quandLeCheminSortDuStockage() {
        LocalStorageService service = service();

        assertThat(service.exists("/etc/passwd")).isFalse();
        assertThat(service.exists("user_avatar/../../x")).isFalse();
        assertThat(service.exists(null)).isFalse();
        assertThat(service.exists("  ")).isFalse();
    }

    @Test
    void delete_devraitIgnorerLeChemin_quandIlSortDuStockage() throws IOException {
        Path dehors = Files.createFile(racine.getParent().resolve("dehors-" + UUID.randomUUID() + ".txt"));
        LocalStorageService service = service();

        // Le chemin remonte d'un cran : sans bornage, Files.delete l'effaçait.
        service.delete("../" + dehors.getFileName());

        assertThat(Files.exists(dehors)).isTrue();
        Files.deleteIfExists(dehors);
    }

    @Test
    void load_devraitResoudreLeChemin_quandIlResteSousLaRacine() {
        LocalStorageService service = service();

        Path resolu = service.load("user_avatar/portrait.jpg");

        assertThat(resolu).isEqualTo(racine.toAbsolutePath().normalize().resolve("user_avatar/portrait.jpg"));
    }

    /**
     * Le fichier envoyé sous un nom trompeur est enregistré avec l'extension de
     * son <b>type réel</b>. Sans quoi {@code <uuid>.html} était servi avec un
     * {@code Content-Type: text/html} déduit de cette extension.
     */
    @Test
    void store_devraitEnregistrerLExtensionDuTypeDetecte_quandLeNomEnvoyeMent() throws IOException {
        MediaFileRepository repository = mock(MediaFileRepository.class);
        LocalStorageService service = service(repository);

        String chemin = service.store(
            new MockMultipartFile("file", "portrait.html", "text/html", pngValide()),
            UUID.randomUUID(),
            MediaType.USER_AVATAR);

        assertThat(chemin).startsWith("user_avatar/").endsWith(".png");
        assertThat(chemin).doesNotContain(".html");
    }

    /**
     * Un type que nous ne servons pas retombe sur {@code .bin}, donc sur
     * {@code application/octet-stream} à la lecture : jamais de rendu par le
     * navigateur. Repli et non refus — le refus des types non autorisés
     * appartient à {@code MediaValidator}, qui s'exécute avant.
     */
    @Test
    void store_devraitReplierSurBin_quandLeTypeDetecteNestPasServi() throws IOException {
        LocalStorageService service = service();

        String chemin = service.store(
            new MockMultipartFile("file", "script.jpg", "image/jpeg",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)),
            UUID.randomUUID(),
            MediaType.PROGRAM_IMAGE);

        assertThat(chemin).endsWith(".bin");
    }

    /**
     * Le cœur de P-BS-01 : le dépôt écrit qui a déposé. Et il l'écrit <b>ici</b>,
     * dans le service, et non depuis un rappel d'entité JPA — voir
     * {@code domain/indexation/ApresCommit} pour ce que coûte une écriture
     * soumise pendant un commit.
     */
    @Test
    void store_devraitEnregistrerLeDeposant_quandUnFichierEstDepose() throws IOException {
        MediaFileRepository repository = mock(MediaFileRepository.class);
        LocalStorageService service = service(repository);
        UUID deposant = UUID.randomUUID();

        String chemin = service.store(
            new MockMultipartFile("file", "portrait.png", "image/png", pngValide()),
            deposant,
            MediaType.USER_AVATAR);

        ArgumentCaptor<MediaFile> capture = ArgumentCaptor.forClass(MediaFile.class);
        verify(repository).save(capture.capture());

        MediaFile ligne = capture.getValue();
        assertThat(ligne.getPath()).isEqualTo(chemin);
        assertThat(ligne.getUploadedBy()).isEqualTo(deposant);
        assertThat(ligne.getPurpose()).isEqualTo(MediaPurpose.AVATAR);
        assertThat(ligne.deposePar(deposant)).isTrue();
        assertThat(ligne.deposePar(UUID.randomUUID())).isFalse();
        // Un compte supprimé laisse uploaded_by à NULL : personne ne peut alors
        // supprimer, et surtout pas « n'importe qui ».
        assertThat(ligne.deposePar(null)).isFalse();
    }

    @Test
    void store_devraitEcrireLesOctetsIntacts_malgreLaDetectionDeType() throws IOException {
        LocalStorageService service = service();
        byte[] octets = pngValide();

        String chemin = service.store(
            new MockMultipartFile("file", "portrait.png", "image/png", octets),
            UUID.randomUUID(),
            MediaType.USER_AVATAR);

        // La détection consomme le début du flux : sans marque et retour en
        // arrière, le fichier écrit serait tronqué de son en-tête.
        assertThat(Files.readAllBytes(service.load(chemin))).isEqualTo(octets);
    }

    private LocalStorageService service() {
        return service(mock(MediaFileRepository.class));
    }

    private LocalStorageService service(MediaFileRepository repository) {
        LocalStorageService service = new LocalStorageService(racine.toString(), repository);
        try {
            service.init();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return service;
    }

    private byte[] pngValide() throws IOException {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
