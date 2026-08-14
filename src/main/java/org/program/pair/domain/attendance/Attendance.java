package org.program.pair.domain.attendance;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendances",
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "user_id"}),
    indexes = {
        @Index(name = "idx_attendance_user_date", columnList = "user_id, attended_at"),
        @Index(name = "idx_attendance_schedule", columnList = "schedule_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attendance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // TRUE = "j'y étais", FALSE = "finalement je n'y suis pas allé"
    @Column(name = "was_present", nullable = false)
    private Boolean wasPresent;

    // Instant réel du créneau (dénormalisé pour le calcul de série)
    @Column(name = "attended_at", nullable = false)
    private Instant attendedAt;

    @Column(name = "confirmed_at", nullable = false)
    @Builder.Default
    private Instant confirmedAt = Instant.now();

    /**
     * Souvenir photo de cette personne pour ce créneau, servi par le chemin
     * média existant ({@code /api/media/files/**}). Nul tant qu'elle n'en a
     * pas partagé.
     */
    @Column(name = "memory_photo_url", length = 500)
    private String memoryPhotoUrl;

    /**
     * La photo peut-elle figurer sur la carte-souvenir du créneau ?
     *
     * <p>Faux par défaut, et séparé du consentement à être nommé : partager
     * une image du moment et accepter de figurer sur la carte sont deux
     * décisions distinctes.
     */
    @Column(name = "memory_is_public", nullable = false)
    @Builder.Default
    private Boolean memoryIsPublic = false;
}
