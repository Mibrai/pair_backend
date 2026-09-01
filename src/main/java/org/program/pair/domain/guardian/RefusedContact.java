package org.program.pair.domain.guardian;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un numéro qui a refusé d'être sollicité, et qu'aucun compte ne peut plus
 * désigner.
 *
 * <p><b>Le numéro n'est pas ici — seulement son empreinte.</b> Stocker les numéros
 * en clair reviendrait à constituer un fichier de personnes qui n'ont jamais voulu
 * de ce produit, précisément ce qu'on n'a aucune raison de détenir. La ligne porte
 * donc l'empreinte {@code HMAC-SHA256(E.164, poivre)} sous une clé qui vit hors de
 * la base : une fuite de la base seule ne rend aucun numéro. Un hachage nu ne
 * suffirait pas — l'espace des mobiles français s'énumère en secondes — d'où le
 * poivre, exactement le raisonnement du §7.4 de la demande.
 *
 * <p><b>Ni qui a refusé, ni qui avait désigné.</b> La ligne ne référence aucun
 * utilisateur. Le refus est un fait attaché à un numéro, pas une relation entre
 * deux personnes : y accrocher un {@code ownerId} ou un horodatage nominatif en
 * ferait une trace de qui connaît qui, ce que le module refuse d'être.
 *
 * <p><b>Empreinte déterministe, et {@code key_version} conservée.</b> À la
 * différence du code de retour, cette empreinte doit se <i>consulter par numéro</i>
 * — « ce numéro est-il déjà refusé ? » — donc sans sel par ligne, sous quoi la
 * recherche serait impossible. La version de clé est gardée pour deux raisons :
 * pouvoir tourner le poivre, et surtout garantir qu'une rotation ne <b>débloque</b>
 * personne — tant que l'ancienne clé reste configurée, l'empreinte reste
 * retrouvable.
 */
@Entity
@Table(name = "refused_contacts")
@EntityListeners(AuditingEntityListener.class)
public class RefusedContact {

    @Id
    @GeneratedValue
    private UUID id;

    /** {@code HMAC-SHA256(numéro E.164, poivre)}, en hexadécimal. Unique. */
    @Column(name = "phone_hash", nullable = false, unique = true, length = 64)
    private String phoneHash;

    /** La version de clé sous laquelle {@link #phoneHash} a été calculée. */
    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefusedContact() {}

    public RefusedContact(String phoneHash, int keyVersion) {
        this.phoneHash = phoneHash;
        this.keyVersion = keyVersion;
    }

    public UUID getId() {
        return id;
    }

    public String getPhoneHash() {
        return phoneHash;
    }

    public int getKeyVersion() {
        return keyVersion;
    }
}
