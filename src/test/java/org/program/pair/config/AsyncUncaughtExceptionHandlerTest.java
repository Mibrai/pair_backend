package org.program.pair.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que le gestionnaire d'exception asynchrone écrit, et surtout <b>ce qu'il
 * n'écrit pas</b>.
 *
 * <p>Une méthode {@code @Async} au retour {@code void} ne rend aucune
 * {@code Future} : personne ne peut attraper son exception, et sans gestionnaire
 * on lit une trace sans savoir quel chemin a rompu. Le gestionnaire nomme donc
 * la classe et la méthode.
 *
 * <p>Il s'arrête là, et c'est l'objet du second test. La signature des méthodes
 * concernées est
 * {@code notify(UUID userId, UUID actorId, NotificationType type, Map payload)} :
 * journaliser {@code params} déverserait dans les journaux des identifiants
 * d'utilisateurs et des charges utiles entières — titres de créneaux, extraits
 * de messages, numéros de contact d'urgence (P-BS-19). L'assertion négative est
 * là pour que l'ajout « pour déboguer » de {@code params} rougisse.
 *
 * <p>Test unitaire : aucun contexte Spring, le gestionnaire est demandé à une
 * instance nue d'{@link AsyncConfig}.
 */
@ExtendWith(OutputCaptureExtension.class)
class AsyncUncaughtExceptionHandlerTest {

    /** Improbable dans n'importe quelle autre ligne de journal. */
    private static final String CHARGE_CONFIDENTIELLE = "charge-utile-confidentielle-9f2c";

    private final AsyncUncaughtExceptionHandler gestionnaire =
        new AsyncConfig().getAsyncUncaughtExceptionHandler();

    /**
     * La vraie signature, prise par réflexion : si elle change, le test le dit
     * plutôt que de continuer à éprouver une méthode inventée.
     */
    private Method methodeNotify() throws NoSuchMethodException {
        return NotificationService.class.getMethod(
            "notify", UUID.class, UUID.class, NotificationType.class, Map.class);
    }

    @Test
    void gestionnaire_doitJournaliserLaClasseEtLaMethode_quandUneTacheAsynchroneLeve(CapturedOutput sortie)
            throws NoSuchMethodException {
        gestionnaire.handleUncaughtException(
            new IllegalStateException("panne simulée"),
            methodeNotify(),
            UUID.randomUUID(), UUID.randomUUID(), NotificationType.NEW_MESSAGE, Map.of());

        assertThat(sortie.getOut() + sortie.getErr())
            .contains("NotificationService")
            .contains("notify")
            // La cause reste jointe : sans elle on saurait quel chemin a rompu,
            // jamais pourquoi.
            .contains("panne simulée")
            .contains("IllegalStateException");
    }

    @Test
    void gestionnaire_doitTaireLesParametres_quandIlsPortentDesIdentifiantsEtUneChargeUtile(CapturedOutput sortie)
            throws NoSuchMethodException {
        UUID destinataire = UUID.randomUUID();
        UUID acteur = UUID.randomUUID();

        gestionnaire.handleUncaughtException(
            new IllegalStateException("panne simulée"),
            methodeNotify(),
            destinataire, acteur, NotificationType.NEW_MESSAGE,
            Map.of("text", CHARGE_CONFIDENTIELLE, "senderId", acteur));

        String journal = sortie.getOut() + sortie.getErr();
        // La ligne est bien partie : sans cette assertion, les trois suivantes
        // passeraient aussi sur une sortie vide.
        assertThat(journal).contains("NotificationService");
        assertThat(journal).doesNotContain(CHARGE_CONFIDENTIELLE);
        assertThat(journal).doesNotContain(destinataire.toString());
        assertThat(journal).doesNotContain(acteur.toString());
    }
}
