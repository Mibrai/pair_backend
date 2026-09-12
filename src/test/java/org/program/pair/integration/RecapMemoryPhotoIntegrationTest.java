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
import org.program.pair.domain.media.MediaFile;
import org.program.pair.domain.media.MediaFileRepository;
import org.program.pair.domain.media.MediaFileService;
import org.program.pair.domain.media.MediaPurpose;
import org.program.pair.domain.program.ParticipationStatus;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotParticipation;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.reactive.function.BodyInserters;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La photo de souvenir : elle doit venir de son auteur, et de chez nous.
 *
 * <p><b>Le défaut fermé ici</b> (volet serveur de P-MS-01, étape 7 de P-BS-01).
 * {@code PATCH /api/slots/{id}/recap/photo} n'est pas un chemin d'upload — il
 * <i>rattache</i> une URL déjà téléversée. Mais « déjà téléversée » n'était pas
 * vérifié : l'URL arrivait comme une chaîne libre, {@code strip()}, puis rangée
 * dans {@code attendances.memory_photo_url}. Une adresse externe finissait donc
 * sur une carte-souvenir potentiellement <b>publique</b>, et l'application
 * charge ces images avec son client Dio authentifié — le jeton de chaque
 * lecteur partait vers l'hôte choisi par qui avait posté l'URL.
 *
 * <p>Le décor est celui qu'exige la route : un créneau terminé, une présence
 * confirmée, et la fenêtre de contribution encore ouverte.
 */
class RecapMemoryPhotoIntegrationTest extends AbstractIntegrationTest {

