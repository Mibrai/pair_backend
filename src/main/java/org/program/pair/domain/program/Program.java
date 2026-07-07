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

    @Column(name = "next_session_at")
    private Instant nextSessionAt;

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

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("startsAt ASC")
    @Builder.Default
    private List<Schedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "program", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<ProgramMedia> media = new ArrayList<>();
}
