package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.PlaceType;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat B12/B2/B10 du payload : identifiants de deep-link, libellés pour
 * composer la phrase, et categoryColorRamp — la même graine de teinte que la
 * carte. Tout en scalaires JSON, jamais de clé à valeur nulle.
 */
class NotificationPayloadTest {

    @Test
    void ofSchedule_doitPorterDeepLink_libelles_etColorRamp() {
        Schedule slot = slot("orange-red");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        // Identifiants de deep-link, en chaînes — le payload traverse jsonb.
        assertThat(payload.get("scheduleId")).isEqualTo(slot.getId().toString());
        assertThat(payload.get("programId")).isEqualTo(slot.getProgram().getId().toString());
        assertThat(payload.get("userActivityId"))
            .isEqualTo(slot.getProgram().getUserActivity().getId().toString());
        // De quoi écrire la phrase sans title/message serveur (B10, option 2).
        assertThat(payload.get("programTitle")).isEqualTo("Yoga du soir");
        assertThat(payload.get("activityName")).isEqualTo("Yoga");
        assertThat(payload.get("placeName")).isEqualTo("Studio");
        // La même graine de teinte que la carte (B2).
        assertThat(payload.get("categoryColorRamp")).isEqualTo("orange-red");
        // L'instant de la séance, sous la clé que NotificationDto relit.
        assertThat(payload.get("sessionAt")).isEqualTo(slot.getStartsAt().toString());
    }