    @Autowired UserRepository userRepository;
    @Autowired ActivityRepository activityRepository;
    @Autowired UserActivityRepository userActivityRepository;
    @Autowired ProgramRepository programRepository;
    @Autowired ScheduleRepository scheduleRepository;
    @Autowired SlotParticipationRepository participationRepository;
    @Autowired MediaFileRepository mediaFileRepository;
    @Autowired JdbcTemplate jdbc;

    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    /** Le test que la fiche demande nommément. */
    @Test
    void uneUrlExterne_devraitEtreRefusee_commePhotoDeSouvenir() {
        Decor decor = creneauTermine("photo-externe");

        confirmerPresence(decor.hoteToken(), decor.scheduleId());

        webTestClient.patch().uri("/api/slots/{id}/recap/photo", decor.scheduleId())
            .headers(h -> h.setBearerAuth(decor.hoteToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of(
                "photoUrl", "https://evil.tld/api/media/files/program_image/x.jpg",
                "isPublic", true))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");
    }

    /** Le fichier d'un autre compte n'est pas davantage recevable. */
    @Test
    void leFichierDunAutre_devraitEtreRefuse_commePhotoDeSouvenir() throws IOException {
        Decor decor = creneauTermine("photo-tiers");
        confirmerPresence(decor.hoteToken(), decor.scheduleId());

        String fichierDuTiers = deposerImage(decor.invitToken());

        webTestClient.patch().uri("/api/slots/{id}/recap/photo", decor.scheduleId())
            .headers(h -> h.setBearerAuth(decor.hoteToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("photoUrl", fichierDuTiers, "isPublic", false))
            .exchange().expectStatus().isBadRequest()
            .expectBody().jsonPath("$.code").isEqualTo("MEDIA_URL_INVALID");
    }

    /**
     * Le chemin nominal, et le rattrapage V109 de cette colonne par la même
     * occasion : la photo de souvenir est portée par
     * {@code attendances.memory_photo_url} (V54) et son déposant est
     * {@code attendances.user_id} (V41) — pas là où la fiche la cherchait.
     */
    @Test
    void saProprePhoto_devraitEtreAcceptee_etRattacheeAuBonUsage() throws IOException {
        Decor decor = creneauTermine("photo-mienne");
        confirmerPresence(decor.hoteToken(), decor.scheduleId());

        String fichier = deposerImage(decor.hoteToken());

        webTestClient.patch().uri("/api/slots/{id}/recap/photo", decor.scheduleId())
            .headers(h -> h.setBearerAuth(decor.hoteToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("photoUrl", fichier, "isPublic", true))
            .exchange().expectStatus().isOk();

        String chemin = fichier.substring(MediaFileService.URL_PREFIX.length());
        MediaFile ligne = mediaFileRepository.findById(chemin).orElseThrow();
        assertThat(ligne.getUploadedBy()).isEqualTo(decor.hoteId());
        assertThat(ligne.getPurpose())
            .as("le rattachement fixe l'usage : au dépôt, rien ne le disait")
            .isEqualTo(MediaPurpose.RECAP_PHOTO);

        // Le rattrapage de V109 retrouverait ce même déposant à partir de la
        // seule colonne d'attendances, si la ligne n'existait pas encore.
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM attendances WHERE memory_photo_url = ?", UUID.class, fichier))
            .isEqualTo(decor.hoteId());
    }

    /** Retirer sa photo reste possible : une URL nulle n'a rien à rattacher. */
    @Test
    void uneUrlNulle_devraitRetirerLeSouvenir_sansRienExiger() {
        Decor decor = creneauTermine("photo-retrait");
        confirmerPresence(decor.hoteToken(), decor.scheduleId());

        webTestClient.patch().uri("/api/slots/{id}/recap/photo", decor.scheduleId())
            .headers(h -> h.setBearerAuth(decor.hoteToken()))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("isPublic", false))
            .exchange().expectStatus().isOk();
    }

    // ------------------------------------------------------------------ outils

    private record Decor(UUID scheduleId, UUID hoteId, String hoteToken, String invitToken) {}

    /**
     * Un créneau non récurrent dont la séance vient de se terminer.
     *
     * <p>Non récurrent à dessein : la route n'exige qu'une occurrence terminée
     * et une fenêtre ouverte, et le décor hebdomadaire de
     * {@code RecapOccurrenceIntegrationTest} demanderait en plus un passage du
     * job de report — du bruit pour ce que ces tests éprouvent.
     */
    private Decor creneauTermine(String prefixe) {
        String emailHote = uniqueEmail(prefixe + "-hote");
        String hoteToken = inscrire(emailHote);
        User hote = userRepository.findByEmail(emailHote).orElseThrow();

        String emailInvit = uniqueEmail(prefixe + "-invit");
        String invitToken = inscrire(emailInvit);
        User invit = userRepository.findByEmail(emailInvit).orElseThrow();

        Activity activity = activityRepository.findAll().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("Le catalogue de seed n'a aucune activité."));

        UserActivity userActivity = userActivityRepository.save(UserActivity.builder()
            .user(hote).activity(activity).visibleOnMap(true).build());

        Program program = programRepository.save(Program.builder()
            .userActivity(userActivity)
            .title("Séance souvenir " + UUID.randomUUID().toString().substring(0, 8))
            .status(ProgramStatus.ACTIVE)
            .isPublic(true)
            .build());

        Schedule schedule = scheduleRepository.save(Schedule.builder()
            .program(program)
            .placeName("Parc de la Tête d'Or")
            .placeType(PlaceType.PUBLIC)
            .addressPublic("Boulevard des Belges")
            .city("Lyon")
            .location(geometryFactory.createPoint(new Coordinate(4.85, 45.77)))
            .startsAt(Instant.now().minus(3, ChronoUnit.HOURS))
            .endsAt(Instant.now().minus(2, ChronoUnit.HOURS))
            .status(SlotStatus.OPEN)
            .isOpenToPartners(true)
            .build());

        participationRepository.save(SlotParticipation.builder()
            .schedule(schedule)
            .user(invit)
            .status(ParticipationStatus.CONFIRMED)
            .build());

        return new Decor(schedule.getId(), hote.getId(), hoteToken, invitToken);
    }

    private void confirmerPresence(String token, UUID scheduleId) {
        webTestClient.post().uri("/api/attendances/{scheduleId}/confirm", scheduleId)
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"wasPresent\":true}")
            .exchange().expectStatus().isOk();
    }

    private String deposerImage(String token) throws IOException {
        Map<?, ?> reponse = webTestClient.post().uri("/api/media/upload/image")
            .headers(h -> h.setBearerAuth(token))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(corpsPng().build()))
            .exchange().expectStatus().isOk()
            .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(reponse).isNotNull();
        String url = String.valueOf(reponse.get("url"));
        assertThat(url).startsWith(MediaFileService.URL_PREFIX);
        return url;
    }

    private MultipartBodyBuilder corpsPng() throws IOException {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(pngValide()) {
            @Override
            public String getFilename() {
                return "souvenir.png";
            }
        }).contentType(MediaType.IMAGE_PNG);
        return builder;
    }

    private byte[] pngValide() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private String inscrire(String email) {
        webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, "Password123!",
                "Sou" + UUID.randomUUID().toString().substring(0, 8)))
            .exchange().expectStatus().isCreated();

        AuthResponse auth = webTestClient.post().uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(email, "Password123!"))
            .exchange().expectStatus().isOk()
            .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }
}
