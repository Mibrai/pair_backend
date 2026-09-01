package org.program.pair.domain.incident;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un incident de sécurité, dans un registre séparé de la modération.
 *
 * <p>La priorité 5 en écrit un seul type — le « perdu en chemin » de la boucle
 * aller, une cible {@link IncidentTarget#TRANSIT}. La priorité 7 ajoutera les
 * routes {@code POST /incidents} et {@code GET /incidents/me} par-dessus cette
 * même table, et les pièces jointes.
 *
 * <p><b>Ce que cette entité rend possible, et que {@code Attendance} rendait
 * impossible.</b> Un « perdu en chemin » ne doit compter ni comme une absence, ni
 * contre la fiabilité, la série ou les badges — sinon le produit punit quelqu'un
 * pour un incident de sécurité, et cette personne désarme la veille la fois
 * d'après. Écrire l'événement ici, et jamais une ligne {@code Attendance(présent =
 * false)}, est ce qui tient cette règle : le dénominateur du signal de fiabilité
 * ne compte que les créneaux portant une ligne {@code Attendance}, et il n'y en a
 * pas.
 */
@Entity
@Table(name = "incidents")
@EntityListeners(AuditingEntityListener.class)
public class Incident {

    @Id
    @GeneratedValue
    private UUID id;

    /** La personne concernée par l'incident — celle qui l'a vécu ou signalé. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target", nullable = false, length = 12)
    private IncidentTarget target;

    /** La veille à l'origine de l'incident, si elle existe. */
    @Column(name = "watch_id")
    private UUID watchId;

    /** Le créneau concerné, si l'incident s'y rattache. */
    @Column(name = "schedule_id")
    private UUID scheduleId;

    @Column(name = "note", length = 500)
    private String note;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Incident() {}

    public static Incident lostOnTheWay(UUID userId, UUID watchId, UUID scheduleId) {
        Incident i = new Incident();
        i.userId = userId;
        i.target = IncidentTarget.TRANSIT;
        i.watchId = watchId;
        i.scheduleId = scheduleId;
        i.note = "Perdu en chemin : trois demandes d'arrivée sans réponse.";
        return i;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public IncidentTarget getTarget() { return target; }
    public UUID getWatchId() { return watchId; }
    public UUID getScheduleId() { return scheduleId; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
