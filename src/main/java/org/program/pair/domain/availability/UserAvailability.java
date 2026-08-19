package org.program.pair.domain.availability;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Une case de disponibilité : tel jour, tel moment.
 *
 * <p>Aucun champ hors de la clé : la ligne <i>est</i> l'information. Déclarer
 * deux fois « mardi soir » n'a pas de sens, et la clé composite le dit mieux
 * qu'une contrainte ajoutée après coup.
 */
@Entity
@Table(name = "user_availability")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserAvailability {

    @EmbeddedId
    private Id id;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Column(name = "user_id", nullable = false)
        private UUID userId;

        /** 1 = lundi … 7 = dimanche, numérotation ISO. */
        @Column(name = "day_of_week", nullable = false)
        private Short dayOfWeek;

        @Enumerated(EnumType.STRING)
        @Column(name = "time_slot", nullable = false, length = 20)
        private TimeSlot timeSlot;
    }
}
