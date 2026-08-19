package org.program.pair.domain.language;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.language.dto.UserLanguageDto;
import org.program.pair.repository.UserLanguageRepository;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Les langues qu'une personne déclare parler.
 *
 * <p>Remplacement complet plutôt que retouches unitaires : le client envoie la
 * liste telle qu'il veut la voir, et le serveur l'aligne. Une API à trois verbes
 * — ajouter, modifier, retirer — obligerait le client à tenir un état
 * intermédiaire pour un écran où l'on coche des cases.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserLanguageService {

    /** Au-delà, ce n'est plus un profil, c'est une collection. */
    private static final int MAX_LANGUAGES = 10;

    private final UserLanguageRepository languageRepository;

    @Transactional(readOnly = true)
    public List<UserLanguageDto> list(UUID userId) {
        return languageRepository.findByUserId(userId).stream()
            .map(ul -> new UserLanguageDto(ul.getId().getLanguage(), ul.getProficiency()))
            .toList();
    }

    public List<UserLanguageDto> replace(UUID userId, List<UserLanguageDto> languages) {
        if (languages.size() > MAX_LANGUAGES) {
            throw new ValidationException(
                "Au plus " + MAX_LANGUAGES + " langues peuvent être déclarées.");
        }

        // Déduplication sur l'étiquette normalisée : « FR » et « fr » sont la
        // même langue, et la clé primaire composite refuserait la seconde par une
        // violation d'intégrité plutôt que par un message lisible.
        Map<String, LanguageProficiency> byTag = new LinkedHashMap<>();
        for (UserLanguageDto declared : languages) {
            byTag.put(declared.language().toLowerCase(Locale.ROOT), declared.proficiency());
        }

        languageRepository.deleteByUserId(userId);
        byTag.forEach((tag, proficiency) -> languageRepository.save(
            new UserLanguage(new UserLanguage.Id(userId, tag), proficiency)));

        return list(userId);
    }
}
