package org.program.pair.domain.notification;

import com.google.firebase.messaging.Aps;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le routage time-sensitive : quelles notifications iOS doit afficher malgré un
 * mode Concentration, et lesquelles non.
 *
 * <p>C'est la logique qui décide de poser {@code interruption-level:
 * time-sensitive} dans la charge APNs. Le mode d'échec qu'elle évite est le plus
 * coûteux du module : sans ce niveau, une relance de retour est retenue par un
 * mode Concentration alors que l'alerte au proche, elle, part quand même.
 */
class NotificationTimeSensitiveTest {

    @Test
    void lesNotificationsDeVeilleQuiMettentEnMouvement_sontTimeSensitive() {
        assertThat(NotificationType.WATCH_RETURN_REMINDER.isTimeSensitive()).isTrue();
        assertThat(NotificationType.WATCH_ARRIVAL_PROMPT.isTimeSensitive()).isTrue();
        assertThat(NotificationType.WATCH_GUARDIAN_ALERT.isTimeSensitive()).isTrue();
    }

    @Test
    void lorganisateurDunPerduEnChemin_nEstPasTimeSensitive() {
        // Il ne met personne en mouvement dans l'instant : le réveiller malgré une
        // Concentration serait le faux positif que la retenue existe pour éviter.
        assertThat(NotificationType.WATCH_LOST_ORGANIZER.isTimeSensitive()).isFalse();
    }

    @Test
    void lesNotificationsOrdinaires_neSontPasTimeSensitive() {
        assertThat(NotificationType.NEW_MESSAGE.isTimeSensitive()).isFalse();
        assertThat(NotificationType.BADGE_EARNED.isTimeSensitive()).isFalse();
        assertThat(NotificationType.PROGRAM_REMINDER.isTimeSensitive()).isFalse();
    }

    @Test
    void aucunTypeQuiDecritUneFinDeVeille_nExiste() {
        // §5.5 : aucune notification ne doit décrire une fin de veille — « close »,
        // « alerte envoyée », « levée » — sinon les deux clôtures deviennent
        // distinguables sur un écran verrouillé. Le catalogue ne porte que des
        // types qui appellent une action ou une information d'entrée, jamais une
        // conclusion.
        for (NotificationType t : NotificationType.values()) {
            String n = t.name();
            assertThat(n)
                .as("type %s", n)
                .doesNotContain("CLOSED")
                .doesNotContain("RESOLVED")
                .doesNotContain("ESCALATED")
                .doesNotContain("LEVEE");
        }
    }

    @Test
    void lApsTimeSensitive_seConstruitSansErreur() {
        // La clé interruption-level est posée en donnée personnalisée de l'aps ;
        // on vérifie au moins que le builder l'accepte et produit un aps non nul.
        Aps aps = PushNotificationService.visibleApsTimeSensitive(3);
        assertThat(aps).isNotNull();
    }
}
