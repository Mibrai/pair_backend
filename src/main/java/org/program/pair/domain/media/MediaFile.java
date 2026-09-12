package org.program.pair.domain.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * La ligne de propriété d'un fichier déposé (table {@code media_files}, V109).
 *
 * <p><b>Ce que cette ligne répond, et que rien ne répondait avant.</b> « Qui a
 * déposé ce chemin ? » Sans elle, la seule suppression possible était
 * inconditionnelle : c'est ce qui a permis à n'importe quel compte authentifié
 * d'effacer le fichier de n'importe qui, pièces jointes de signalement
 * comprises, puisque les chemins circulent en clair dans les DTO.
 *
 * <p><b>Le chemin est la clé.</b> {@code « user_avatar/<uuid>.jpg »} — sans le
 * préfixe d'URL {@code /api/media/files/}, qui appartient à la couche HTTP et
 * non au stockage. C'est la forme que {@code StorageService} manipule, celle
 * que {@code load}, {@code delete} et {@code exists} reçoivent.
 *
 * <p><b>Absence de ligne = personne ne peut supprimer.</b> Cette entité n'est
 * jamais créée « au besoin » au moment d'une suppression : si elle manque, le
 * fichier reste. Voir le commentaire de tête de V109.
 *
 * <p><b>Écrite depuis un service, jamais depuis un rappel d'entité.</b>
 * {@code LocalStorageService.store} l'enregistre après avoir copié les octets.
 * La leçon du 12/09 tient : une écriture soumise pendant le commit d'une autre
 * transaction a bloqué définitivement six fils HTTP (voir
 * {@code domain/indexation/ApresCommit}). Aucun {@code @PostPersist} ici.
 */
@Entity
@Table(name = "media_files")
public class MediaFile {

    /** Le chemin relatif au stockage, sans préfixe d'URL. Clé primaire. */
    @Id
    @Column(name = "path", nullable = false, length = 300)
    private String path;

    /**
     * Le compte qui a déposé les octets — {@code principal.getId()}, toujours.
     *
     * <p>{@code null} est un état légitime : la contrainte est
     * {@code ON DELETE SET NULL}, donc un compte supprimé laisse ses fichiers
     * sans déposant. Un fichier sans déposant n'est supprimable par personne,
     * comme un fichier sans ligne.
     */
    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private MediaPurpose purpose;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MediaFile() {
    }

    public static MediaFile depose(String path, UUID uploadedBy, MediaPurpose purpose) {
        MediaFile fichier = new MediaFile();
        fichier.path = path;
        fichier.uploadedBy = uploadedBy;
        fichier.purpose = purpose == null ? MediaPurpose.UNKNOWN : purpose;
        fichier.createdAt = Instant.now();
        return fichier;
    }

    /** Le déposant est-il bien ce compte ? {@code false} si la ligne n'a plus de déposant. */
    public boolean deposePar(UUID userId) {
        return userId != null && userId.equals(uploadedBy);
    }

    public String getPath() {
        return path;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public MediaPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(MediaPurpose purpose) {
        this.purpose = purpose;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
