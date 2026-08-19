package org.program.pair.domain.availability;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.availability.dto.AvailabilitySlotDto;
import org.program.pair.repository.UserAvailabilityRepository;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Les moments où quelqu'un est généralement libre.
 *
 * <p>Remplacement complet, comme les langues : l'écran est une grille de sept
 * jours sur trois tranches, et le client envoie ce qu'il veut voir coché.
 * Ajouter et retirer case par case l'obligerait à tenir un état intermédiaire
 * pour rien.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserAvailabilityService {

    /** Sept jours, trois tranches : la grille entière. */
    private static final int MAX_SLOTS = 21;

    private final UserAvailabilityRepository availabilityRepository;

    @Transactional(readOnly = true)
    public List<AvailabilitySlotDto> list(UUID userId) {
        return availabilityRepository.findByUserId(userId).stream()
            .map(a -> new AvailabilitySlotDto(a.getId().getDayOfWeek(), a.getId().getTimeSlot()))
            .toList();
    }

    public List<AvailabilitySlotDto> replace(UUID userId, List<AvailabilitySlotDto> slots) {
        if (slots.size() > MAX_SLOTS) {
            throw new ValidationException(
                "La grille ne compte que " + MAX_SLOTS + " cases.");
        }

        // Déduplication avant écriture : cocher deux fois la même case n'est pas
        // une erreur de l'utilisateur, et la clé composite la refuserait par une
        // violation d'intégrité plutôt que par un message lisible.
        Set<UserAvailability.Id> unique = new LinkedHashSet<>();
        for (AvailabilitySlotDto slot : slots) {
            unique.add(new UserAvailability.Id(userId, slot.dayOfWeek(), slot.timeSlot()));
        }

        availabilityRepository.deleteByUserId(userId);
        unique.forEach(id -> availabilityRepository.save(new UserAvailability(id)));

        return list(userId);
    }
}
