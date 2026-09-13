package org.program.pair.domain.notification;

import org.program.pair.shared.logging.Masque;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.DeviceTokenRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    /**
     * Enregistrer ou mettre à jour un device token.
     *
     * <p><b>Un appareil appartient toujours au dernier compte qui s'y est
     * connecté</b> (P-BL-12). La colonne {@code token} est {@code UNIQUE}
     * (V11) et FCM n'émet qu'un jeton par installation : deux comptes ne
     * peuvent pas légitimement se partager le même. La méthode cherchait
     * pourtant {@code existsByUserIdAndToken(userId, token)} — donc le couple —
     * et repartait sur une insertion dès que le propriétaire différait, ce qui
     * violait la contrainte d'unicité : aucun gestionnaire ne couvrait
     * {@code DataIntegrityViolationException}, l'appel rendait {@code 500}, et
     * le jeton restait au compte précédent. Cela voulait dire deux choses, dont
     * la seconde est la plus grave : le nouveau compte ne recevait aucune push,
     * et l'<b>ancien</b> continuait de recevoir les siennes sur cet appareil —
     * une alerte de veille pouvait donc arriver sur le téléphone de quelqu'un
     * d'autre. On réattribue.
     *
     * <p><b>La course de deux enregistrements simultanés du même jeton</b> reste
     * possible : la lecture ci-dessous ne verrouille rien — il n'y a pas encore
     * de ligne à verrouiller dans le cas de l'insertion. La perdante casse alors
     * sur l'index unique, et c'est le gestionnaire
     * {@code DataIntegrityViolationException} de {@code GlobalExceptionHandler}
     * qui la rend en {@code 409} sans message technique. Le cas est bénin : le
     * client ré-enregistre son jeton à chaque démarrage, et le second appel
     * trouvera la ligne. Un {@code INSERT … ON CONFLICT (token) DO UPDATE} le
     * fermerait tout à fait, au prix d'une requête native qui doublerait la mise
     * à jour des champs ci-dessous.
     *
     * @param locale   langue des textes push pour cet appareil, déjà normalisée par
     *                 l'appelant ({@code "fr"}, {@code "en"}, {@code "de"}), ou
     *                 {@code null} pour ne pas y toucher — un ré-enregistrement
     *                 sans langue (client historique) ne doit pas effacer celle
     *                 qu'un enregistrement précédent avait posée
     * @param timezone fuseau de l'appareil, étiquette IANA déjà validée par
     *                 l'appelant, ou {@code null} — même règle que la langue :
     *                 un ré-enregistrement sans fuseau n'efface pas celui qui
     *                 était là
     */
    public DeviceToken registerToken(UUID userId, String token, DevicePlatform platform,
                                     String deviceName, String locale, String timezone) {
        // Le jeton, et non le couple (compte, jeton) : c'est lui qui porte la
        // contrainte d'unicité, donc lui qui décide s'il y a une ligne à mettre
        // à jour ou une ligne à créer.
        DeviceToken existing = deviceTokenRepository.findByToken(token).orElse(null);

        if (existing != null) {
            // getId() sur un proxy paresseux ne déclenche pas son chargement :
            // l'identifiant est déjà connu du proxy.
            UUID proprietaireActuel = existing.getUser().getId();
            if (!proprietaireActuel.equals(userId)) {
                existing.setUser(userRepository.getReferenceById(userId));
                // Jamais le jeton entier au journal : il suffit à adresser une
                // push à cet appareil (P-BS-19). Une empreinte suffit à relier
                // deux lignes de journal entre elles.
                log.info("Device token {} reassigned from user {} to user {}",
                    empreinte(token), proprietaireActuel, userId);
            }

            existing.setLastUsedAt(Instant.now());
            if (deviceName != null) {
                existing.setDeviceName(deviceName);
            }
            if (locale != null) {
                existing.setLocale(locale);
            }
            // Le chemin qui compte : un fuseau change en cours de vie de l'app —
            // on voyage — et le client ré-enregistre le MÊME jeton pour le dire.
            // Ne mettre à jour qu'à la création aurait figé le fuseau du premier
            // enregistrement pour toute la vie de l'appareil.
            if (timezone != null) {
                existing.setTimezone(timezone);
            }
            return deviceTokenRepository.save(existing);
        }

        // Créer nouveau token
        DeviceToken deviceToken = DeviceToken.builder()
            .user(userRepository.getReferenceById(userId))
            .token(token)
            .platform(platform)
            .deviceName(deviceName)
            .locale(locale)
            .timezone(timezone)
            .createdAt(Instant.now())
            .lastUsedAt(Instant.now())
            .build();

        DeviceToken saved = deviceTokenRepository.save(deviceToken);
        log.info("Device token registered for user {} on platform {} (locale {}, timezone {})",
            userId, platform, locale, timezone);
        return saved;
    }

    /**
     * Détacher un jeton, <b>à condition qu'il soit celui de l'appelant</b>.
     *
     * <p>La signature portait le seul jeton et supprimait sans rien vérifier : le
     * jeton voyage en clair dans le chemin de {@code DELETE
     * /notifications/devices/{token}}, donc quiconque en connaissait un pouvait
     * faire taire les notifications de son propriétaire (P-BL-12, apport de
     * P-BS-10). Un jeton d'un autre compte, ou inconnu, ne fait rien ici et
     * l'appelant reçoit tout de même un {@code 204} : lui répondre {@code 404}
     * lui apprendrait quels jetons existent.
     */
    public void unregisterToken(UUID userId, String token) {
        if (!deviceTokenRepository.existsByUserIdAndToken(userId, token)) {
            log.debug("Device token {} not unregistered: not owned by user {}",
                empreinte(token), userId);
            return;
        }
        deviceTokenRepository.deleteByToken(token);
        log.info("Device token unregistered: {}", empreinte(token));
    }

    /**
     * Récupérer les tokens d'un utilisateur
     */
    @Transactional(readOnly = true)
    public List<DeviceToken> getUserTokens(UUID userId) {
        return deviceTokenRepository.findByUserId(userId);
    }

    /**
     * Supprimer tous les tokens d'un utilisateur.
     *
     * <p>Sans appelant à ce jour : la désactivation de compte doit l'appeler
     * (P-BL-01 / P-BL-12 étape 5), ce qui appartient au propriétaire de
     * {@code UserService}.
     */
    public void unregisterAllUserTokens(UUID userId) {
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(userId);
        deviceTokenRepository.deleteAll(tokens);
        log.info("All device tokens unregistered for user {}", userId);
    }

    /**
     * De quoi reconnaître un jeton dans un journal sans pouvoir s'en servir : une
     * empreinte, et non plus un préfixe du jeton lui-même (P-BS-19).
     */
    private static String empreinte(String token) {
        return Masque.jeton(token);
    }
}
