package org.program.pair.domain.program;

public enum ParticipationStatus {
    INTERESTED,
    /**
     * En liste d'attente : la personne veut venir, le créneau est plein.
     *
     * <p><b>Ne compte jamais dans la capacité.</b> Le décompte des places
     * ({@code countConfirmedParticipants}) ne retient que {@code CONFIRMED}, et
     * c'est ce qui rend la promotion possible : élargir ce filtre remplirait le
     * créneau avec sa propre file, et plus personne ne serait jamais promu.
     */
    WAITLISTED,
    CONFIRMED,
    DECLINED,
    WITHDRAWN
}