    @Test
    void valeurNulle_donneClefAbsente_pasClefANull() {
        Schedule slot = slot(null); // catégorie sans colorRamp
        slot.getProgram().getUserActivity().getActivity().setCategory(null);

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload).doesNotContainKeys("categoryId", "categoryColorRamp");
        assertThat(payload.values()).doesNotContainNull();
    }

    @Test
    void with_normaliseUuidEtInstantEnChaines() {
        UUID id = UUID.randomUUID();
        Instant when = Instant.parse("2026-08-17T18:30:00Z");

        Map<String, Object> payload = NotificationPayload.empty()
            .with("someId", id)
            .with("someAt", when)
            .build();

        assertThat(payload.get("someId")).isEqualTo(id.toString());
        assertThat(payload.get("someAt")).isEqualTo("2026-08-17T18:30:00Z");
    }

    // ─── N1 — l'identité de la séance, exigée par le template client ──────────

    @Test
    void ofSchedule_doitPorterLIdentiteDeLaSeance_auteurIconeEtPlageHoraire() {
        Schedule slot = slot("orange-red");
        slot.setEndsAt(Instant.parse("2026-08-17T20:00:00Z"));

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        // L'auteur : de quoi écrire la ligne d'auteur et poser son avatar.
        assertThat(payload.get("authorId"))
            .isEqualTo(slot.getProgram().getUserActivity().getUser().getId().toString());
        assertThat(payload.get("authorName")).isEqualTo("Lena Müller");
        assertThat(payload.get("authorAvatarUrl")).isEqualTo("https://cdn/avatars/lena.jpg");
        // L'icône de catégorie, résolue côté client comme l'est colorRamp.
        assertThat(payload.get("categoryIcon")).isEqualTo("pool");
        // Une plage, pas un instant : le client affiche « 18:30 – 20:00 ».
        assertThat(payload.get("endsAt")).isEqualTo("2026-08-17T20:00:00Z");
    }

    @Test
    void ofProgram_doitPrefererOrganizerName_commeLaFicheDuProgramme() {
        // Le programme nomme son organisateur : trois surfaces (fiche, carte,
        // notification) doivent afficher le même auteur, sinon une séance a deux
        // auteurs selon l'écran d'où on la regarde.
        Schedule slot = slot("orange-red");
        slot.getProgram().setOrganizerName("Le Club des Nageurs");
        slot.getProgram().setOrganizerAvatarUrl("https://cdn/avatars/club.jpg");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload.get("authorName")).isEqualTo("Le Club des Nageurs");
        assertThat(payload.get("authorAvatarUrl")).isEqualTo("https://cdn/avatars/club.jpg");
        // L'identifiant reste celui de la personne : c'est la clé de navigation,
        // pas un libellé.
        assertThat(payload.get("authorId"))
            .isEqualTo(slot.getProgram().getUserActivity().getUser().getId().toString());
    }

    @Test
    void creneauSansFinDeclaree_neDoitPasPorterEndsAt() {
        // endsAt est facultatif en base. Absent, le client affiche l'heure de
        // début seule — son repli documenté.
        Map<String, Object> payload = NotificationPayload.ofSchedule(slot("ocean-blue")).build();

        assertThat(payload).doesNotContainKey("endsAt");
    }

    // ─── N1 — l'adresse ne sort que si elle est diffusable ────────────────────

    @Test
    void lieuPublic_doitPorterLAdresse() {
        Schedule slot = slot("ocean-blue");
        slot.setPlaceType(PlaceType.PUBLIC);
        slot.setLocation(POINT);
        slot.setAddressPublic("Piscine du Rhône, 8 quai Claude Bernard, Lyon");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload.get("addressPublic"))
            .isEqualTo("Piscine du Rhône, 8 quai Claude Bernard, Lyon");
    }

    @Test
    void lieuPriveNonPartage_neDoitJamaisPorterLAdresse() {
        // Le cas qui compte : une notification s'affiche sur un écran verrouillé.
        // La colonne porte l'adresse, pas le droit de la montrer — et un payload
        // est composé une fois pour N destinataires, donc sans demandeur à qui
        // appliquer la règle complète.
        Schedule slot = slot("ocean-blue");
        slot.setPlaceType(PlaceType.PRIVATE);
        slot.setShowExactAddress(false);
        slot.setLocation(POINT);
        slot.setAddressPublic("12 rue des Lilas, appartement 4B, Lyon");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload).doesNotContainKey("addressPublic");
        assertThat(payload.values().stream().map(String::valueOf))
            .noneMatch(v -> v.contains("Lilas"));
        // Le reste de la carte s'affiche quand même : c'est la ligne d'adresse
        // qui disparaît, pas la notification.
        assertThat(payload.get("programTitle")).isEqualTo("Yoga du soir");
    }

    @Test
    void lieuPrive_maisAdresseExacteAssumee_doitPorterLAdresse() {
        Schedule slot = slot("ocean-blue");
        slot.setPlaceType(PlaceType.PRIVATE);
        slot.setShowExactAddress(true);
        slot.setLocation(POINT);
        slot.setAddressPublic("12 rue des Lilas, Lyon");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload.get("addressPublic")).isEqualTo("12 rue des Lilas, Lyon");
    }

    @Test
    void lieuEnLigne_naJamaisDAdresse() {
        Schedule slot = slot("ocean-blue");
        slot.setPlaceType(PlaceType.ONLINE);
        slot.setAddressPublic("ne devrait pas sortir");

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload).doesNotContainKey("addressPublic");
    }

    @Test
    void programmeSansAuteur_neDoitPasCasser() {
        // UserActivity sans utilisateur chargé : les clés d'auteur sautent, le
        // reste du payload tient. Une notification incomplète vaut mieux qu'une
        // notification qui n'est jamais écrite.
        Schedule slot = slot("ocean-blue");
        slot.getProgram().getUserActivity().setUser(null);

        Map<String, Object> payload = NotificationPayload.ofSchedule(slot).build();

        assertThat(payload).doesNotContainKeys("authorId", "authorName", "authorAvatarUrl");
        assertThat(payload.get("programTitle")).isEqualTo("Yoga du soir");
        assertThat(payload.values()).doesNotContainNull();
    }

    private static final Point POINT =
        new GeometryFactory(new PrecisionModel(), 4326).createPoint(new Coordinate(4.84, 45.75));

    private static Schedule slot(String colorRamp) {
        Category category = Category.builder()
            .id(UUID.randomUUID()).name("Sports").colorRamp(colorRamp).icon("pool").build();
        Activity activity = Activity.builder()
            .id(UUID.randomUUID()).name("Yoga").category(category).build();

        User author = new User();
        author.setId(UUID.randomUUID());
        author.setDisplayName("Lena Müller");
        author.setAvatarUrl("https://cdn/avatars/lena.jpg");

        UserActivity ua = new UserActivity();
        ua.setId(UUID.randomUUID());
        ua.setActivity(activity);
        ua.setUser(author);

        Program program = new Program();
        program.setId(UUID.randomUUID());
        program.setTitle("Yoga du soir");
        program.setUserActivity(ua);

        Schedule slot = new Schedule();
        slot.setId(UUID.randomUUID());
        slot.setProgram(program);
        slot.setPlaceName("Studio");
        slot.setStartsAt(Instant.parse("2026-08-17T18:30:00Z"));
        return slot;
    }
}
