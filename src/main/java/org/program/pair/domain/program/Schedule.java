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

    /**
     * Position du lieu. <b>Nulle si et seulement si le créneau est en ligne</b> —
     * la contrainte {@code chk_schedule_location_unless_online} (V61) le garantit
     * en base. Un lieu physique sans position ne serait sur aucune carte, dans
     * aucun rayon, et personne ne saurait où aller.
     */
    @Column(columnDefinition = "geometry(Point,4326)")
    private Point location;

    @Column(name = "address_public", length = 300)
    private String addressPublic;

    /**
     * Ville du créneau — le grain de lieu diffusable sans condition, là où
     * {@code addressPublic} reste soumis à {@link SlotAddressVisibility}.
     *
     * <p>Nullable et jamais devinée : aucun service de géocodage réel n'est
     * branché ({@code MapService.reverseGeocode} est un bouchon), et une ville
     * inventée vaut moins qu'une ville absente.
     */
    @Column(name = "city", length = 120)
    private String city;

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

    /**
     * {@code startsAt} pour lequel le rappel T-2h a été émis — pas un booléen.
     *
     * <p>C'est ce qui rend la replanification implicite : un créneau déplacé voit
     * son {@code startsAt} changer, la comparaison cesse de coïncider, et le
     * balayage le reprend sans que le chemin de déplacement ait eu à le savoir.
     * Voir {@code ProgramReminderJob}.
     */
    @Column(name = "reminder_sent_for")
    private Instant reminderSentFor;

    /**
     * Début de la dernière séance que {@code RecurringSlotRolloverJob} a
     * retirée en avançant {@code startsAt}.
     *
     * <p>Sans elle, une séance passée d'un créneau récurrent devient
     * irrécupérable dès le passage suivant du job : la RRULE ne permet pas de
     * la retrouver, puisque le rollover a écrasé l'ancre dont elle se déduit.
     * C'est donc au moment où il avance la ligne — le seul instant où le
     * système sait encore de quel moment il parle — que le job l'inscrit ici.
     *
     * <p>Nulle pour un créneau non récurrent : sa ligne <i>est</i> son unique
     * occurrence, rien ne l'a jamais déplacée. Voir {@link SlotOccurrence}.
     */
    @Column(name = "last_occurrence_start")
    private Instant lastOccurrenceStart;

    /** Fin de cette même séance, conservée telle qu'elle était vécue. */
    @Column(name = "last_occurrence_end")
    private Instant lastOccurrenceEnd;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SlotParticipation> participations = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
