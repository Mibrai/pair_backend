package org.program.pair.domain.program;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.*;
import org.locationtech.jts.geom.Point;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "schedules", indexes = {
    @Index(name = "idx_schedules_program", columnList = "program_id"),
    @Index(name = "idx_schedules_starts_at", columnList = "starts_at")
})
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    @Column(name = "place_name", nullable = false, length = 200)
    @NotBlank
    private String placeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "place_type", nullable = false, length = 10)
    private PlaceType placeType;

    @Column(columnDefinition = "geometry(Point,4326)", nullable = false)
    private Point location;

    @Column(name = "address_public", length = 300)
    private String addressPublic;

    @Column(name = "show_exact_address", nullable = false)
    @Builder.Default
    private Boolean showExactAddress = false;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "recurrence_rule", length = 200)
    private String recurrenceRule;

    @Column(name = "max_participants")
    @Min(1)
    private Integer maxParticipants;

    // Un créneau peut être ouvert à d'autres personnes (le coeur du produit meetDo)
    @Column(name = "is_open_to_partners", nullable = false)
    @Builder.Default
    private Boolean isOpenToPartners = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SlotStatus status = SlotStatus.OPEN;

    // Nombre de participants confirmés, tous mécanismes confondus (UserProgram + SlotParticipation)
    @Column(name = "participant_count", nullable = false)
    @Builder.Default
    private Integer participantCount = 0;

    @Column(name = "welcome_note", length = 300)
    private String welcomeNote;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SlotParticipation> participations = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
