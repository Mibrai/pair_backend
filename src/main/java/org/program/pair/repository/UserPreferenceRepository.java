package org.program.pair.repository;

import org.program.pair.domain.preference.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Les préférences privées, lues et écrites par leur seul propriétaire.
 *
 * <p><b>Aucune méthode ne cherche par valeur, et il ne faut pas en ajouter.</b>
 * C'est ce qui garantit qu'un réglage opaque ne devient pas une donnée
 * interrogeable — la propriété entière de cet espace tient à cette absence.
 */
@Repository
public interface UserPreferenceRepository extends JpaRepository<UserPreference, UserPreference.Cle> {

    Optional<UserPreference> findByUserIdAndKey(UUID userId, String key);

    void deleteByUserIdAndKey(UUID userId, String key);
}
