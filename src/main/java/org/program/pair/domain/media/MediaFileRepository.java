package org.program.pair.domain.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Les lignes de propriété des fichiers déposés.
 *
 * <p>Dans {@code domain/media} et non dans {@code org.program.pair.repository} :
 * la table n'a qu'un seul lecteur, {@link MediaFileService}, et la garder à côté
 * de lui évite qu'un autre domaine décide de propriété de fichier sans passer
 * par le service qui porte la règle. Précédent dans le dépôt :
 * {@code domain/audit/AuditLogRepository}. Le scan de Spring Data part de
 * {@code org.program.pair}, donc l'emplacement ne change rien à la découverte.
 */
@Repository
public interface MediaFileRepository extends JpaRepository<MediaFile, String> {

    /** Tous les fichiers déposés par un compte. Pour un export ou une purge RGPD. */
    List<MediaFile> findByUploadedBy(UUID uploadedBy);
}
