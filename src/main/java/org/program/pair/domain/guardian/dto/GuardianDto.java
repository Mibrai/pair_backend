package org.program.pair.domain.guardian.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.domain.guardian.Guardian;
import org.program.pair.domain.guardian.GuardianRole;

import java.time.Instant;
import java.util.UUID;

/**
 * Un contact d'urgence tel que son parrain le voit.
 *
 * <p>Rendu à l'owner et à lui seul : les coordonnées qu'il a lui-même saisies lui
 * reviennent, ce qui ne divulgue rien qu'il ne connaisse déjà. Pour un contact
 * membre, on ne rend qu'un nom d'affichage — jamais son e-mail ni son téléphone,
 * que l'owner n'a pas à obtenir en le désignant.
 *
 * <p>{@code consentToken} n'y figure pas : c'est le secret du lien envoyé au
 * contact, il n'a rien à faire dans la liste que consulte l'owner.
 *
 * <p><b>Ce DTO n'est jamais servi au contact lui-même</b>, et {@code role} en
 * dépend. Le flux public de consentement passe par une projection dédiée — nom du
 * parrain et état, rien d'autre — et non par ce record. Si un écran devait un jour
 * montrer quelque chose au contact, il lui faudrait sa propre projection : savoir
 * qu'il est principal plutôt que secours est une information de plus, sans usage
 * pour lui, et sur laquelle il n'a rien à dire.
 */
@Schema(description = "Un contact d'urgence désigné par l'appelant.")
public record GuardianDto(

    UUID id,

    @Schema(description = "MEMBER si le contact a un compte meetDo, EXTERNAL sinon.")
    String type,

    @Schema(description = "Nom d'affichage du contact.")
    String name,

    @Schema(description = "Téléphone, pour un contact externe uniquement. Null pour un membre.")
    String phone,

    @Schema(description = "E-mail, pour un contact externe uniquement. Null pour un membre.")
    String email,

    @Schema(description = "PENDING tant qu'il n'a pas répondu, ACCEPTED s'il a accepté, "
        + "REFUSED s'il a refusé. Seul un contact ACCEPTED peut armer une veille.")
    ConsentState consentState,

    @Schema(description = "Quand la demande de consentement lui a été envoyée. Null si pas encore invité.")
    Instant invitedAt,

    @Schema(description = "Quand il a répondu. Null tant qu'il est PENDING.")
    Instant respondedAt,

    @Schema(description = "PRIMARY, BACKUP ou NONE — lequel de ses contacts l'appelant veut "
        + "prévenir en premier. Un réglage de l'appelant, jamais une information sur le "
        + "contact : le contact ne l'apprend pas, et ce champ ne figure dans aucune vue qui "
        + "lui soit servie. Au plus un PRIMARY et un BACKUP par personne, tenus par la base.")
    GuardianRole role,

    Instant createdAt
) {

    /**
     * @param displayName pour un contact membre, son nom d'affichage résolu ; pour
     *                    un contact externe, ignoré au profit du nom saisi.
     */
    public static GuardianDto from(Guardian g, String displayName) {
        boolean member = g.isMember();
        return new GuardianDto(
            g.getId(),
            member ? "MEMBER" : "EXTERNAL",
            member ? displayName : g.getName(),
            member ? null : g.getPhone(),
            member ? null : g.getEmail(),
            g.getConsentState(),
            g.getInvitedAt(),
            g.getRespondedAt(),
            GuardianRole.ofNullable(g.getRole()),
            g.getCreatedAt());
    }
}
