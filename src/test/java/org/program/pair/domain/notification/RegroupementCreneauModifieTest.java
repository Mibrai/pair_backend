package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trois corrections d'affilée du même créneau remplacent la bannière au lieu d'en
 * empiler trois (demande mobile du 02/09) — et rien d'autre n'est regroupé.
 */
class RegroupementCreneauModifieTest {

    @Test
    void uneModification_doitSeRegrouperParCreneau() {
        UUID id = UUID.randomUUID();

        assertThat(PushNotificationService.regroupementCreneauModifie(
            NotificationType.SCHEDULE_CHANGED, Map.of("scheduleId", id)))
            .isEqualTo("slot-changed-" + id);
    }

    @Test
    void uneAnnulation_neDoitPasEtreRegroupee_avecUneModification() {
        // Même créneau, même scheduleId : regroupées, l'annulation remplacerait la
        // modification — ou l'inverse, et c'est l'annulation qui disparaîtrait.
        assertThat(PushNotificationService.regroupementCreneauModifie(
            NotificationType.SLOT_CANCELLED, Map.of("scheduleId", UUID.randomUUID())))
            .isNull();
    }

    @Test
    void sansCreneau_rienNEstRegroupe() {
        assertThat(PushNotificationService.regroupementCreneauModifie(
            NotificationType.SCHEDULE_CHANGED, Map.of())).isNull();
        assertThat(PushNotificationService.regroupementCreneauModifie(
            NotificationType.SCHEDULE_CHANGED, null)).isNull();
    }
}
