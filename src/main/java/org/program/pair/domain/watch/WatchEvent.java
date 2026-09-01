package org.program.pair.domain.watch;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Une ligne de la chronologie d'une veille : un fait, et quand il a eu lieu.
 *
 * <p>La chronologie est ce que rend {@code GET /watches/{id}}, et ce à quoi
 * certains gestes doivent « s'inscrire » — le renvoi du code, par exemple. Une
 * table à part plutôt que des colonnes sur {@code Watch} : les faits s'ajoutent
 * sans se remplacer, et leur nombre n'est pas borné à l'avance.
 *
 * <p>La ligne ne porte pas d'identité de tiers : un événement dit ce qui est
 * arrivé à la veille, pas qui l'a vu. Le {@code detail} est un court texte
 * technique optionnel, jamais un contenu libre saisi par quelqu'un.
 */
@Entity
@Table(name = "watch_events")
public class WatchEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "watch_id", nullable = false)
    private UUID watchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 40)
    private WatchEventType type;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "detail", length = 200)
    private String detail;

    protected WatchEvent() {}

    public WatchEvent(UUID watchId, WatchEventType type, Instant occurredAt) {
        this.watchId = watchId;
        this.type = type;
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWatchId() {
        return watchId;
    }

    public WatchEventType getType() {
        return type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public String getDetail() {
        return detail;
    }
}
