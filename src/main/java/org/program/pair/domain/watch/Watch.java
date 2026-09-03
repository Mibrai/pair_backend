package org.program.pair.domain.watch;

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
 * Une veille retour : « si je ne confirme pas mon retour à temps, prévenez ce
 * proche ».
 *
 * <p><b>{@code deadlineAt} est figé à l'armement, et n'est plus jamais dérivé du
 * créneau.</b> C'est le point de conception qui gouverne cette entité. Un créneau
 * récurrent voit son {@code starts_at} réécrit toutes les dix minutes par le
 * rollover ; une échéance recalculée à chaque lecture fuirait donc devant elle, et
 * le proche lirait la date de la semaine suivante. L'échéance est donc une colonne,
 * posée une fois — par défaut la fin du créneau plus une heure — et déplacée
 * ensuite seulement par les gestes qui le disent (un snooze, une interruption),
 * jamais par le passage du temps sur le créneau. C'est la même leçon que
 * {@code SlotSafetyShare} a apprise avant celle-ci.
 *
 * <p><b>Le serveur tient l'horloge, l'entité en porte l'état.</b> {@code state} et
 * {@code remindersSent} ne sont pas de l'affichage : ce sont les variables sur
 * lesquelles les minuteurs du module s'appuieront pour savoir quoi envoyer et
 * quand. L'application n'en planifie aucun.
 *
 * <p>{@code userId}, {@code guardianId}, {@code backupGuardianId} sont des UUID
 * nus, comme partout dans ce module : les chemins chauds n'ont besoin d'aucune
 * donnée jointe, et le contact se relit par son identifiant quand une réponse le
 * demande.
 */
