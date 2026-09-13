package org.program.pair.shared.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.program.pair.config.LocaleConfig;
import org.program.pair.domain.email.GabaritEmail;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * P-BS-09 — ce que l'absence de fournisseur d'e-mail doit provoquer, et ce
 * qu'elle ne doit surtout plus écrire.
 *
 * <p><b>Le défaut que ces tests ferment.</b> Sans Resend configuré,
 * {@link EmailService} se rabattait sur cinq {@code log.info} qui écrivaient le
 * lien entier — jeton compris pour la réinitialisation de mot de passe. Or
 * {@code application-railway.properties} pose {@code resend.enabled} à
 * {@code false} par défaut : une variable d'environnement oubliée suffisait à
 * déverser dans les journaux de la plateforme de quoi prendre n'importe quel
 * compte, sans mot de passe et sans trace applicative. Un journal se lit sans
 * les droits de la base, et il se conserve.
 *
 * <p>Deux verrous, et les deux sont ici : le serveur refuse de démarrer dans cet
 * état sous un profil de production, et le repli lui-même n'écrit plus rien
 * d'identifiant tant que {@code pair.email.journaliser-liens} n'est pas allumé —
 * ce qu'il n'est que dans {@code application-dev.properties}
 * ({@code ObservabiliteConfigurationTest} tient cette moitié-là du contrat).
 *
 * <p>Test unitaire et non d'intégration : rien ici ne touche la base ni le
 * réseau, et surtout le cache de contextes Spring est à son plafond de quatre
 * entrées — éprouver un refus de démarrage par un vrai contexte en aurait
 * demandé un cinquième, pour une question que {@code MockEnvironment} répond
 * mieux.
 */
@ExtendWith(OutputCaptureExtension.class)
class EmailServiceDemarrageTest {

    /** Improbable dans n'importe quelle autre ligne de journal. */
    private static final String JETON = "jeton-de-test-7c1f9a2e";
    private static final String ADRESSE = "lena.mueller@web.invalid";
    private static final String CLE = "re_une_cle_qui_ressemble_a_une_vraie";

    // ------------------------------------------------- le refus de démarrage

    /**
     * Le refus qui compte. Sous {@code prod} comme sous {@code railway}, un
     * fournisseur éteint n'est pas une dégradation du service : c'est le
     * déversement des liens dans les journaux, et l'absence totale d'e-mails
     * sans qu'une seule ligne rouge ne le dise.
     */
    @Test
    void leDemarrage_doitEchouer_quandResendEstEteintSousUnProfilDeProduction() {
        for (String profil : new String[] {"prod", "railway"}) {
            assertThatThrownBy(() -> demarrer(profil, false, CLE))
                .as("profil %s", profil)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resend.enabled=false")
                // Entre parenthèses, et non le nom nu : le message contient le
                // mot « production », dans lequel « prod » se trouve. Cherché
                // ainsi, le test passerait même si le profil n'était pas nommé
                // — c'est-à-dire même si premierProfilActif rendait null.
                .hasMessageContaining("(" + profil + ")")
                .hasMessageContaining("RESEND_ENABLED");
        }
    }

