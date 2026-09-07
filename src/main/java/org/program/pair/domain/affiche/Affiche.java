package org.program.pair.domain.affiche;

import jakarta.persistence.*;
import lombok.*;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.user.User;

import java.time.Instant;
import java.util.UUID;

/**
 * L'affiche d'une personne pour une séance qu'elle a vécue.
 *
 * <p><b>Ce que cette table porte, et ce qu'elle ne portera jamais.</b> Le
 * visuel et la phrase — « cette séance-ci est la première en escalade » — sont
 * composés par le client à partir de ses propres cartes-souvenirs, et n'entrent
 * pas ici. Le serveur ne retient que <i>qu'une affiche existe, laquelle, et pour
 * qui</i> : le motif est une clé opaque, jamais lue, et l'audience est le seul
 * champ que le serveur interprète.
 *
 * <p><b>Le déclencheur est la présence, pas l'organisation.</b> Une affiche
 * s'appuie sur une {@code Attendance} confirmée ({@code was_present = true}) —
 * ce qui est précisément ce que la seule route de publication existante,
 * {@code PATCH /api/slots/{id}/recap/visibility}, ne savait pas faire : elle est
 * réservée à l'hôte, et le simple participant n'avait aucun endroit où publier.
 *
 * <p><b>Une affiche par personne et par SÉANCE</b>, jamais par ligne de créneau.
 * C'est la correction que {@link org.program.pair.domain.recap.SlotRecap} a déjà
 * dû subir : sur une série hebdomadaire, une clé portant {@code schedule_id}
 * seul aurait fait réécrire d'une semaine sur l'autre l'affiche d'un cours
 * régulier — la seule situation où quelqu'un en produit plusieurs. La séance est
 * nommée par son début, comme partout ailleurs (voir
 * {@link org.program.pair.domain.program.SlotOccurrence}).
 */
@Entity
@Table(name = "affiches",
    uniqueConstraints = @UniqueConstraint(name = "uq_affiche_user_occurrence",
        columnNames = {"user_id", "schedule_id", "occurrence_start"}),
    indexes = {
        @Index(name = "idx_affiches_user_published", columnList = "user_id, published_at"),
        @Index(name = "idx_affiches_published", columnList = "published_at")
    })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Affiche {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** L'auteur de l'affiche : celui qui y était, pas celui qui organisait. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "schedule_id", nullable = false)
    private Schedule schedule;

    /** La séance dont l'affiche est la trace, nommée par son début. */
    @Column(name = "occurrence_start", nullable = false)
    private Instant occurrenceStart;

    /**
     * Fin de cette séance, figée à la publication.
     *
     * <p>Copiée pour la même raison que sur la carte-souvenir : la fenêtre de
     * mise en avant en découle, et allonger un créneau des mois plus tard ne
     * doit pas remonter une affiche sur un profil.
     */
    @Column(name = "occurrence_end", nullable = false)
    private Instant occurrenceEnd;

    /**
     * Le motif choisi par le client parmi les siens. Opaque : le serveur ne le
     * lit pas, il le rend tel qu'il l'a reçu.
     *
     * <p><b>Volontairement pas une énumération</b>, à rebours de
     * {@link org.program.pair.domain.recap.SlotVibe} qui refuse toute valeur hors
     * vocabulaire. Le critère qui sépare les deux : le serveur <i>lit</i> les
     * ambiances — il les agrège pour en tirer les trois dominantes, et une valeur
     * parasite y fausserait un calcul sans que rien ne le signale. Il ne lit
     * jamais le motif. L'énumérer ici aurait fait dépendre l'ajout d'un
     * quatorzième motif d'un déploiement serveur, pour ne rien protéger.
     *
     * <p>La forme, elle, est contrainte — voir {@code AfficheService.parseMotif} :
     * la valeur ressort chez des tiers, et une clé n'a besoin ni d'espaces ni de
     * ponctuation.
     */
    @Column(name = "motif", nullable = false, length = 40)
    private String motif;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 20)
    @Builder.Default
    private AfficheAudience audience = AfficheAudience.NOBODY;

    /**
     * Quand cette affiche est devenue visible sous son audience actuelle.
     *
     * <p>Ce n'est pas « quand la ligne a été créée » : elle est rafraîchie à
     * chaque <b>ouverture</b> de l'audience, et à elle seule. C'est cette date
     * que {@code GET /api/affiches/updates} compare à {@code since}, donc elle
     * qui allume l'anneau sur l'avatar. Un motif corrigé ne rallume rien ; une
     * affiche passée de {@code NOBODY} à {@code SUBSCRIBERS} si.
     */
    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    /**
     * Les horodatages sont {@code NOT NULL} en base : on les pose ici plutôt que
     * de compter sur le {@code DEFAULT NOW()} du schéma, qu'un INSERT explicite
     * court-circuite. Même raison que sur {@code SlotRecap}.
     */
    @PrePersist
    void stampCreation() {
        Instant now = Instant.now();
        if (createdAt == null) {
            this.createdAt = now;
        }
        if (updatedAt == null) {
            this.updatedAt = now;
        }
        if (publishedAt == null) {
            this.publishedAt = now;
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
