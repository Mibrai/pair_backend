package org.program.pair.domain.program;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * Une relance de cycle déjà partie — le registre qui porte les deux plafonds.
 *
 * <p>Écrit <b>dans la transaction du job</b>, et non déduit de la table
 * {@code notifications} : {@code NotificationService.notify} est {@code @Async},
 * si bien qu'un job qui s'y plafonnerait lui-même ne verrait pas ce qu'il vient
 * d'envoyer, et enverrait deux fois dans la même passe.
 *
 * <p>L'unicité {@code (programId, stage)} est portée par la base (V102). Elle dit
 * « une seule fois par programme et par étape », et comme il n'y a que trois
 * étapes elle rend du même geste le plafond de trois relances par programme
 * impossible à dépasser — y compris par un code futur qui ne le connaîtrait pas.
 *
 * @see org.program.pair.domain.program.jobs.CycleNudgeJob
 */
@Entity
@Table(name = "cycle_nudges",
    uniqueConstraints = @UniqueConstraint(name = "uq_cycle_nudge_program_stage",
        columnNames = {"program_id", "stage"}),
    indexes = @Index(name = "idx_cycle_nudge_user_sent", columnList = "user_id, sent_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CycleNudge {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    private Program program;

    /** L'auteur relancé. Dénormalisé : le plafond par personne ne veut pas de jointure. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Short stage;

    /**
     * Horizon du programme au moment de la relance — étape 7 seulement, et jamais
     * relu. Il sert à expliquer après coup pourquoi la relance est partie ce
     * jour-là.
     */
    @Column(name = "horizon")
    private Instant horizon;

    @Column(name = "sent_at", nullable = false)
    @Builder.Default
    private Instant sentAt = Instant.now();
}
