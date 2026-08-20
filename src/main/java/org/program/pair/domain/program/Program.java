package org.program.pair.domain.program;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.indexation.ProgramIndexationListener;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "programs", indexes = {
    @Index(name = "idx_programs_user_activity", columnList = "user_activity_id"),
    @Index(name = "idx_programs_status", columnList = "status"),
    @Index(name = "idx_programs_archived", columnList = "archived_at")
})
@EntityListeners({AuditingEntityListener.class, ProgramIndexationListener.class})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_activity_id", nullable = false)
    private UserActivity userActivity;

    @Column(nullable = false, length = 150)
    @NotBlank
    @Size(max = 150)
    private String title;

    @Column(columnDefinition = "TEXT")
    @Size(max = 3000)
    private String description;

    // La colonne embedding (vector 1536) existe en DB mais est gérée via JDBC dans IndexationService.
    // Pas de mapping JPA pour éviter la complexité de l'enregistrement du type pgvector.

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ProgramStatus status = ProgramStatus.DRAFT;

    @Column(name = "is_public", nullable = false)
    @Builder.Default
    private Boolean isPublic = true;

    /**
     * L'auteur accepte-t-il de recevoir des messages de ses participants ?
     *
     * <p>Vrai par défaut : le produit met des gens en relation, un programme
     * muet par défaut prendrait tout le monde à contre-pied. L'auteur restreint,
     * il n'ouvre pas.
     *
     * <p>Le refus s'applique côté serveur — voir {@code ChatService} — et pas
     * seulement à l'affichage : un drapeau que seul le client respecte ne
     * protège personne.
     */
    @Column(name = "allow_participant_messages", nullable = false)
    @Builder.Default
    private Boolean allowParticipantMessages = true;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "organizer_name", length = 80)
    private String organizerName;

    @Column(name = "organizer_avatar_url", length = 500)
    private String organizerAvatarUrl;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    @Column(name = "next_session_at")
    private Instant nextSessionAt;

    /**
     * Instant où les abonnés ont été notifiés de ce programme, {@code null} tant
     * qu'ils ne l'ont pas été.
     *
     * <p>L'annonce part du <b>premier créneau posé</b> et non de la création :
     * un programme sans créneau n'a ni date, ni lieu, ni compte à rebours à
     * annoncer. Cette colonne est ce qui la rend unique — un créneau supprimé
     * puis reposé ferait sinon du suivant « le premier » une seconde fois.
     */
    @Column(name = "subscribers_notified_at")
    private Instant subscribersNotifiedAt;

    // Champs ajoutés par V26
    @Column(name = "duration_weeks")
    private Integer durationWeeks;

    @Column(name = "sessions_per_week")
    private Integer sessionsPerWeek;

    @Column(name = "session_duration_minutes")
    private Integer sessionDurationMinutes;

    @Column(name = "preferred_days", columnDefinition = "integer[]")
    private int[] preferredDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "preferred_time", length = 20)
    private PreferredTime preferredTime;

    @Column(name = "max_participants")
    private Integer maxParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "privacy", length = 20)
    @Builder.Default
    private ProgramPrivacy privacy = ProgramPrivacy.PUBLIC;

    @Column(name = "goals", columnDefinition = "TEXT")
    private String goals;

    @Column(name = "prerequisites", columnDefinition = "TEXT")
    private String prerequisites;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 20)
    private LocationType locationType;

    /**
     * Partage public du programme, calqué sur celui des créneaux (V65 → V78).
     *
     * <p>Le jeton est <b>opaque</b> et n'est jamais l'identifiant interne : une
     * adresse bâtie sur la clé primaire se laisse énumérer, et lierait l'URL
     * publique à une structure interne qui a le droit de changer.
     *
     * <p>Il est créé à la première demande de lien et <b>jamais régénéré</b> :
     * refermer le partage suffit à ce que le lien ne mène plus nulle part, et le
     * rouvrir doit rendre valides les liens déjà collés ailleurs.
     */
    @Column(name = "public_share_token", length = 22, unique = true)
    private String publicShareToken;

    @Column(name = "is_publicly_shareable", nullable = false)
    @Builder.Default
    private Boolean isPubliclyShareable = true;

    @Column(name = "public_view_count", nullable = false)
    @Builder.Default
    private Integer publicViewCount = 0;

    /**
     * Par quel chemin ce programme est né (V61). Le {@code @Builder.Default} n'est
     * pas décoratif : plusieurs tests construisent un {@code Program.builder()}
     * sans ce champ, et il arriverait nul là où la colonne est {@code NOT NULL}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "created_via", nullable = false, length = 20)
    @Builder.Default
    private ProgramCreatedVia createdVia = ProgramCreatedVia.FULL;

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startsAt ASC")
    @Builder.Default
    private List<Schedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<ProgramMedia> media = new ArrayList<>();
}
