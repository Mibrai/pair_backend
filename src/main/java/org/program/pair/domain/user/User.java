package org.program.pair.domain.user;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.locationtech.jts.geom.Point;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
    @Index(name = "idx_users_email", columnList = "email"),
    @Index(name = "idx_users_last_active", columnList = "last_active_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 255)
    @Email
    @NotBlank
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    @NotBlank
    private String passwordHash;

    @Column(length = 20)
    private String phone;

    @Column(name = "display_name", nullable = false, length = 80)
    @NotBlank
    @Size(max = 80)
    private String displayName;

    @Column(length = 1000)
    @Size(max = 1000)
    private String bio;

    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "blur_radius_m", nullable = false)
    @Builder.Default
    private Integer blurRadiusM = 500;

    @Column(name = "location_public", nullable = false)
    @Builder.Default
    private Boolean locationPublic = false;

    @Column(name = "online_status_visible", nullable = false)
    @Builder.Default
    private Boolean onlineStatusVisible = false;

    @Column(name = "receive_messages", nullable = false)
    @Builder.Default
    private Boolean receiveMessages = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 30)
    @Builder.Default
    private VerificationStatus verificationStatus = VerificationStatus.UNVERIFIED;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_active_at")
    private Instant lastActiveAt;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Embedded
    @Builder.Default
    private PrivacySettings privacySettings = new PrivacySettings();

    // Métriques de valeur meetDo : régularité et diversité des partenaires,
    // jamais un score comparatif entre personnes (voir PracticeStatsService).
    @Column(name = "distinct_partners_count", nullable = false)
    @Builder.Default
    private Integer distinctPartnersCount = 0;

    @Column(name = "attendance_count", nullable = false)
    @Builder.Default
    private Integer attendanceCount = 0;

    @Column(name = "current_streak_weeks", nullable = false)
    @Builder.Default
    private Integer currentStreakWeeks = 0;

    @Column(name = "last_attendance_at")
    private Instant lastAttendanceAt;

    // Parcours d'accueil (V60). Placés en fin de classe à dessein : plusieurs
    // champs de cette entité portent leur annotation d'audit sur la ligne qui
    // les précède, et s'insérer entre les deux la déplacerait silencieusement.
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "onboarding_step", length = 30)
    private OnboardingStep onboardingStep;

    /**
     * Heures de silence, en heures pleines locales (0–23), ou nulles.
     *
     * <p>Les deux vont ensemble, la base le contraint. La fenêtre <b>traverse
     * minuit</b> dans le cas normal — « 22 h – 7 h » —, ce que
     * {@link org.program.pair.domain.notification.QuietHours} sait lire et qu'une
     * comparaison écrite à la main ici casserait.
     *
     * <p>Sans fuseau : c'est celui de l'appareil qui décide, appareil par
     * appareil, au moment de l'envoi.
     *
     * <p>{@code Short} et non {@code Integer}, pour coller au {@code SMALLINT} de
     * la colonne — même choix qu'au lot D3 pour {@code day_of_week}. Hibernate
     * valide le schéma au démarrage et refuse l'écart, ce qui fait échouer
     * l'application entière et non la seule fonctionnalité concernée.
     */
    @Column(name = "quiet_hours_start")
    private Short quietHoursStart;

    @Column(name = "quiet_hours_end")
    private Short quietHoursEnd;

    // Règles de communauté (V64). Nulles tant que la personne n'a jamais
    // accepté — aucun rétro-remplissage, à l'inverse de l'onboarding : c'est
    // l'acceptation explicite qui est demandée.
    @Column(name = "guidelines_accepted_at")
    private Instant guidelinesAcceptedAt;

    @Column(name = "guidelines_version", length = 10)
    private String guidelinesVersion;

    /**
     * Créneaux passés auxquels cette personne s'était inscrite (V69).
     *
     * <p>Le dénominateur du signal de fiabilité. Le numérateur est
     * {@code attendanceCount}, qui existe depuis V41 — un second compteur de
     * présences aurait fait doublon et fini par diverger.
     *
     * <p>Ni trié, ni filtré, ni exposé tel quel : seul le libellé sort de
     * l'API, voir {@code ReliabilitySignal}.
     */
    @Column(name = "joined_slots_count", nullable = false)
    @Builder.Default
    private Integer joinedSlotsCount = 0;

    /**
     * Ce qu'est devenu le dernier e-mail de vérification envoyé à ce compte (V105).
     *
     * <p>Porté ici et non calculé depuis l'outbox, contrairement à
     * {@code alertDelivery} : la purge de l'outbox efface les messages partis
     * depuis sept jours, et l'état retomberait à {@code NONE} sur un compte dont
     * l'adresse avait rebondi — c'est-à-dire qu'il mentirait précisément dans le
     * cas pour lequel il existe.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "verification_email_delivery", nullable = false, length = 12)
    @Builder.Default
    private VerificationEmailDelivery verificationEmailDelivery = VerificationEmailDelivery.NONE;

    /**
     * L'identifiant Resend du dernier e-mail de vérification remis au
     * fournisseur, ou nul tant qu'aucun ne l'a été.
     *
     * <p>Il n'est pas là pour être lu — il n'est exposé nulle part — mais pour
     * décider si un accusé de remise concerne encore ce compte : après un renvoi,
     * un rebond portant sur l'envoi précédent ne doit pas écraser le sort du
     * nouveau.
     */
    @Column(name = "verification_email_message_id", length = 128)
    private String verificationEmailMessageId;

    /**
     * L'adresse demandée par un changement, tant que son lien n'a pas été cliqué.
     *
     * <p><b>Ce n'est pas l'adresse du compte.</b> La bascule se fait au clic et
     * jamais avant : l'adresse est aussi l'identifiant de connexion, et basculer
     * à la demande enfermerait dehors quelqu'un qui se serait trompé deux fois —
     * or se tromper une première fois est exactement la raison d'être de cette
     * route.
     */
    @Column(name = "pending_email", length = 255)
    private String pendingEmail;

    /**
     * Quand la suppression de ce compte a été demandée, ou {@code null} (V111).
     *
     * <p><b>Ce n'est pas « depuis quand le compte est inactif ».</b> C'est la
     * date de la <i>demande</i>, et c'est la seule depuis laquelle le délai de
     * trente jours de la décision D2 peut courir. La purge lisait auparavant
     * {@code lastActiveAt}, qui n'est écrit qu'à la connexion et à la mise à jour
     * de position : un compte resté ouvert des mois par jetons de rafraîchissement
     * y porte une date périmée, et sa demande de suppression aurait été exécutée
     * la nuit suivante — sans le délai de réversibilité que l'écran de
     * l'application promet.
     *
     * <p><b>Nulle pour tout compte désactivé avant V111, et la purge les ignore.</b>
     * La migration ne remplit pas le passé : aucune donnée existante ne dit quand
     * la demande a eu lieu, et l'inventer aurait fait repartir le délai à
     * l'instant du déploiement. Ces comptes relèvent d'un runbook, pas du job
     * nocturne. Voir {@code UserRepository.findDeactivatedBefore}.
     *
     * <p>Posée par {@link UserService#deactivateAccount} au seul passage de
     * actif à inactif, jamais réécrite : le second appel de la route de
     * suppression est un no-op, et il ne doit pas remettre le compteur à zéro.
     *
     * <p><b>Ce que le code ne décide pas.</b> La longueur du délai, la
     * réversibilité pendant ce délai, et le choix de {@code CASCADE} sur les
     * présences restent suspendus à un avis juridique qui n'a pas été donné —
     * voir la javadoc de {@code GdprAccountEraser}. Cette colonne rend ces
     * questions décidables ; elle ne les décide pas.
     */
    @Column(name = "deactivated_at")
    private Instant deactivatedAt;
}
