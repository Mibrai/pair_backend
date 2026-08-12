package org.program.pair.domain.program;

import org.program.pair.repository.SlotParticipationRepository;

import java.util.UUID;

/**
 * Règle de visibilité du lieu d'un créneau, partagée par tous les points
 * d'entrée qui exposent un Schedule (feed /api/slots, recherche
 * sémantique...) : ne jamais diverger, sinon un créneau privé finit par
 * fuiter sa position via un chemin qui a oublié la règle.
 */
public final class SlotAddressVisibility {

    private SlotAddressVisibility() {}

    public record Resolved(Double lat, Double lng, String displayAddress) {
        private static final Resolved HIDDEN = new Resolved(null, null, null);
    }

    /**
     * lat/lng/adresse ne sont renvoyés que si le lieu est PUBLIC, ou PRIVATE
     * avec showExactAddress=true, ou si l'appelant a déjà une participation
     * CONFIRMED sur ce créneau. Un lieu ONLINE n'a jamais de coordonnées.
     * Sinon : tout est null (seul le nom générique du lieu reste visible côté
     * appelant), le créneau restant par ailleurs normalement trouvable.
     */
    public static Resolved resolve(Schedule slot, UUID requesterId,
                                    SlotParticipationRepository participationRepository) {
        if (slot.getPlaceType() == PlaceType.ONLINE || slot.getLocation() == null) {
            return Resolved.HIDDEN;
        }

        boolean canSeeExactPlace = slot.getPlaceType() == PlaceType.PUBLIC
            || Boolean.TRUE.equals(slot.getShowExactAddress())
            || (requesterId != null && participationRepository
                .existsByScheduleIdAndUserIdAndStatus(slot.getId(), requesterId, ParticipationStatus.CONFIRMED));

        if (!canSeeExactPlace) {
            return Resolved.HIDDEN;
        }

        return new Resolved(slot.getLocation().getY(), slot.getLocation().getX(), slot.getAddressPublic());
    }

    /**
     * Adresse diffusable <b>sans connaître le demandeur</b> : {@code null} dès
     * qu'il faut savoir qui regarde pour trancher.
     *
     * <p>Écrite pour le payload des notifications. Une notification est composée
     * une fois puis envoyée à N destinataires — {@code SLOT_CANCELLED} en prévient
     * tous les inscrits d'un seul payload — et son texte s'affiche sur un écran
     * verrouillé. Il n'y a donc ni demandeur unique à qui appliquer
     * {@link #resolve}, ni écran de garde derrière lequel se rattraper.
     *
     * <p>C'est {@link #resolve} amputé de sa seule branche qui dépend de
     * l'appelant (la participation {@code CONFIRMED}) : ce que cette méthode
     * renvoie, {@code resolve} le renverrait pour n'importe qui. Elle ne peut donc
     * pas élargir ce qui est déjà visible, seulement le restreindre.
     */
    public static String broadcastableAddress(Schedule slot) {
        return resolve(slot, null, null).displayAddress();
    }
}
