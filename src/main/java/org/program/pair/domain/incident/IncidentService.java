package org.program.pair.domain.incident;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.incident.dto.CreateIncidentRequest;
import org.program.pair.domain.incident.dto.IncidentDto;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportReason;
import org.program.pair.domain.report.ReportService;
import org.program.pair.domain.report.dto.CreateReportRequest;
import org.program.pair.repository.IncidentRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Le registre des incidents de sécurité : les signaler, et relire les siens.
 *
 * <p><b>Séparé de la modération, sauf pour une cible.</b> Un incident reste dans
 * ce registre — c'est le journal de sécurité de la personne. Seule la cible
 * {@link IncidentTarget#PERSON} bascule <i>en plus</i> dans le flux de
 * signalement existant : un comportement inapproprié doit atteindre la
 * modération, là où « perdu en chemin » ou « lieu mal éclairé » n'ont rien à y
 * faire, et y mettraient la victime dans la colonne des signalés.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class IncidentService {

    private final IncidentRepository incidentRepository;
    private final ReportService reportService;

    public IncidentDto create(UUID userId, CreateIncidentRequest req) {
        // Une cible PERSON bascule dans la modération : on crée d'abord le
        // signalement, qui valide l'existence de la cible, le non-soi et le
        // non-doublon. S'il échoue, l'incident n'est pas écrit non plus.
        if (req.target() == IncidentTarget.PERSON) {
            if (req.targetUserId() == null) {
                throw new BusinessException(ErrorCode.INCIDENT_PERSON_TARGET_REQUIRED,
                    "Un incident visant une personne doit désigner qui.");
            }
            if (req.note() == null || req.note().strip().length() < 10) {
                throw new BusinessException(ErrorCode.INCIDENT_NOTE_REQUIRED,
                    "Un incident visant une personne demande une description d'au moins 10 caractères.");
            }
            reportService.createReport(userId, CreateReportRequest.builder()
                .reportedEntityType(ReportEntityType.USER)
                .reportedEntityId(req.targetUserId())
                .reason(req.reason() == null ? ReportReason.OTHER : req.reason())
                .description(req.note().strip())
                .build());
        }

        Incident incident = incidentRepository.save(Incident.reported(
            userId, req.target(), req.scheduleId(),
            req.note() == null ? null : req.note().strip(),
            req.attachmentUrl()));

        return IncidentDto.from(incident);
    }

    @Transactional(readOnly = true)
    public List<IncidentDto> mine(UUID userId) {
        return incidentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(IncidentDto::from)
            .toList();
    }

    /**
     * « Retirer de mon journal » : supprime l'incident personnel de l'appelant.
     *
     * <p><b>Ce que ce geste ne touche pas, et pourquoi.</b> Un incident visant une
     * personne a engendré un signalement dans un registre séparé ({@code reports}),
     * qui n'est lié à cette ligne par aucune colonne. Supprimer l'incident laisse
     * donc le signalement en place : c'est voulu. Sinon, retirer son incident
     * effacerait la trace d'un lieu ou d'une personne qui concentre les incidents —
     * le signal même que la modération doit garder. Le retrait est personnel ;
     * l'agrégat de modération reste, et n'est plus rattaché nominativement à ce
     * journal une fois la ligne partie. « Retirer de mon journal » dit exactement
     * ce qu'il fait, et ne promet rien de plus.
     */
    public void delete(UUID userId, UUID incidentId) {
        Incident incident = incidentRepository.findByIdAndUserId(incidentId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Incident introuvable."));
        incidentRepository.delete(incident);
    }
}
