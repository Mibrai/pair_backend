package org.program.pair.domain.report;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "ReportPhase3")
@Table(
    name = "reports",
    uniqueConstraints = @UniqueConstraint(
        name = "unique_report",
        columnNames = {"reporter_id", "reported_entity_type", "reported_entity_id"}
    )
)
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {

    @Id
    @ToString.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "reporter_id", nullable = false)
    private UUID reporterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reported_entity_type", nullable = false)
    private ReportEntityType reportedEntityType;

    @Column(name = "reported_entity_id", nullable = false)
    private UUID reportedEntityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportReason reason;

    @Column(length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReportStatus status = ReportStatus.PENDING;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Identité seule, et stable avant comme après {@code persist} (P-BA-12).
     * L'{@code equals} de {@code @Data} comparait tous les champs, associations
     * paresseuses comprises : une comparaison pouvait charger la base, ou boucler.
     */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof Report autre && id != null && id.equals(autre.id));
    }

    @Override
    public int hashCode() {
        return Report.class.hashCode();
    }
}
