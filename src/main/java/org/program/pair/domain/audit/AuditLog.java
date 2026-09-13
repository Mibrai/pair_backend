package org.program.pair.domain.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Entity representing an audit log entry for GDPR compliance
 * (Articles 30, 32, 33)
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @ToString.Include
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 50)
    private AuditActionType actionType;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Identité seule, et stable avant comme après {@code persist} (P-BA-12).
     * L'{@code equals} de {@code @Data} comparait tous les champs, associations
     * paresseuses comprises : une comparaison pouvait charger la base, ou boucler.
     */
    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof AuditLog autre && id != null && id.equals(autre.id));
    }

    @Override
    public int hashCode() {
        return AuditLog.class.hashCode();
    }
}
