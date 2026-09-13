package org.program.pair.shared.email;

import org.junit.jupiter.api.Test;
import org.program.pair.config.LocaleConfig;
import org.program.pair.domain.email.GabaritEmail;
import org.program.pair.domain.email.ResendEmailService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.outbox.OutboxService;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.i18n.Messages;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Le texte d'un e-mail de notification, type par type.
 *
 * <p><b>Le défaut que cette classe existe pour empêcher de revenir.</b>
 * {@code notificationText} écrivait « La séance « X » est annulée. » <b>quel que
 * soit le type</b>, sans un {@code if}. Tant que {@code SCHEDULE_CHANGED} n'avait
 * aucun producteur, personne ne pouvait s'en apercevoir ; le jour où il en a eu
 * un (P-BL-06), avancer une séance d'une heure aurait envoyé à chaque inscrit un
 * courriel annonçant son annulation. C'est le pire message que ce système puisse
 * produire : celui qui fait rester chez soi quelqu'un dont la séance a bien lieu.
 *
 * <p>Un test unitaire et non d'intégration : rien ici ne touche la base ni le
 * réseau, et le contexte Spring partagé est déjà à son plafond de configurations.
 * Le {@code MessageSource} est le vrai — {@code messages*.properties} du
 * classpath — pour que ces tests disent aussi ce que les clés existantes rendent.
 */
class EmailServiceTest {

