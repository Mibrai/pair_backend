package org.program.pair.repository;

import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.domain.guardian.GuardianRole;
import org.program.pair.domain.guardian.Guardian;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GuardianRepository extends JpaRepository<Guardian, UUID> {

    /** Les contacts d'un utilisateur, du plus récent au plus ancien. */
    List<Guardian> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);

    /** Le contact que porte un jeton de consentement — le seul point d'entrée du flux public. */
    Optional<Guardian> findByConsentToken(String consentToken);

    boolean existsByConsentToken(String consentToken);

    /** Un contact précis de cet utilisateur : l'appartenance est vérifiée dans la même requête. */
    Optional<Guardian> findByIdAndOwnerId(UUID id, UUID ownerId);

    /** Empêche de redésigner deux fois le même membre. */
    boolean existsByOwnerIdAndMemberId(UUID ownerId, UUID memberId);

    /** Les contacts acceptés d'un utilisateur : les seuls qu'une veille peut prendre. */
    List<Guardian> findByOwnerIdAndConsentState(UUID ownerId, ConsentState consentState);

    /** Le contact qui porte ce rôle chez cette personne, s'il y en a un. Au plus un. */
    Optional<Guardian> findByOwnerIdAndRole(UUID ownerId, GuardianRole role);
}
