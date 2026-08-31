package org.program.pair.domain.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.program.pair.domain.report.Report;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportPublicState;

import java.time.Instant;
import java.util.UUID;

/**
 * Un signalement vu par la personne qui l'a émis — et rien d'autre.
 *
 * <p><b>Ce type existe pour refermer une fuite, pas pour habiller une réponse.</b>
 * {@code GET /api/reports/me} rendait {@code Page<Report>}, c'est-à-dire l'entité
 * brute annotée {@code @Data} : tous ses champs partaient au client, dont
 * {@code resolutionNotes} — les notes internes du modérateur, en {@code TEXT} —
 * et {@code reviewedBy}, l'identifiant du membre de l'équipe qui a tranché. Un
 * signalant lisait donc les notes de modération le concernant et savait qui
 * l'avait traité.
 *
 * <p>Le risque n'est pas théorique dans les deux sens : la note est rédigée pour
 * l'équipe et peut décrire ce qui a été constaté sur un tiers, et l'identité du
 * modérateur est précisément ce qu'on ne veut pas exposer à quelqu'un que sa
 * décision mécontente.
 *
 * <p>Comme {@code SafetyShareView}, la liste est <b>fermée</b> : un champ ajouté
 * ici est un champ que quelqu'un a décidé de publier. Ce qui n'y figure pas, et
 * ne doit pas y entrer : {@code resolutionNotes}, {@code reviewedBy},
 * {@code reviewedAt} (qui date la décision d'un modérateur nommable par
 * recoupement), {@code reporterId} (l'appelant, donc redondant) et
 * {@code reportedEntityId} — l'identifiant de la cible, que l'app n'affiche pas
 * et qui n'a pas à voyager.
 *
 * <p>{@code reason} et {@code description} n'y sont pas non plus : ce sont les
 * mots de l'appelant, qu'il a déjà, et les renvoyer n'ajoute rien à un écran de
 * suivi qui répond à « où en est ma demande ».
 */
@Schema(description = "Un signalement émis par l'appelant, tel que l'écran de suivi l'affiche.")
public record ReportSummaryDto(

    @Schema(description = "Identifiant du signalement.")
    UUID id,

    @Schema(description = "Ce qui a été signalé : un compte, un programme, un message ou un avis.")
    ReportEntityType targetType,

    @Schema(description = "Où en est la demande. RECEIVED tant que personne ne l'a "
        + "regardée, RESOLVED une fois traitée, DISMISSED si elle a été classée sans "
        + "suite. Ce qu'une décision a entraîné pour la personne visée ne se dit pas.")
    ReportPublicState state,

    @Schema(description = "Quand le signalement a été émis.")
    Instant createdAt,

    @Schema(description = "Dernier mouvement sur ce signalement. Égal à createdAt "
        + "tant que rien n'a bougé.")
    Instant updatedAt
) {

    public static ReportSummaryDto from(Report report) {
        return new ReportSummaryDto(
            report.getId(),
            report.getReportedEntityType(),
            ReportPublicState.of(report.getStatus()),
            report.getCreatedAt(),
            report.getUpdatedAt());
    }
}