    /** Le vrai bundle, comme dans {@code PushNotificationServiceTest} et pour la même raison. */
    private static Messages messages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(LocaleConfig.FRENCH);
        return new Messages(source);
    }

    private EmailService service() {
        GabaritEmail gabarit = new GabaritEmail();
        ReflectionTestUtils.setField(gabarit, "publicBaseUrl", "https://lien.meetdo.fun");
        // Un environnement sans profil actif : la garde de démarrage
        // (exigerUnFournisseurEnProduction) ne s'applique qu'aux profils de
        // production, et ces tests ne portent que sur la composition des textes.
        return new EmailService(
            mock(ResendEmailService.class),
            mock(UserRepository.class),
            mock(OutboxService.class),
            messages(),
            gabarit,
            new MockEnvironment(),
            mock(org.program.pair.repository.DeviceTokenRepository.class));
    }

    private static Map<String, Object> charge(String... changedFields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "Yoga du soir");
        if (changedFields.length > 0) {
            payload.put("changedFields", List.of(changedFields));
        }
        return payload;
    }

    @Test
    void lEmailDeModification_neDoitJamaisDireAnnulee() {
        EmailService service = service();

        for (String[] formes : new String[][] {{"TIME"}, {"PLACE"}, {"TIME", "PLACE"}}) {
            String texte = service.notificationText(NotificationType.SCHEDULE_CHANGED, charge(formes));
            String objet = service.subjectFor(NotificationType.SCHEDULE_CHANGED, "Yoga du soir");

            assertThat(texte.toLowerCase()).doesNotContain("annul");
            assertThat(objet.toLowerCase()).doesNotContain("annul");
            // Et il dit de quoi il parle : le titre du programme, faute de quoi
            // l'e-mail ne se rattache à aucune séance dans une boîte.
            assertThat(texte).contains("Yoga du soir");
            assertThat(objet).contains("Yoga du soir");
        }
    }

    /**
     * Les trois formes de modification disent trois choses différentes.
     *
     * <p>Un texte unique « la séance a été modifiée » obligerait à ouvrir
     * l'application pour savoir s'il faut se réorganiser, ce que la moitié des
     * gens ne fera pas.
     */
    @Test
    void lesTroisFormesDeModification_doiventDonnerTroisTextes() {
        EmailService service = service();

        String heure = service.notificationText(NotificationType.SCHEDULE_CHANGED, charge("TIME"));
        String lieu = service.notificationText(NotificationType.SCHEDULE_CHANGED, charge("PLACE"));
        String deux = service.notificationText(
            NotificationType.SCHEDULE_CHANGED, charge("TIME", "PLACE"));

        assertThat(List.of(heure, lieu, deux)).doesNotHaveDuplicates();
        assertThat(heure.toLowerCase()).contains("horaire");
        assertThat(lieu.toLowerCase()).contains("lieu");
        assertThat(deux.toLowerCase()).contains("horaire").contains("lieu");
    }

    /**
     * Tout type qui part par e-mail a son texte propre et son objet propre.
     *
     * <p><b>Le test déclaratif du lot.</b> Il échoue le jour où un quatrième type
     * entre dans {@code warrantsEmail} sans qu'on lui écrive de texte : il
     * hériterait alors de celui d'un autre, ce qui est exactement l'histoire de
     * {@code SCHEDULE_CHANGED}. Aucun test fonctionnel ne l'aurait vu — le type
     * n'avait pas de producteur.
     */
    @Test
    void chaqueTypeQuiPartParEmail_doitAvoirSonTexteEtSonObjet() {
        EmailService service = service();

        List<NotificationType> aLEmail = java.util.Arrays.stream(NotificationType.values())
            .filter(NotificationType::warrantsEmail)
            .toList();

        // Le filtre lui-même est vérifié : s'il devenait vide, tout le reste
        // passerait sans rien prouver.
        assertThat(aLEmail).contains(NotificationType.SLOT_CANCELLED,
            NotificationType.SCHEDULE_CHANGED);

        List<String> textes = aLEmail.stream()
            .map(type -> service.notificationText(type, charge("TIME")))
            .toList();
        List<String> objets = aLEmail.stream()
            .map(type -> service.subjectFor(type, "Yoga du soir"))
            .toList();

        assertThat(textes).doesNotHaveDuplicates().allSatisfy(t -> assertThat(t).isNotBlank());
        assertThat(objets).doesNotHaveDuplicates().allSatisfy(o -> assertThat(o).isNotBlank());
    }

    /**
     * L'annulation garde son texte, son motif et son repli — ce lot ne les a pas
     * touchés.
     *
     * <p>Le rappel n'est pas décoratif : le {@code switch} qui sépare les types
     * aurait pu emporter le motif ou le décompte d'alternatives en passant, et
     * l'annulation est la raison d'être de ce canal.
     */
    @Test
    void lEmailDAnnulation_doitGarderSonMotifEtSesAlternatives() {
        EmailService service = service();
        Map<String, Object> payload = charge();
        payload.put("cancellationReason", "Le gymnase est fermé");
        payload.put("alternativesCount", 2);

        String texte = service.notificationText(NotificationType.SLOT_CANCELLED, payload);

        assertThat(texte).contains("Yoga du soir").contains("annulée");
        assertThat(texte).contains("Le gymnase est fermé");
        assertThat(texte).contains("2 autres créneaux");
        assertThat(service.subjectFor(NotificationType.SLOT_CANCELLED, "Yoga du soir"))
            .isEqualTo("Séance annulée : Yoga du soir");
    }

    /**
     * Un type qui ne part pas par e-mail garde l'objet générique.
     *
     * <p>{@code sendNotificationEmail} les écarte avant d'arriver ici ; ce que ce
     * test fixe, c'est qu'un {@code default} reste et qu'un type inattendu ne
     * fasse pas lever la composition dans un envoi asynchrone.
     */
    @Test
    void unTypeSansEmail_doitGarderLObjetGenerique() {
        assertThat(service().subjectFor(NotificationType.NEW_MESSAGE, "Yoga du soir"))
            .isEqualTo("meetDo — Yoga du soir");
    }

    /**
     * P-BL-20 — l'annulation part dans la langue de l'appareil le plus récent de
     * la personne, et en français quand aucun appareil ne dit rien.
     */
    @Test
    void unUtilisateurAllemand_doitRecevoirLAnnulationEnAllemand() {
        java.util.UUID userId = java.util.UUID.randomUUID();
        ResendEmailService resend = mock(ResendEmailService.class);
        org.mockito.Mockito.when(resend.isEnabled()).thenReturn(true);
        org.mockito.Mockito.when(resend.sendEmail(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        UserRepository users = mock(UserRepository.class);
        org.program.pair.domain.user.User lena = new org.program.pair.domain.user.User();
        lena.setEmail("lena@example.test");
        org.mockito.Mockito.when(users.findById(userId)).thenReturn(java.util.Optional.of(lena));
        org.program.pair.repository.DeviceTokenRepository appareils =
            mock(org.program.pair.repository.DeviceTokenRepository.class);
        org.mockito.Mockito.when(appareils.findByUserId(userId)).thenReturn(List.of(
            org.program.pair.domain.notification.DeviceToken.builder()
                .token("ancien").locale("fr-FR").lastUsedAt(java.time.Instant.now().minusSeconds(86_400)).build(),
            org.program.pair.domain.notification.DeviceToken.builder()
                .token("recent").locale("de-DE").lastUsedAt(java.time.Instant.now()).build()));
        GabaritEmail gabarit = new GabaritEmail();
        ReflectionTestUtils.setField(gabarit, "publicBaseUrl", "https://lien.meetdo.fun");
        EmailService service = new EmailService(resend, users, mock(OutboxService.class), messages(),
            gabarit, new MockEnvironment(), appareils);

        Map<String, Object> payload = charge();
        payload.put("cancellationReason", "Halle geschlossen");
        payload.put("alternativesCount", 2);
        service.sendNotificationEmail(userId, NotificationType.SLOT_CANCELLED, payload);

        org.mockito.ArgumentCaptor<String> objet = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> texte = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(resend).sendEmail(org.mockito.ArgumentMatchers.eq("lena@example.test"),
            objet.capture(), texte.capture(), org.mockito.ArgumentMatchers.anyString());
        assertThat(objet.getValue()).isEqualTo("Termin abgesagt: Yoga du soir");
        assertThat(texte.getValue()).contains("ist abgesagt").contains("Halle geschlossen")
            .contains("2 weitere Termine").doesNotContain("annulée");
    }
}
