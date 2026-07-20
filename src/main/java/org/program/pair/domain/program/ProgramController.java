package org.program.pair.domain.program;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.media.ImageProcessor;
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
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/programs")
@RequiredArgsConstructor
@Validated
public class ProgramController {

    private final ProgramService programService;
    private final ReportService reportService;
    private final StorageService storageService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;

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

    @GetMapping("/new")
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public void getNewProgram() {
        throw new ValidationException("Utilisez POST /api/programs pour créer un programme.");
    }

    @GetMapping("/{programId}")
    public ProgramDto getProgram(
            @PathVariable UUID programId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return programService.getProgram(programId, principal.getId());
    }

    @PutMapping("/{programId}")
    public ProgramDto updateProgram(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody UpdateProgramRequest request) {
        return programService.updateProgram(principal.getId(), programId, request);
    }

    @PatchMapping("/{programId}")
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

    @PostMapping("/{programId}/image/upload")
    public ProgramDto uploadProgramImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @RequestParam("file") MultipartFile file) throws IOException {
        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(), processedImage);
        String filename = storageService.store(processedFile, programId, MediaType.PROGRAM_IMAGE);
        return programService.updateProgramImage(
            principal.getId(), programId, "/api/media/files/" + filename);
    }

    @DeleteMapping("/{programId}/image")
    public ProgramDto deleteProgramImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId) throws IOException {
        String previousImageUrl = programService.removeProgramImage(principal.getId(), programId);
        deleteStoredFile(previousImageUrl);
        return programService.getProgram(programId, principal.getId());
    }

    private void deleteStoredFile(String url) throws IOException {
        String prefix = "/api/media/files/";
        if (url != null && url.startsWith(prefix)) {
            storageService.delete(url.substring(prefix.length()));
        }
    }

    @PostMapping("/{programId}/schedules")
    @ResponseStatus(HttpStatus.CREATED)
    public ScheduleDto addSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @Valid @RequestBody CreateScheduleRequest request) {
        return programService.addSchedule(principal.getId(), programId, request);
    }

    @PutMapping("/{programId}/schedules/{scheduleId}")
    public ScheduleDto updateSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody UpdateScheduleRequest request) {
        return programService.updateSchedule(principal.getId(), scheduleId, request);
    }

    @DeleteMapping("/{programId}/schedules/{scheduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSchedule(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable UUID programId,
            @PathVariable UUID scheduleId) {
        programService.deleteSchedule(principal.getId(), scheduleId);
    }

    @PostMapping("/{programId}/report")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> reportProgram(
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
        return Map.of("message", "Programme signalé");
    }

    private static class ProcessedMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final InputStream inputStream;

        ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }

        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return "image/jpeg"; }
        @Override public boolean isEmpty() { return false; }
        @Override public long getSize() { return 0; }
        @Override public byte[] getBytes() throws IOException { return inputStream.readAllBytes(); }
        @Override public InputStream getInputStream() { return inputStream; }
        @Override public void transferTo(java.io.File dest) throws IOException { throw new UnsupportedOperationException(); }
    }
}
