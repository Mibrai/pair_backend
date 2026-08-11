package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Couvre {@code POST /api/programs/{id}/duplicate}, livré sans test au lot 7
 * (contrat B4), et le revirement décidé le 2026-08-11 : <b>la copie ne reprend
 * plus la couverture de son original</b>.
 *
 * <p>Deux exigences distinctes, et la seconde ne découle pas de la première :
 * <ul>
 *   <li>la copie n'a pas d'image ({@code imageUrl == null}) — visible par le
 *       client ;</li>
 *   <li><b>aucun octet n'a été écrit</b> sur le stockage pour elle — invisible
 *       par le client, et pourtant c'est tout l'objet de la demande. Le
 *       contournement mobile actuel (un {@code DELETE} juste après) satisfait la
 *       première sans rien économiser sur la seconde.</li>
 * </ul>
 *
 * <p>Le dernier test reproduit l'incident de production : un fichier disparu du
 * stockage, sa référence toujours en base. La duplication doit réussir malgré
 * tout — copier des métadonnées et des créneaux n'a jamais eu besoin de ce
 * fichier — là où elle échouait en 4xx « File not found », transaction annulée,
 * aucune copie créée.
 */
class ProgramDuplicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired UserRepository userRepository;
    @Autowired StorageService storageService;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    @Test
    void duplication_devraitCopierMetadonneesEtCreneaux() {
        String token = registerAndLogin("dup-metadata@pair.app");
        Program original = createProgramWithSchedule("dup-metadata@pair.app", "Yoga du matin");

        ProgramDto copy = duplicate(token, original.getId(), null);

        assertThat(copy.id()).isNotEqualTo(original.getId());
        assertThat(copy.title()).isEqualTo("Yoga du matin (copie)");
        assertThat(copy.status()).isEqualTo(ProgramStatus.DRAFT.name());
        assertThat(copy.isPublic()).isFalse();
        assertThat(copy.schedules()).hasSize(1);
        assertThat(copy.schedules().get(0).placeName()).isEqualTo("Parc Monceau");
    }

    @Test
    void duplication_devraitUtiliserLeTitreDemande() {
        String token = registerAndLogin("dup-title@pair.app");
        Program original = createProgramWithSchedule("dup-title@pair.app", "Yoga du soir");

        ProgramDto copy = duplicate(token, original.getId(), "Yoga du dimanche");

        assertThat(copy.title()).isEqualTo("Yoga du dimanche");
    }

    @Test
    void duplication_devraitRendreUneCopieSansCouverture_etNEcrireAucunOctet() throws IOException {
        String token = registerAndLogin("dup-cover@pair.app");
        Program original = createProgramWithSchedule("dup-cover@pair.app", "Yoga avec couverture");
        String coverUrl = uploadCover(token, original.getId());

        Set<String> before = storedProgramImages();
        ProgramDto copy = duplicate(token, original.getId(), null);
        Set<String> after = storedProgramImages();

        assertThat(copy.imageUrl()).isNull();
        // L'exigence chiffrée de la demande : dupliquer ne doit pas doubler les
        // octets stockés. Un fichier de plus ici, et l'économie recherchée est nulle.
        assertThat(after).isEqualTo(before);

        // Et l'original garde la sienne : ne plus copier n'est pas déplacer.
        ProgramDto refreshed = getProgram(token, original.getId());
        assertThat(refreshed.imageUrl()).isEqualTo(coverUrl);
    }

    @Test
    void duplication_devraitReussir_quandLeFichierDeCouvertureADisparuDuStockage() throws IOException {
        String token = registerAndLogin("dup-orphan@pair.app");
        Program original = createProgramWithSchedule("dup-orphan@pair.app", "Yoga couverture disparue");
        String coverUrl = uploadCover(token, original.getId());

        // L'incident de production : les octets s'en vont, la référence reste.
        Files.delete(storageService.load(coverUrl.substring("/api/media/files/".length())));

        ProgramDto copy = duplicate(token, original.getId(), null);

        assertThat(copy.id()).isNotEqualTo(original.getId());
        assertThat(copy.imageUrl()).isNull();
        assertThat(copy.schedules()).hasSize(1);
    }

    @Test
    void lectureProgramme_devraitRendreImageNulle_quandLeFichierADisparu() throws IOException {
        String token = registerAndLogin("dup-guard@pair.app");
        Program original = createProgramWithSchedule("dup-guard@pair.app", "Yoga référence orpheline");
        String coverUrl = uploadCover(token, original.getId());

        assertThat(getProgram(token, original.getId()).imageUrl()).isEqualTo(coverUrl);

        Files.delete(storageService.load(coverUrl.substring("/api/media/files/".length())));

        // Référence orpheline : l'URL est toujours en base, mais la servir
        // donnerait au client une adresse qui répond 404 à chaque affichage.
        assertThat(getProgram(token, original.getId()).imageUrl()).isNull();
        assertThat(programRepository.findById(original.getId()).orElseThrow().getImageUrl())
            .as("la colonne n'est pas modifiée : un stockage restauré redevient lisible sans migration")
            .isEqualTo(coverUrl);
    }

    // — helpers —

    private ProgramDto duplicate(String token, UUID programId, String title) {
        var request = webTestClient.post()
            .uri("/api/programs/" + programId + "/duplicate")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON);

        var spec = title != null
            ? request.bodyValue("{\"title\":\"" + title + "\"}").exchange()
            : request.exchange();

        ProgramDto copy = spec
            .expectStatus().isCreated()
            .expectBody(ProgramDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(copy).isNotNull();
        return copy;
    }

    private ProgramDto getProgram(String token, UUID programId) {
        ProgramDto program = webTestClient.get()
            .uri("/api/programs/" + programId)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProgramDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(program).isNotNull();
        return program;
    }

    private String uploadCover(String token, UUID programId) throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(validPngBytes()) {
            @Override
            public String getFilename() {
                return "cover.png";
            }
        }).contentType(MediaType.IMAGE_PNG);

        ProgramDto program = webTestClient.post()
            .uri("/api/programs/" + programId + "/image/upload")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .exchange()
            .expectStatus().isOk()
            .expectBody(ProgramDto.class)
            .returnResult()
            .getResponseBody();

        assertThat(program).isNotNull();
        assertThat(program.imageUrl()).startsWith("/api/media/files/program_image/");
        return program.imageUrl();
    }

    /** Noms des fichiers présents dans le répertoire des couvertures de programme. */
    private Set<String> storedProgramImages() throws IOException {
        Path dir = storageService.load("program_image");
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
        }
    }

    private Program createProgramWithSchedule(String email, String title) {
        User owner = userRepository.findByEmail(email).orElseThrow();
        Activity yoga = activityRepository.findBySlug("yoga").orElseThrow();

        UserActivity userActivity = userActivityRepository.save(
            UserActivity.builder().user(owner).activity(yoga).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title(title)
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Parc Monceau")
            .placeType(PlaceType.PUBLIC)
            .location(geometryFactory.createPoint(new Coordinate(2.3522, 48.8566)))
            .startsAt(Instant.now().plus(7, ChronoUnit.DAYS))
            .endsAt(Instant.now().plus(7, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS))
            .build());

        return program;
    }

    private byte[] validPngBytes() throws IOException {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String registerAndLogin(String email) {
        RegisterRequest registerReq = new RegisterRequest(email, "Password123!", email.split("@")[0]);
        webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(registerReq)
            .exchange()
            .expectStatus().isCreated();

        AuthResponse authResponse = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange()
            .expectStatus().isOk()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(authResponse).isNotNull();
        return authResponse.accessToken();
    }
}
