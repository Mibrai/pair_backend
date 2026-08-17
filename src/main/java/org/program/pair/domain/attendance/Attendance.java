package org.program.pair.domain.attendance;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "attendances",
    // L'unicité porte sur l'OCCURRENCE, pas sur la ligne de créneau : un
    // créneau récurrent n'a qu'une ligne en base, et la contrainte d'origine
    // (schedule_id, user_id) interdisait donc de confirmer sa présence à deux
    // séances de la même série. attended_at nomme la séance — voir
    // SlotOccurrence.
    uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "user_id", "attended_at"}),
    indexes = {
        @Index(name = "idx_attendance_user_date", columnList = "user_id, attended_at"),
        @Index(name = "idx_attendance_schedule", columnList = "schedule_id"),
        @Index(name = "idx_attendance_schedule_occurrence", columnList = "schedule_id, attended_at")
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

    /**
     * Instant réel de la séance — dénormalisé pour le calcul de série, et
     * <b>identifiant de l'occurrence</b> depuis que celle-ci est distinguée de
     * la ligne de créneau.
     *
     * <p>Ce double rôle n'est pas un détournement : c'est la même information.
     * Ajouter une colonne {@code occurrence_start} à côté aurait créé deux
     * copies d'un même fait, donc une occasion de les voir diverger.
     */
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
