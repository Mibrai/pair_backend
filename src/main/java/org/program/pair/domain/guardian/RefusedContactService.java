package org.program.pair.domain.guardian;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.repository.RefusedContactRepository;
import org.program.pair.shared.security.Pepper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * La liste des numéros qui ont refusé d'être sollicités : la consulter, et
 * l'alimenter.
 *
 * <p>Le numéro n'entre jamais ici en clair — seulement son empreinte sous le
 * poivre. La classe ne connaît donc que des empreintes ; le numéro E.164 lui est
 * passé le temps d'un calcul et n'est stocké nulle part.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RefusedContactService {

    private final RefusedContactRepository repository;
    private final Pepper pepper;

    /**
     * Ce numéro a-t-il refusé ?
     *
     * <p>On calcule l'empreinte sous <b>chaque</b> version de clé connue et l'on
     * cherche l'ensemble d'un coup. Une empreinte écrite sous une ancienne clé ne
     * se retrouverait pas si l'on ne la recalculait que sous la clé courante — et
     * ne pas la retrouver reviendrait à débloquer, en silence, un numéro qui avait
     * refusé. C'est le mode d'échec le plus grave de cette liste, d'où le balayage
     * de toutes les versions plutôt que de la seule courante.
     */
    @Transactional(readOnly = true)
    public boolean estRefuse(String e164) {
        Set<String> candidats = pepper.versions().stream()
            .map(version -> pepper.empreinteDeterministe(e164, version))
            .collect(Collectors.toSet());
        return repository.existsByPhoneHashIn(candidats);
    }

    /**
     * Inscrit un numéro dans les refus, définitivement.
     *
     * <p>Idempotent : deux refus du même numéro ne font qu'une ligne. La course —
     * deux refus simultanés — est tranchée par la contrainte d'unicité, pas par le
     * test préalable, et un doublon rattrapé n'est pas une erreur ici.
     */
    public void refuser(String e164) {
        if (estRefuse(e164)) {
            return;
        }
        try {
            repository.save(new RefusedContact(
                pepper.empreinteDeterministe(e164, pepper.versionCourante()),
                pepper.versionCourante()));
        } catch (DataIntegrityViolationException course) {
            // Un refus concurrent a posé la même empreinte entre le test et
            // l'écriture. Le résultat voulu est atteint : le numéro est refusé.
            log.debug("Refus concurrent du même numéro, déjà inscrit.");
        }
    }
}
