package org.program.pair.repository;

import org.program.pair.domain.guardian.RefusedContact;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.UUID;

@Repository
public interface RefusedContactRepository extends JpaRepository<RefusedContact, UUID> {

    /**
     * Ce numéro figure-t-il dans les refus ? On passe l'ensemble des empreintes
     * candidates — une par version de clé connue — parce qu'une empreinte
     * déterministe écrite sous une ancienne clé ne se retrouve que si on la
     * recalcule sous cette clé. Chercher sur l'ensemble d'un coup évite à la fois
     * de rater un refus après rotation et de balayer la table ligne à ligne.
     */
    boolean existsByPhoneHashIn(Collection<String> phoneHashes);
}
