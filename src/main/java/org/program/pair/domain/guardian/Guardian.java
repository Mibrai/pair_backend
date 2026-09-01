package org.program.pair.domain.guardian;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Un contact d'urgence désigné par quelqu'un : qui prévenir s'il ne confirme pas
 * son retour.
 *
 * <p><b>Deux natures de contact, une seule table.</b> Ou bien le contact a un
 * compte meetDo ({@code memberId} renseigné), ou bien il est hors meetDo et l'on
 * garde de quoi le joindre ({@code name}, {@code phone}, {@code email}). Les deux
 * ne se mélangent pas sur une même ligne : un membre est joint dans l'application,
 * un contact externe par le canal qu'il a laissé.
 *
 * <p><b>Ce qui n'est pas ici, et ne doit pas y être : aucune adresse postale.</b>
 * Rien dans ce flux n'envoie de courrier ; une adresse serait une donnée sensible
 * de tiers sans usage. La demande l'exclut explicitement, et l'entité la refuse
 * par construction — un champ absent est un champ que personne ne remplira par
 * mégarde.
 *
 * <p><b>Le téléphone est stocké en forme normalisée</b> (E.164, voir
 * {@link PhoneNumber}) et non tel que saisi : c'est cette forme qui sert à joindre
 * le contact et à confronter le numéro à la liste des refus. Deux écritures du
 * même numéro doivent désigner le même contact, sans quoi la règle du refus global
 * se contournerait.
 *
 * <p><b>{@code ownerId} et {@code memberId} sont des UUID nus</b>, pas des
 * associations. Les chemins chauds de ce domaine — « les contacts acceptés de X »,
 * « le contact que porte ce jeton » — n'ont besoin d'aucune donnée de l'utilisateur
 * ; les noms se résolvent dans le service quand une réponse les demande. C'est la
 * même sobriété que {@code Report}, et elle évite de traîner un {@code User}
 * complet derrière chaque contact.
 */
@Entity
@Table(name = "guardians")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Guardian {

    @Id
    @GeneratedValue
    private UUID id;

    /** Celui qui a désigné ce contact. */
    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    /** L'identifiant du compte meetDo du contact, s'il en a un. Exclusif du trio ci-dessous. */
    @Column(name = "member_id")
    private UUID memberId;

    /** Nom du contact hors meetDo, tel que son parrain l'a saisi. */
    @Column(name = "name", length = 120)
    private String name;

    /** Téléphone du contact hors meetDo, en forme E.164 normalisée. */
    @Column(name = "phone", length = 20)
    private String phone;

    /** E-mail du contact hors meetDo. */
    @Column(name = "email", length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_state", nullable = false, length = 10)
    @Builder.Default
    private ConsentState consentState = ConsentState.PENDING;

    /**
     * Le jeton porté par les liens accepter / refuser du message ①. Opaque, tiré
     * au hasard, unique — c'est la seule chose que le contact présente, et il ne
     * doit rien laisser deviner de qui l'a désigné.
     */
    @Column(name = "consent_token", nullable = false, unique = true, length = 22)
    private String consentToken;

    /** Quand le message ① a été envoyé. Null tant qu'aucune invitation n'est partie. */
    @Column(name = "invited_at")
    private Instant invitedAt;

    /** Quand le contact a répondu — accepté ou refusé. Null tant qu'il est PENDING. */
    @Column(name = "responded_at")
    private Instant respondedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Un contact avec un compte meetDo, par opposition à un contact externe. */
    public boolean isMember() {
        return memberId != null;
    }
}