    /**
     * Le pire des trois états, et celui qu'un contrôle sur le seul drapeau
     * aurait laissé passer : l'application croit envoyer, le repli ne se
     * déclenche pas, et chaque appel échoue chez le fournisseur. Personne ne
     * reçoit rien, et rien ne ressemble à une panne de configuration.
     */
    @Test
    void leDemarrage_doitEchouer_quandLaCleEstAbsenteAlorsQueResendEstAllume() {
        assertThatThrownBy(() -> demarrer("railway", true, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("resend.api-key absente");
    }

    /** Configuré, le démarrage passe — c'est l'état attendu en production. */
    @Test
    void leDemarrage_doitReussir_quandResendEstAllumeEtLaCleePresente() {
        assertThatCode(() -> demarrer("railway", true, CLE)).doesNotThrowAnyException();
    }

    /**
     * En développement, pas de fournisseur et pas de refus : c'est le seul
     * endroit où le repli a un sens, puisqu'aucune boîte aux lettres ne reçoit
     * rien et que le lien du journal est le seul moyen d'ouvrir l'e-mail qu'on
     * vient de déclencher.
     */
    @Test
    void leDemarrage_doitReussir_enDeveloppementSansFournisseur() {
        assertThatCode(() -> demarrer("dev", false, "")).doesNotThrowAnyException();
        // Et sans aucun profil actif non plus : c'est le cas des tests, et de
        // quiconque lance le jar sans rien poser.
        assertThatCode(() -> demarrer(null, false, "")).doesNotThrowAnyException();
    }

    /**
     * {@code staging} démarre sans fournisseur, et c'est délibéré — la garde
     * s'appuie sur {@code Profils.PRODUCTION} et non sur
     * {@code Profils.DEPLOIEMENT}, contrairement à celle de
     * {@code JwtTokenProvider}.
     *
     * <p>La différence tient à ce qui est en jeu : une clé de signature absente
     * est contournable partout, staging compris ; un fournisseur d'e-mail absent
     * n'expose que là où de vraies adresses reçoivent de vrais liens. Exiger un
     * domaine vérifié chez Resend sous staging interdirait de monter un
     * environnement d'essai, pour fermer un risque qui n'y existe pas. Ce test
     * est là pour que le choix soit relu plutôt que corrigé par réflexe.
     */
    @Test
    void leDemarrage_doitReussir_sousStagingSansFournisseur() {
        assertThatCode(() -> demarrer("staging", false, "")).doesNotThrowAnyException();
    }

    // --------------------------------------------- ce que le repli journalise

    /**
     * Le test de la fiche : hors développement, le lien de réinitialisation
     * n'apparaît jamais dans les journaux — ni le jeton, ni l'adresse.
     */
    @Test
    void leLienDeReinitialisation_neDoitPasApparaitreDansLesJournaux_quandLeDrapeauEstEteint(
            CapturedOutput sortie) {
        service(false).sendPasswordResetEmail(ADRESSE, JETON);

        assertThat(journal(sortie))
            .doesNotContain(JETON)
            .doesNotContain(ADRESSE)
            .doesNotContain("reset-password?token=");
        // Mais la panne de configuration, elle, reste dite : un repli muet
        // ferait chercher le défaut du côté du fournisseur.
        assertThat(journal(sortie))
            .contains("aucun fournisseur d'e-mail configuré")
            .contains("réinitialisation de mot de passe");
    }

    /**
     * Aucun des cinq replis n'écrit d'adresse ni de lien. Ils écrivaient chacun
     * sa ligne, et il a suffi que la fiche d'audit en relise une pour découvrir
     * les quatre autres : ce test les prend tous ensemble, pour qu'un sixième
     * écrit demain ne passe pas seul.
     */
    @Test
    void aucunRepliSansFournisseur_neDoitEcrireDAdresseNiDeLien_quandLeDrapeauEstEteint(
            CapturedOutput sortie) {
        cinqReplis(service(false));

        assertThat(journal(sortie))
            .doesNotContain(JETON)
            .doesNotContain(ADRESSE)
            .doesNotContain("http://localhost:9999");
    }

    /**
     * Et en développement, le lien passe : le repli reste ce qu'il était là où
     * il ne protège rien. Sans cette moitié, on aurait fermé la fuite en cassant
     * le seul moyen d'ouvrir un e-mail de vérification en local.
     */
    @Test
    void lesCinqReplis_doiventEcrireLeLien_quandLeDrapeauEstAllume(CapturedOutput sortie) {
        cinqReplis(service(true));

        assertThat(journal(sortie))
            .contains(JETON)
            .contains(ADRESSE)
            .contains("reset-password?token=" + JETON)
            .contains("/v/" + JETON);
    }

    // ------------------------------------------------------------------ outils

    private static String journal(CapturedOutput sortie) {
        return sortie.getOut() + sortie.getErr();
    }

    /** Les cinq chemins de repli de la classe, dans l'ordre du fichier. */
    private static void cinqReplis(EmailService service) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail(ADRESSE);

        service.sendVerificationEmail(user, JETON);
        service.sendEmailChangeEmail(user, ADRESSE, JETON);
        service.sendPasswordResetEmail(ADRESSE, JETON);
        service.sendNotificationEmail(user.getId(), NotificationType.SLOT_CANCELLED,
            Map.of("programTitle", "Yoga du soir"));
        service.sendGuardianConsentEmail(ADRESSE, "Lena",
            "http://localhost:9999/g/" + JETON);
    }

    /**
     * Monte le service comme Spring le ferait, puis joue la garde de démarrage.
     * {@code environment.getProperty("resend.api-key")} est ce que la garde lit :
     * le {@code MockEnvironment} la porte, exactement comme
     * {@code application-railway.properties} la porterait.
     */
    private static EmailService demarrer(String profil, boolean resendAllume, String cle) {
        MockEnvironment environnement = new MockEnvironment();
        if (profil != null) {
            environnement.setActiveProfiles(profil);
        }
        environnement.setProperty("resend.api-key", cle);

        ResendEmailService resend = mock(ResendEmailService.class);
        given(resend.isEnabled()).willReturn(resendAllume);

        EmailService service = construire(resend, environnement);
        service.exigerUnFournisseurEnProduction();
        return service;
    }

    /** Un service dont le fournisseur est éteint, drapeau de journalisation au choix. */
    private static EmailService service(boolean journaliserLiens) {
        EmailService service = construire(mock(ResendEmailService.class), new MockEnvironment());
        ReflectionTestUtils.setField(service, "journaliserLiens", journaliserLiens);
        return service;
    }

    private static EmailService construire(ResendEmailService resend, MockEnvironment environnement) {
        GabaritEmail gabarit = new GabaritEmail();
        ReflectionTestUtils.setField(gabarit, "publicBaseUrl", "http://localhost:9999");

        UserRepository users = mock(UserRepository.class);
        User porteur = new User();
        porteur.setEmail(ADRESSE);
        given(users.findById(any())).willReturn(Optional.of(porteur));

        EmailService service = new EmailService(
            resend, users, mock(OutboxService.class), messages(), gabarit, environnement,
            mock(org.program.pair.repository.DeviceTokenRepository.class));
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:9999");
        return service;
    }

    /** Le vrai bundle, comme {@code EmailServiceTest} et pour la même raison. */
    private static Messages messages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(LocaleConfig.FRENCH);
        return new Messages(source);
    }
}
