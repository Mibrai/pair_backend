package org.program.pair.shared.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.program.pair.domain.email.GabaritEmail;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * P-BS-19 — un envoi raté ne journalise pas l'adresse de son destinataire.
 *
 * <p>Le cas qui a motivé la fiche est le contact de confiance : une personne qui
 * n'a pas de compte, désignée par quelqu'un d'autre, et dont l'adresse finissait
 * en clair dans les journaux de production au premier refus du fournisseur.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailServiceJournauxTest {

    private static final String CONTACT = "marie.dupont@posteo.de";

    private EmailService serviceDontLEnvoiEchoue() {
        ResendEmailService resend = mock(ResendEmailService.class);
        doReturn(true).when(resend).isEnabled();
        doReturn(false).when(resend).sendHtmlEmail(anyString(), anyString(), anyString());

        GabaritEmail gabarit = new GabaritEmail();
        ReflectionTestUtils.setField(gabarit, "publicBaseUrl", "https://lien.meetdo.fun");
        return new EmailService(resend, mock(UserRepository.class), mock(OutboxService.class),
            new Messages(new StaticMessageSource()), gabarit, new MockEnvironment(),
            mock(org.program.pair.repository.DeviceTokenRepository.class));
    }

    @Test
    void unEchecDEnvoiAUnContactDeConfiance_neJournalisePasSonAdresse(CapturedOutput sortie) {
        serviceDontLEnvoiEchoue().sendGuardianConsentEmail(CONTACT, "Lena", "https://lien.meetdo.fun/g/abc");

        assertThat(sortie.getAll()).as("l'échec est bien journalisé").contains("guardian consent");
        assertThat(sortie.getAll()).doesNotContain(CONTACT).contains("m***@posteo.de");
    }

    @Test
    void unEchecDeReinitialisation_neJournalisePasLAdresse(CapturedOutput sortie) {
        serviceDontLEnvoiEchoue().sendPasswordResetEmail(CONTACT, "jeton-de-reinitialisation");

        assertThat(sortie.getAll()).contains("password reset");
        assertThat(sortie.getAll()).doesNotContain(CONTACT, "jeton-de-reinitialisation");
    }
}
