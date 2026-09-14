package org.program.pair.domain.program;

import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.media.ProcessedMultipartFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.media.ImageProcessor;
import org.program.pair.domain.media.MediaFileService;
import org.program.pair.domain.media.MediaType;
import org.program.pair.domain.media.MediaValidator;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.dto.*;
import org.program.pair.domain.report.ReportEntityType;
import org.program.pair.domain.report.ReportService;
import org.program.pair.domain.report.dto.CreateReportRequest;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
@Validated
public class ProgramController {

    private final ProgramService programService;
    private final ReportService reportService;
    private final StorageService storageService;
    private final MediaFileService mediaFileService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;
    private final org.program.pair.domain.publicslot.PublicProgramService publicProgramService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramDto createProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody CreateProgramRequest request) {
        return programService.createProgram(principal.getId(), request);
    }

    @GetMapping
    public List<ProgramDto> getMyPrograms(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(name = "radius_km", required = false) Double radiusKm) {
        if (lat != null || lng != null) {
            return programService.getNearbyPrograms(principal.getId(), lat, lng, radiusKm);
        }
        return programService.getMyPrograms(principal.getId());
    }

    @PostMapping("/{programId}/duplicate")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramDto duplicateProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody(required = false) DuplicateProgramRequest request) {
        String title = request != null ? request.title() : null;
        return programService.duplicateProgram(principal.getId(), programId, title);
    }

    @GetMapping("/new")
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void getNewProgram() {
        throw new ValidationException(ErrorCode.VALIDATION_ERROR, "REFUS_CREER_PROGRAMME_AILLEURS", "Utilisez POST /api/programs pour créer un programme.");
    }

    @GetMapping("/{programId}")
    public ProgramDto getProgram(
            @PathVariable UUID programId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return programService.getProgram(programId, principal.getId());
    }

    @PutMapping("/{programId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Passage à ACTIVE par un compte à l'adresse non vérifiée (EMAIL_NOT_VERIFIED).")
    public ProgramDto updateProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody UpdateProgramRequest request) {
        return programService.updateProgram(principal.getId(), programId, request);
    }

    @PatchMapping("/{programId}")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Passage à ACTIVE par un compte à l'adresse non vérifiée (EMAIL_NOT_VERIFIED).")
    public ProgramDto patchProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody UpdateProgramRequest request) {
        return programService.updateProgram(principal.getId(), programId, request);
    }

    @DeleteMapping("/{programId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        programService.deleteProgram(principal.getId(), programId);
    }

    /**
     * Dépose la couverture d'un programme.
     *
     * <p><b>Le déposant est l'appelant, pas le programme.</b> Cette ligne
     * passait {@code programId} à {@code store(...)} — un identifiant de
     * programme là où le stockage attendait une personne. Le fichier
     * n'appartenait donc à personne, et aucune suppression ne pouvait être
     * autorisée : c'est la moitié « programme » de la racine du défaut P-BS-01.
     */
    @PostMapping("/{programId}/image/upload")
    public ProgramDto uploadProgramImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @RequestParam("file") MultipartFile file) throws IOException {
        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(), processedImage);
        String filename = storageService.store(processedFile, principal.getId(), MediaType.PROGRAM_IMAGE);
        return programService.updateProgramImage(
            principal.getId(), programId, MediaFileService.URL_PREFIX + filename);
    }

    /**
     * Retire la couverture, et efface le fichier <b>si l'appelant l'a déposé</b>.
     *
     * <p>{@code removeProgramImage} garantit déjà que l'appelant est l'hôte du
     * programme ; ce que la seconde ligne ajoute, c'est de ne pas détruire les
     * octets d'un tiers. Le cas existe : les couvertures déposées avant V109
     * n'ont aucun déposant connaissable (l'ancien code enregistrait un
     * {@code programId}), et les couvertures de seed sont des URL externes.
     * Dans ces deux cas le programme oublie bien son image, et les octets
     * restent — un fichier sans ligne n'est supprimable par personne.
     */
    @DeleteMapping("/{programId}/image")
    public ProgramDto deleteProgramImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        String previousImageUrl = programService.removeProgramImage(principal.getId(), programId);
        mediaFileService.supprimerUrlSiAuteur(previousImageUrl, principal.getId());
        return programService.getProgram(programId, principal.getId());
    }

    @PostMapping("/{programId}/schedules")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
        description = "Créneau posé hors brouillon par un compte à l'adresse non vérifiée (EMAIL_NOT_VERIFIED) : rien n'est créé. Dans un programme DRAFT, le créneau est accepté.")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto addSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody CreateScheduleRequest request) {
        return programService.addSchedule(principal.getId(), programId, request);
    }

    @PutMapping("/{programId}/schedules/{scheduleId}")
    @io.swagger.v3.oas.annotations.Operation(summary = "Modifier un créneau (mise à jour partielle)",
        description = "FUSION, pas remplacement : un champ absent ou null reste ce qu'il était. "
            + "Pour retirer une valeur : chaîne vide pour primaryLanguage et level, liste vide pour "
            + "accessibilityTags ; endsAt ne se retire pas. Passer placeType à ONLINE efface la "
            + "position. addressPublic n'est enregistrée que pour un lieu PUBLIC ou quand "
            + "showExactAddress vaut true — la valeur envoyée, sinon celle déjà en place. "
            + "isPubliclyShareable est ignoré ici : PATCH /api/slots/{id}/shareable. "
            + "Une capacité sous le nombre d'inscrits ne désinscrit personne : le créneau passe FULL. "
            + "Aucun contrôle de chevauchement d'agenda : jamais de 409 SCHEDULE_CONFLICT sur cette route. "
            + "Les inscrits reçoivent SCHEDULE_CHANGED seulement si l'heure (startsAt, endsAt) ou le "
            + "lieu (placeName, placeType, position, adresse diffusable) a changé ; une requête "
            + "sans changement effectif ne notifie personne.")
    public ScheduleDto updateSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return programService.updateSchedule(principal.getId(), scheduleId, request);
    }

    @DeleteMapping("/{programId}/schedules/{scheduleId}")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Supprimer un créneau — ou l'annuler s'il concerne quelqu'un.",
        description = "Supprime le créneau s'il ne concerne personne (outcome DELETED). "
            + "Sinon, l'annule par le même chemin que POST /api/slots/{id}/cancel et "
            + "prévient une fois chaque personne concernée (outcome CANCELLED). "
            + "Un scheduleId qui n'appartient pas à programId rend 404 sans rien modifier.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200")
    public ScheduleDeletionResult deleteSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID scheduleId) {
        return programService.deleteSchedule(principal.getId(), programId, scheduleId);
    }

    @PostMapping("/{programId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramReportResult reportProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody ProgramReportRequest request) {
        CreateReportRequest reportRequest = CreateReportRequest.builder()
            .reportedEntityType(ReportEntityType.PROGRAM)
            .reportedEntityId(programId)
            .reason(request.reason())
            .description(request.description())
            .build();
        reportService.createReport(principal.getId(), reportRequest);
        return new ProgramReportResult("Programme signalé");
    }

    /**
     * L'adresse publique de ce programme, créée à la première demande.
     *
     * <p>Réservée à l'organisateur, là où celle d'un créneau s'ouvre à tous ses
     * participants : partager une séance qu'on a rejointe est un geste ordinaire,
     * mais un programme n'appartient qu'à son auteur, et c'est lui qui décide
     * s'il existe sur le web ouvert.
     *
     * <p>{@code 404} pour quiconque d'autre, jamais {@code 403}.
     */
    @GetMapping("/{programId}/share-link")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "L'adresse publique de ce programme.",
        description = "Créée à la première demande — un programme que personne n'a jamais "
            + "partagé n'a pas besoin d'adresse publique. pageUrl est à lire tel quel, "
            + "sans le recomposer.")
    public org.program.pair.domain.publicslot.dto.PublicShareLinkDto shareLink(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) {
        return publicProgramService.shareLink(principal.getId(), programId);
    }

    @PatchMapping("/{programId}/shareable")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Ouvre ou ferme le partage public de ce programme.",
        description = "Le jeton n'est jamais effacé ni régénéré : refermer suffit à ce que "
            + "le lien ne mène plus nulle part, et rouvrir rend valides les liens déjà "
            + "partagés. 404 pour qui n'est pas l'organisateur.")
    public org.program.pair.domain.publicslot.dto.PublicShareLinkDto setShareable(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody
                org.program.pair.domain.publicslot.dto.SetShareableRequest request) {
        return publicProgramService.setShareable(
            principal.getId(), programId, request.isPubliclyShareable());
    }
}
