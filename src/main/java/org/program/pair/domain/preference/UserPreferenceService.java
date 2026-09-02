package org.program.pair.domain.preference;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.UserPreferenceRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lit et écrit les réglages privés d'une personne. Voir
 * {@link UserPreferenceController} pour ce que cet espace est, et surtout pour ce
 * qu'il a été choisi de ne pas être.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class UserPreferenceService {

    /**
     * L'alphabet d'une clé. Une clé est un identifiant technique choisi par le
     * client, jamais une saisie d'utilisateur : on la borne pour qu'elle ne puisse
     * ni transporter de contenu, ni ressembler à un chemin. La base porte la même
     * contrainte — les deux disent la même chose, et c'est voulu : celle du
     * serveur rend une erreur lisible, celle de la base tient face à un autre
     * écrivain.
     */
    private static final Pattern CLE = Pattern.compile("^[a-zA-Z0-9._-]{1,64}$");

    private static final int VALEUR_MAX = 8192;

    private final UserPreferenceRepository repository;

    @Transactional(readOnly = true)
    public String read(UUID userId, String key) {
        exigerCleValide(key);
        return repository.findByUserIdAndKey(userId, key)
            .map(UserPreference::getValue)
            .orElseThrow(() -> new ResourceNotFoundException("Ce réglage n'existe pas."));
    }

    public String write(UUID userId, String key, String value) {
        exigerCleValide(key);
        if (value == null || value.length() > VALEUR_MAX) {
            throw new ValidationException(
                "Une préférence ne peut pas dépasser " + VALEUR_MAX + " caractères.");
        }
        UserPreference pref = repository.findByUserIdAndKey(userId, key)
            .orElseGet(() -> new UserPreference(userId, key, value, Instant.now()));
        pref.setValue(value);
        pref.setUpdatedAt(Instant.now());
        repository.save(pref);
        return value;
    }

    /** Idempotent : effacer une clé absente réussit, plutôt que de rendre 404. */
    public void erase(UUID userId, String key) {
        exigerCleValide(key);
        repository.deleteByUserIdAndKey(userId, key);
    }

    private static void exigerCleValide(String key) {
        if (key == null || !CLE.matcher(key).matches()) {
            throw new ValidationException(
                "Une clé de réglage ne peut contenir que lettres, chiffres, point, tiret "
                    + "et souligné, et faire 64 caractères au plus.");
        }
    }
}
