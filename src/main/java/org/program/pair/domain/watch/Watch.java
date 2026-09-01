package org.program.pair.domain.watch;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Une veille retour : « si je ne confirme pas mon retour à temps, prévenez ce
 * proche ».
 *
 * <p><b>{@code deadlineAt} est figé à l'armement, et n'est plus jamais dérivé du
 * créneau.</b> C'est le point de conception qui gouverne cette entité. Un créneau
 * récurrent voit son {@code starts_at} réécrit toutes les dix minutes par le
 * rollover ; une échéance recalculée à chaque lecture fuirait donc devant elle, et
 * le proche lirait la date de la semaine suivante. L'échéance est donc une colonne,
 * posée une fois — par défaut la fin du créneau plus une heure — et déplacée
 * ensuite seulement par les gestes qui le disent (un snooze, une interruption),
 * jamais par le passage du temps sur le créneau. C'est la même leçon que
 * {@code SlotSafetyShare} a apprise avant celle-ci.
 *
 * <p><b>Le serveur tient l'horloge, l'entité en porte l'état.</b> {@code state} et
 * {@code remindersSent} ne sont pas de l'affichage : ce sont les variables sur
 * lesquelles les minuteurs du module s'appuieront pour savoir quoi envoyer et
 * quand. L'application n'en planifie aucun.
 *
 * <p>{@code userId}, {@code guardianId}, {@code backupGuardianId} sont des UUID
 * nus, comme partout dans ce module : les chemins chauds n'ont besoin d'aucune
 * donnée jointe, et le contact se relit par son identifiant quand une réponse le
 * demande.
 */
@Entity
@Table(name = "watches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Watch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private WatchState state = WatchState.ARMED;

    @Column(name = "armed_at", nullable = false)
    @Builder.Default
    private Instant armedAt = Instant.now();

    /** Null tant que la personne n'a pas validé son arrivée sur place. */
    @Column(name = "arrival_confirmed_at")
    private Instant arrivalConfirmedAt;

    @Column(name = "interrupted_at")
    private Instant interruptedAt;

    /** L'échéance, figée à l'armement. Voir la note de classe. */
    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    /** 0 à 3 rappels de retour envoyés. */
    @Column(name = "reminders_sent", nullable = false)
    @Builder.Default
    private int remindersSent = 0;

    /** Le contact principal — un contact accepté de l'utilisateur. */
    @Column(name = "guardian_id", nullable = false)
    private UUID guardianId;

    /** Le contact de secours, s'il y en a un. */
    @Column(name = "backup_guardian_id")
    private UUID backupGuardianId;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Le jeton de la page de statut publique. Nul jusqu'à l'escalade : le lien
     * d'urgence naît avec l'alerte, pas à l'armement, pour que la veille ne
     * devienne pas un mouchard qui montrerait au contact chaque soirée.
     */
    @Column(name = "public_token", length = 22)
    private String publicToken;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean estActive() {
        return state.estActive();
    }
}
