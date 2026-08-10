package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;

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

    private static Schedule slot(String colorRamp) {
        Category category = Category.builder()
            .id(UUID.randomUUID()).name("Sports").colorRamp(colorRamp).build();
        Activity activity = Activity.builder()
            .id(UUID.randomUUID()).name("Yoga").category(category).build();

        UserActivity ua = new UserActivity();
        ua.setId(UUID.randomUUID());
        ua.setActivity(activity);

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
