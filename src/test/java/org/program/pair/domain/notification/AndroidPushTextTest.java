package org.program.pair.domain.notification;

import org.junit.jupiter.api.Test;
import org.program.pair.config.LocaleConfig;
import org.program.pair.shared.i18n.Messages;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La formule du template client, côté Android (T5).
 *
 * <p>Android n'a aucune extension qui réécrive la bannière sur l'appareil : ce
 * qui est composé ici est <b>tout</b> ce que l'utilisateur verra. D'où des
 * assertions sur le texte exact, et non sur la présence de fragments.
 *
 * <p>Horloge et fuseau imposés : sans eux, la date, l'heure et le rebours
 * changeraient à chaque exécution.
 */
class AndroidPushTextTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    /** 17/08/2026 à 15:00 UTC — 17:00 à Paris, un lundi. */
    private static final Instant NOW = Instant.parse("2026-08-17T15:00:00Z");

    private final AndroidPushText text = new AndroidPushText(messages(), ZONE, Clock.fixed(NOW, ZONE));

    private static Messages messages() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename("classpath:messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(LocaleConfig.FRENCH);
        return new Messages(source);
    }

    // ─── Titre ────────────────────────────────────────────────────────────────

    @Test
    void titre_doitJoindreLActiviteEtLeProgramme() {
        assertThat(text.title(LocaleConfig.FRENCH, slotPayload()))
            .isEqualTo("Natation · Longueurs du soir");
    }

    /**
     * Un message direct n'a ni programme ni activité : rendre {@code null} laisse
     * le titre traduit d'origine (« Nouveau message de Sophie ») faire son
     * travail, au lieu d'un titre vide.
     */
    @Test
    void titre_sansProgrammeNiActivite_doitRendreNull_pourLaisserLeRepli() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageAuthorName", "Sophie Martin");
        payload.put("messageBody", "On se retrouve devant le court 3 ?");

        assertThat(text.title(LocaleConfig.FRENCH, payload)).isNull();
    }

    @Test
    void titre_avecUnSeulDesDeux_neDoitPasLaisserDeSeparateurOrphelin() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("programTitle", "Longueurs du soir");

        assertThat(text.title(LocaleConfig.FRENCH, payload)).isEqualTo("Longueurs du soir");
    }

    // ─── Corps : le rappel ────────────────────────────────────────────────────

    /**
     * La formule du rappel ouvre sur le rebours — c'est l'information urgente —
     * puis pose l'heure absolue, qui corrige le rebours quand il a vieilli dans
     * le centre de notifications. Le lieu passe en seconde ligne : c'est elle qui
     * doit sauter quand la bannière se replie.
     */
    @Test
    void corpsDuRappel_rebourdPuisHeureAbsolue_lieuEnSecondeLigne() {
        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, slotPayload()))
            .isEqualTo("dans 2 h · Aujourd'hui 19:00 – 20:00 · par Lena Müller\nPiscine du Rhône");
    }

    @Test
    void corpsDuProgramme_doitOuvrirSurLaDate_pasSurLeRebours() {
        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.AUTHOR_NEW_PROGRAM, slotPayload()))
            .isEqualTo("Aujourd'hui 19:00 – 20:00 · dans 2 h · par Lena Müller\nPiscine du Rhône");
    }

    @Test
    void corpsDuMessage_doitOuvrirSurLaBulle_puisLeContexteDeSeance() {
        Map<String, Object> payload = slotPayload();
        payload.put("messageAuthorName", "Sophie Martin");
        payload.put("messageBody", "On se retrouve devant le court 3 ?");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.NEW_MESSAGE, payload))
            .isEqualTo("Sophie Martin : On se retrouve devant le court 3 ?"
                + "\nAujourd'hui 19:00 – 20:00 · Piscine du Rhône");
    }

    // ─── Le rebours ───────────────────────────────────────────────────────────

    /**
     * Règle du client : ces charges portent {@code sessionAt} comme les autres,
     * mais décompter vers une séance annulée ou vers une séance passée dont on
     * demande confirmation de présence n'a aucun sens.
     */
    @Test
    void aucunRebours_versUneSeanceAnnuleeOuPassee() {
        // Le type n'a pas de forme de corps aujourd'hui : on éprouve la règle là
        // où elle est écrite, sur la forme « programme ».
        Map<String, Object> payload = slotPayload();
        payload.put("sessionAt", "2026-08-17T14:00:00Z"); // une heure avant NOW

        String body = text.body(LocaleConfig.FRENCH, ZONE, NotificationType.AUTHOR_NEW_PROGRAM, payload);

        assertThat(body).doesNotContain("dans");
        assertThat(body).startsWith("Aujourd'hui 16:00");
    }

    @Test
    void rebours_sousUneHeure_doitSExprimerEnMinutes() {
        Map<String, Object> payload = slotPayload();
        payload.put("sessionAt", "2026-08-17T15:45:00Z"); // 45 min après NOW
        payload.remove("endsAt");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .startsWith("dans 45 min · Aujourd'hui 17:45");
    }

    // ─── Dates et langues ─────────────────────────────────────────────────────

    @Test
    void demain_doitSEcrireDemain_etNonUneDate() {
        Map<String, Object> payload = slotPayload();
        payload.put("sessionAt", "2026-08-18T17:00:00Z");
        payload.remove("endsAt");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .contains("Demain 19:00");
    }

    @Test
    void auDelaDeDemain_doitEcrireLaDateDansLaLangueDeLAppareil() {
        Map<String, Object> payload = slotPayload();
        payload.put("sessionAt", "2026-08-22T17:00:00Z"); // samedi
        payload.remove("endsAt");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .contains("sam. 22 août");
        assertThat(text.body(LocaleConfig.ENGLISH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .contains("Sat 22 Aug");
        assertThat(text.body(LocaleConfig.GERMAN, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .contains("Sa. 22. Aug");
    }

    @Test
    void lEnveloppeDuRebours_etLAuteur_doiventSuivreLaLangue() {
        assertThat(text.body(LocaleConfig.ENGLISH, ZONE, NotificationType.PROGRAM_REMINDER, slotPayload()))
            .isEqualTo("in 2 h · Today 19:00 – 20:00 · by Lena Müller\nPiscine du Rhône");
        assertThat(text.body(LocaleConfig.GERMAN, ZONE, NotificationType.PROGRAM_REMINDER, slotPayload()))
            .isEqualTo("in 2 h · Heute 19:00 – 20:00 · von Lena Müller\nPiscine du Rhône");
    }

    // ─── Le fuseau de l'appareil ──────────────────────────────────────────────

    /**
     * Le point de tout le champ {@code timezone} : la même séance, à la même
     * seconde, s'écrit à une heure différente selon où se trouve l'appareil.
     * Londres est à une heure de Paris — c'était exactement l'écart que nous
     * assumions avant que le client n'envoie son fuseau.
     */
    @Test
    void memeSeance_doitSEcrireALHeureLocaleDeLAppareil() {
        Map<String, Object> payload = slotPayload();
        payload.remove("endsAt");

        assertThat(text.body(LocaleConfig.FRENCH, ZoneId.of("Europe/Paris"),
            NotificationType.PROGRAM_REMINDER, payload))
            .contains("Aujourd'hui 19:00");
        assertThat(text.body(LocaleConfig.ENGLISH, ZoneId.of("Europe/London"),
            NotificationType.PROGRAM_REMINDER, payload))
            .contains("Today 18:00");
        assertThat(text.body(LocaleConfig.ENGLISH, ZoneId.of("America/New_York"),
            NotificationType.PROGRAM_REMINDER, payload))
            .contains("Today 13:00");
    }

    /**
     * Le rebours, lui, ne dépend pas du fuseau : c'est une durée. Seule l'heure
     * absolue se déplace — et c'est bien ce qui rend les deux segments
     * complémentaires.
     */
    @Test
    void leRebours_neDoitPasDependreDuFuseau() {
        Map<String, Object> payload = slotPayload();

        assertThat(text.body(LocaleConfig.ENGLISH, ZoneId.of("Asia/Tokyo"),
            NotificationType.PROGRAM_REMINDER, payload))
            .startsWith("in 2 h · ");
    }

    /**
     * « Aujourd'hui » se juge dans le fuseau de l'appareil, pas dans celui du
     * serveur — et les deux ne désignent pas toujours le même jour.
     *
     * <p>À l'instant du test, on est le 17 à Paris (17:00) mais déjà le 18 à
     * Tokyo (00:00). La même séance est donc <b>demain</b> pour l'un et
     * <b>aujourd'hui</b> pour l'autre. Juger le jour dans le fuseau du serveur
     * aurait écrit « demain » sur un téléphone japonais pour une séance qui, chez
     * lui, a lieu le jour même.
     */
    @Test
    void leJour_doitSeJugerDansLeFuseauDeLAppareil() {
        Map<String, Object> payload = slotPayload();
        payload.remove("endsAt");
        payload.put("sessionAt", "2026-08-18T10:00:00Z");

        assertThat(text.body(LocaleConfig.FRENCH, ZoneId.of("Europe/Paris"),
            NotificationType.PROGRAM_REMINDER, payload))
            .contains("Demain 12:00");
        assertThat(text.body(LocaleConfig.ENGLISH, ZoneId.of("Asia/Tokyo"),
            NotificationType.PROGRAM_REMINDER, payload))
            .contains("Today 19:00");
    }

    // ─── Résolution du fuseau déclaré ─────────────────────────────────────────

    @Test
    void fuseauDeclare_doitEtreRetenu() {
        assertThat(text.zoneOf("Europe/London")).isEqualTo(ZoneId.of("Europe/London"));
        assertThat(text.zoneOf("  Europe/Berlin  ")).isEqualTo(ZoneId.of("Europe/Berlin"));
    }

    /**
     * Absent, vide ou illisible : le fuseau de référence, qui était le
     * comportement de tout le monde avant ce champ. Une push qui échoue à se
     * composer est une push qui n'arrive pas — l'étiquette douteuse ne doit rien
     * emporter avec elle.
     */
    @Test
    void fuseauAbsentOuIllisible_doitRetomberSurLaReference() {
        assertThat(text.zoneOf(null)).isEqualTo(ZONE);
        assertThat(text.zoneOf("")).isEqualTo(ZONE);
        assertThat(text.zoneOf("   ")).isEqualTo(ZONE);
        assertThat(text.zoneOf("Mars/Olympus_Mons")).isEqualTo(ZONE);
        assertThat(text.zoneOf("+02:00 ou pas")).isEqualTo(ZONE);
    }

    // ─── Champs absents ───────────────────────────────────────────────────────

    /**
     * {@code endsAt} est facultative en base : un créneau sans fin déclarée
     * affiche l'heure de début seule, et surtout pas un tiret orphelin.
     */
    @Test
    void sansEndsAt_doitAfficherLHeureDeDebutSeule() {
        Map<String, Object> payload = slotPayload();
        payload.remove("endsAt");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .isEqualTo("dans 2 h · Aujourd'hui 19:00 · par Lena Müller\nPiscine du Rhône");
    }

    @Test
    void sansLieu_neDoitPasLaisserDeSecondeLigneVide() {
        Map<String, Object> payload = slotPayload();
        payload.remove("placeName");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .isEqualTo("dans 2 h · Aujourd'hui 19:00 – 20:00 · par Lena Müller");
    }

    @Test
    void sansAuteur_neDoitPasLaisserDeSeparateurOrphelin() {
        Map<String, Object> payload = slotPayload();
        payload.remove("authorName");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .isEqualTo("dans 2 h · Aujourd'hui 19:00 – 20:00\nPiscine du Rhône");
    }

    /**
     * Une date illisible ne doit pas faire échouer la composition : la
     * notification part sans son segment de date, pas du tout.
     */
    @Test
    void sessionAtIllisible_doitFaireSauterLeSegment_pasLaNotification() {
        Map<String, Object> payload = slotPayload();
        payload.put("sessionAt", "la semaine prochaine");

        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.PROGRAM_REMINDER, payload))
            .isEqualTo("par Lena Müller\nPiscine du Rhône");
    }

    // ─── Hors du template ─────────────────────────────────────────────────────

    /**
     * Un type absent de la maquette rend {@code null} des deux côtés, et le texte
     * traduit d'origine reprend la main : le changement est strictement additif.
     */
    @Test
    void typeHorsDuTemplate_doitRendreNull_pourLaisserLeTexteTraduit() {
        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.BADGE_EARNED, slotPayload()))
            .isNull();
        assertThat(text.body(LocaleConfig.FRENCH, ZONE, NotificationType.NEW_FOLLOWER, slotPayload()))
            .isNull();
    }

    /** Séance à 19:00 – 20:00 heure de Paris, soit deux heures après {@code NOW}. */
    private static Map<String, Object> slotPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("activityName", "Natation");
        payload.put("programTitle", "Longueurs du soir");
        payload.put("authorName", "Lena Müller");
        payload.put("placeName", "Piscine du Rhône");
        payload.put("sessionAt", "2026-08-17T17:00:00Z");
        payload.put("endsAt", "2026-08-17T18:00:00Z");
        return payload;
    }
}