@Entity
@Table(name = "watches")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Watch {

    /**
     * Le délai au bout duquel une arrivée déclarée se valide toute seule.
     *
     * <p><b>C'est le garde-fou sans lequel la validation par l'hôte ne serait pas
     * livrable</b>, et la raison est celle que nous opposions nous-mêmes au code de
     * séance le 02/09 : un geste détenu par un tiers fait dépendre de lui la
     * naissance du code de retour, et en fait un point de pression. Passé ce délai,
     * l'arrivée est validée sans que personne n'ait rien touché. L'hôte gagne du
     * temps sur la validation ; il n'a jamais de pouvoir sur elle.
     *
     * <p>Compté depuis {@code arrivalClaimedAt} — le geste de la personne — et non
     * depuis le début de la séance, qu'un hôte peut modifier.
     */
    public static final java.time.Duration DELAI_VALIDATION_AUTO = java.time.Duration.ofMinutes(15);

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "schedule_id", nullable = false)
    private UUID scheduleId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 12)
    @Builder.Default
    private WatchState state = WatchState.ARMED;

    @Column(name = "armed_at", nullable = false)
    @Builder.Default
    private Instant armedAt = Instant.now();

    /**
     * Quand la personne a <b>déclaré</b> son arrivée. Null tant qu'elle ne l'a pas
     * fait.
     *
     * <p>Un champ et non un état, à la demande du client : {@code WatchState.parse}
     * rend {@code ARMED} sur tout état inconnu, donc un état neuf ferait retomber
     * les applications anciennes sur « en attente d'arrivée » — faux dès qu'une
     * déclaration existe. Un champ inconnu, lui, est ignoré sans dommage.
     *
     * <p><b>Ce champ suspend la boucle aller.</b> Tant qu'il est nul, les demandes
     * « tu y es ? » partent et la non-arrivée se prononce à T+45 ; dès qu'il est
     * posé, {@code WatchOutboundJob} ne fait plus qu'attendre la validation. Sans
     * cela, quelqu'un qui déclare son arrivée à T+40 serait classé perdu en chemin
     * cinq minutes plus tard — et sa veille, terminale, ne surveillerait plus rien.
     */
    @Column(name = "arrival_claimed_at")
    private Instant arrivalClaimedAt;

    /** Null tant que l'arrivée n'a pas été validée — par l'hôte, ou par le délai. */
    @Column(name = "arrival_confirmed_at")
    private Instant arrivalConfirmedAt;

    @Column(name = "interrupted_at")
    private Instant interruptedAt;

    /** L'échéance, figée à l'armement. Voir la note de classe. */
    @Column(name = "deadline_at", nullable = false)
    private Instant deadlineAt;

    /** 0 à 3 rappels de retour envoyés. */
    @Column(name = "reminders_sent", nullable = false)
    @Builder.Default
    private int remindersSent = 0;

    /**
     * Le début de l'occurrence, figé à l'armement — la base de la boucle aller.
     * Figé pour la même raison que {@code deadlineAt} : le rollover d'un créneau
     * récurrent réécrit {@code starts_at}, et une base recalculée ferait fuir les
     * demandes « tu y es ? ».
     */
    @Column(name = "occurrence_starts_at")
    private Instant occurrenceStartsAt;

    /** La base des demandes d'arrivée. Décalée de 15 min à chaque « je suis en chemin ». */
    @Column(name = "outbound_base_at")
    private Instant outboundBaseAt;

    /** 0 à 3 demandes « tu y es ? » envoyées. */
    @Column(name = "arrival_prompts_sent", nullable = false)
    @Builder.Default
    private int arrivalPromptsSent = 0;

    /**
     * Le contact principal — un contact accepté de l'utilisateur, ou <b>rien</b>.
     *
     * <p>Nul quand la veille a été armée sans contact. Une telle veille relance,
     * journalise et porte la validation de présence, mais <b>n'envoie rien</b> :
     * elle se referme en {@link WatchState#NO_CONTACT} à l'échéance. Tout code qui
     * déréférence ce champ doit donc le tester d'abord — c'est la contrepartie du
     * champ facultatif, et elle vaut pour chaque chemin d'alerte.
     */
    @Column(name = "guardian_id")
    private UUID guardianId;

    /** Vrai quand la veille n'a personne à prévenir : rien ne sortira jamais d'elle. */
    public boolean sansContact() {
        return guardianId == null;
    }

    /**
     * Quand l'arrivée se validera d'elle-même — nul quand il n'y a rien à attendre.
     *
     * <p>Rendu au client pour qu'il puisse écrire l'heure <b>avant</b> le geste :
     * « sans réponse de ton hôte, ta présence sera validée à 19:57 ». C'est son
     * heure à lui qui doit s'afficher, pas une addition faite sur l'appareil — même
     * raison que {@code deadlineAt}, et la seule façon que les deux côtés parlent
     * du même instant.
     */
    public Instant getArrivalAutoConfirmAt() {
        if (arrivalClaimedAt == null || arrivalConfirmedAt != null || !estActive()) {
            return null;
        }
        return arrivalClaimedAt.plus(DELAI_VALIDATION_AUTO);
    }

    /** Le contact de secours, s'il y en a un. */
    @Column(name = "backup_guardian_id")
    private UUID backupGuardianId;

    @Column(name = "closed_at")
    private Instant closedAt;

    /**
     * Le jeton de la page de statut publique. Nul jusqu'à l'escalade : le lien
     * d'urgence naît avec l'alerte, pas à l'armement, pour que la veille ne
     * devienne pas un mouchard qui montrerait au contact chaque soirée.
     */
    @Column(name = "public_token", length = 22)
    private String publicToken;

    /**
     * Première ouverture de la page publique — le « le principal a ouvert » qui
     * décide si l'on prévient le contact de secours.
     */
    @Column(name = "public_viewed_at")
    private Instant publicViewedAt;

    /** Révocation du lien public par le propriétaire, avant expiration naturelle. */
    @Column(name = "public_token_revoked_at")
    private Instant publicTokenRevokedAt;

    /** Quand un contact a cliqué « j'ai vu » sur la page publique. */
    @Column(name = "guardian_seen_at")
    private Instant guardianSeenAt;

    /** Quand un contact a cliqué « je l'ai eue au téléphone ». */
    @Column(name = "guardian_called_at")
    private Instant guardianCalledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public boolean estActive() {
        return state.estActive();
    }
}
