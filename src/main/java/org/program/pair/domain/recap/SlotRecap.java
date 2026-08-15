package org.program.pair.domain.recap;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.program.Schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * Carte-souvenir d'un créneau : ce qu'il reste d'un moment collectif une fois
 * qu'il est passé.
 *
 * <p>Créée à la <b>première contribution</b> (premier vote d'ambiance, premier
 * mot d'hôte, première photo partagée), jamais d'avance : une carte vide n'a
 * rien à montrer, et en fabriquer une pour chaque créneau passé remplirait la
 * base de bruit.
 *
 * <p>Elle ne porte rien qui décrive les personnes — ni note, ni compteur de
 * réactions, ni palmarès. Son seul but est de donner envie de rejoindre le
 * prochain créneau.
 */
@Entity
@Table(name = "slot_recaps",
    // Une carte par SÉANCE, pas par ligne de créneau. La contrainte d'origine
    // portait sur schedule_id seul : un créneau hebdomadaire ne pouvait donc
    // porter qu'une carte, réécrite d'une semaine sur l'autre.
    uniqueConstraints = @UniqueConstraint(name = "uq_recap_occurrence",
        columnNames = {"schedule_id", "occurrence_start"}),
    indexes = {
        @Index(name = "idx_recaps_schedule", columnList = "schedule_id"),
        @Index(name = "idx_recaps_visibility", columnList = "visibility, published_at"),
        @Index(name = "idx_recaps_occurrence", columnList = "occurrence_start")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SlotRecap {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    /**
     * La séance dont cette carte est la trace, nommée par son début.
     *
     * <p>C'est ici, et non dans {@code schedule.startsAt}, que se lit la date
     * d'un souvenir. Sur un créneau récurrent la ligne a déjà été avancée par
     * le rollover : s'y fier datait la carte d'un mardi <i>prochain</i>. Voir
     * {@link org.program.pair.domain.program.SlotOccurrence}.
     */
    @Column(name = "occurrence_start", nullable = false)
    private Instant occurrenceStart;

    /**
     * Fin de cette séance, figée à la naissance de la carte.
     *
     * <p>Copiée plutôt que relue depuis le créneau : la fenêtre de contribution
     * de sept jours en découle, et une carte doit rester figée. Sans cette
     * copie, allonger la durée d'un créneau rouvrirait une fenêtre close sous
     * les yeux de quelqu'un qui a déjà partagé la carte.
     */
    @Column(name = "occurrence_end", nullable = false)
    private Instant occurrenceEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecapVisibility visibility = RecapVisibility.PRIVATE;

    @Column(name = "host_note", length = 400)
    private String hostNote;

    /**
     * Présents confirmés sur ce créneau, dénormalisé comme
     * {@code Schedule.participantCount}. Rafraîchi à chaque contribution et à
     * chaque confirmation de présence — c'est un effectif, jamais un score.
     */
    @Column(name = "attendee_count", nullable = false)
    @Builder.Default
    private Integer attendeeCount = 0;

    /** Instant du premier passage en {@code PUBLIC}, nul tant que la carte ne l'est pas. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Les deux horodatages sont {@code NOT NULL} en base et n'ont pas de
     * valeur par défaut applicative ailleurs : on les pose ici plutôt que de
     * compter sur le {@code DEFAULT NOW()} du schéma, qu'un INSERT explicite
     * court-circuite.
     */
    @PrePersist
    void stampCreation() {
        Instant now = Instant.now();
        if (createdAt == null) {
            this.createdAt = now;
        }
        if (updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
