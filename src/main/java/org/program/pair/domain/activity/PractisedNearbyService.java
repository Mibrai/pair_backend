package org.program.pair.domain.activity;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.PractisedNearbyDto;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * « Déjà pratiquée près de toi » : un oui ou un non, pour quelques activités et
 * une position (demande mobile du 14/09/2026, modules/rappel, P-MU-22 A1).
 *
 * <p><b>Jamais un compte.</b> {@code /activities/suggested} rend un
 * {@code practitionersNearby} (retiré du contrat le 14/09) ; cette route-ci n'en laisse sortir aucun, pas même
 * de la base (la requête ne rend que les identifiants qui passent le seuil).
 *
 * <p><b>Le seuil est à trois personnes, et la position est arrondie.</b> Un oui
 * dès une personne permettrait, en déplaçant {@code lat}/{@code lng}, de situer
 * quelqu'un qui pratique seul une activité rare. Trois personnes distinctes
 * rendent la réponse collective ; l'arrondi au centième de degré (environ 1 km)
 * ôte l'intérêt d'un balayage fin, et le plafond de débit
 * ({@code RateLimiter.checkPractisedNearby}) en borne le volume.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PractisedNearbyService {

    /** Le rayon de l'Explorer et de {@link SuggestedActivityService} : une seule réponse par endroit. */
    static final int RADIUS_METERS = 25_000;

    /** Personnes distinctes, autres que l'appelant, à partir desquelles la réponse est oui. */
    static final int SEUIL_PERSONNES = 3;

    /** Le bloc de doublons en montre trois ; dix laissent la marge d'une frappe rapide. */
    static final int MAX_IDENTIFIANTS = 10;

    private final ActivityRepository activityRepository;

    public List<PractisedNearbyDto> practisedNearby(UUID requesterId, Double lat, Double lng,
                                                    List<String> activityIds) {
        if (lat == null || lat < -90 || lat > 90 || lng == null || lng < -180 || lng > 180) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_POSITION_HORS_BORNES",
                "La latitude doit être comprise entre -90 et 90, la longitude entre -180 et 180.");
        }
        Set<UUID> demandes = lireIdentifiants(activityIds);

        Set<UUID> connues = new HashSet<>();
        activityRepository.findAllById(demandes).forEach(a -> connues.add(a.getId()));
        if (connues.isEmpty()) {
            return List.of();
        }

        Set<UUID> pratiquees = new HashSet<>(activityRepository.findPractisedNearby(
            arrondir(lat), arrondir(lng), RADIUS_METERS, requesterId, connues, SEUIL_PERSONNES));

        // Dans l'ordre reçu ; un identifiant inconnu est omis, jamais un 404.
        return demandes.stream()
            .filter(connues::contains)
            .map(id -> new PractisedNearbyDto(id, pratiquees.contains(id)))
            .toList();
    }

    private static Set<UUID> lireIdentifiants(List<String> brut) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (brut != null) {
            for (String valeur : brut) {
                if (valeur == null || valeur.isBlank()) {
                    continue;
                }
                try {
                    ids.add(UUID.fromString(valeur.strip()));
                } catch (IllegalArgumentException e) {
                    throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_IDENTIFIANTS_ACTIVITE",
                        "Entre 1 et 10 identifiants d'activité sont attendus.");
                }
            }
        }
        if (ids.isEmpty() || brut.size() > MAX_IDENTIFIANTS) {
            throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_IDENTIFIANTS_ACTIVITE",
                "Entre 1 et 10 identifiants d'activité sont attendus.");
        }
        return ids;
    }

    private static double arrondir(double degres) {
        return Math.round(degres * 100.0) / 100.0;
    }
}
