package org.program.pair.domain.progression;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.progression.dto.*;
import org.program.pair.domain.program.Program;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ProgressionRepository;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProgressionService {

    private final ProgressionRepository progressionRepository;
    private final ProgramRepository programRepository;
    private final UserRepository userRepository;

    @Transactional
    public ProgressionDto createProgression(UUID userId, CreateProgressionRequest request) {
        Program program = programRepository.findById(request.programId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Program not found"));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        Progression progression = Progression.builder()
            .program(program)
            .user(user)
            .title(request.title())
            .content(request.content())
            .metrics(request.metrics())
            .metricLabels(request.metricLabels())
            .isPublic(request.isPublic())
            .build();

        progression = progressionRepository.save(progression);
        return toDto(progression);
    }

    @Transactional
    public ProgressionDto updateProgression(UUID id, UUID userId, UpdateProgressionRequest request) {
        Progression progression = progressionRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Progression not found"));

        if (!progression.getUser().getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not authorized to update this progression");
        }

        if (request.title() != null) {
            progression.setTitle(request.title());
        }
        if (request.content() != null) {
            progression.setContent(request.content());
        }
        if (request.metrics() != null) {
            progression.setMetrics(request.metrics());
        }
        if (request.metricLabels() != null) {
            progression.setMetricLabels(request.metricLabels());
        }
        if (request.isPublic() != null) {
            progression.setIsPublic(request.isPublic());
        }

        progression = progressionRepository.save(progression);
        return toDto(progression);
    }

    @Transactional
    public void deleteProgression(UUID id, UUID userId) {
        if (!progressionRepository.existsByIdAndUserId(id, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Progression not found or not authorized");
        }
        progressionRepository.deleteById(id);
    }

    public ProgressionDto getProgression(UUID id, UUID requestingUserId) {
        Progression progression = progressionRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Progression not found"));

        // 404 et non 403 : un refus nommé confirmait l'existence d'une progression
        // privée à qui n'avait pas à la connaître (demande mobile badges TER, 14/09).
        if (!progression.getIsPublic() && !progression.getUser().getId().equals(requestingUserId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Progression not found");
        }

        return toDto(progression);
    }

    /** {@code pageable} arrive borné par le contrôleur ({@code Pages.borne}, P-BA-14). */
    public Page<ProgressionDto> getProgressionsByProgram(UUID programId, UUID requestingUserId, Pageable pageable) {

        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Program not found"));

        if (program.getUserActivity().getUser().getId().equals(requestingUserId)) {
            return progressionRepository.findByProgramIdOrderByCreatedAtDesc(programId, pageable)
                .map(this::toDto);
        } else {
            return progressionRepository.findByProgramIdAndIsPublicTrueOrderByCreatedAtDesc(programId, pageable)
                .map(this::toDto);
        }
    }

    /**
     * Les progressions d'une personne, <b>pour elle seule</b>.
     *
     * <p>Rendait à tout compte connecté toutes les progressions de n'importe qui,
     * privées comprises — titre, contenu, mesures, dates — sans filtre ni blocage
     * (relevé le 14/09, demande mobile badges TER). Seul {@code /my} l'appelle
     * désormais ; {@code /user/{userId}} rend 404 pour autrui.
     */
    public Page<ProgressionDto> getProgressionsByUser(UUID userId, Pageable pageable) {
        return progressionRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
            .map(this::toDto);
    }

    public ProgressionStatsDto getProgressionStats(UUID userId) {
        int total = progressionRepository.countByUserId(userId);
        int publicCount = progressionRepository.countByUserIdAndIsPublicTrue(userId);
        int privateCount = progressionRepository.countByUserIdAndIsPublicFalse(userId);

        Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        List<Progression> recentProgressions = progressionRepository
            .findByUserIdAndCreatedAtAfter(userId, thirtyDaysAgo);

        Map<String, Object> metricsAggregates = calculateMetricsAggregates(recentProgressions);

        return new ProgressionStatsDto(total, publicCount, privateCount, metricsAggregates);
    }

    private Map<String, Object> calculateMetricsAggregates(List<Progression> progressions) {
        Map<String, Object> aggregates = new HashMap<>();

        if (progressions.isEmpty()) {
            return aggregates;
        }

        Map<String, List<Float>> metricsByLabel = new HashMap<>();

        for (Progression p : progressions) {
            if (p.getMetrics() != null && p.getMetricLabels() != null) {
                for (int i = 0; i < Math.min(p.getMetrics().length, p.getMetricLabels().length); i++) {
                    String label = p.getMetricLabels()[i];
                    float value = p.getMetrics()[i];
                    metricsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(value);
                }
            }
        }

        for (Map.Entry<String, List<Float>> entry : metricsByLabel.entrySet()) {
            String label = entry.getKey();
            List<Float> values = entry.getValue();

            Map<String, Object> stats = new HashMap<>();
            stats.put("count", values.size());
            stats.put("sum", values.stream().mapToDouble(Float::doubleValue).sum());
            stats.put("avg", values.stream().mapToDouble(Float::doubleValue).average().orElse(0.0));
            stats.put("min", values.stream().mapToDouble(Float::doubleValue).min().orElse(0.0));
            stats.put("max", values.stream().mapToDouble(Float::doubleValue).max().orElse(0.0));

            aggregates.put(label, stats);
        }

        return aggregates;
    }

    private ProgressionDto toDto(Progression progression) {
        return new ProgressionDto(
            progression.getId(),
            progression.getProgram().getId(),
            progression.getProgram().getTitle(),
            progression.getUser().getId(),
            progression.getUser().getDisplayName(),
            progression.getTitle(),
            progression.getContent(),
            progression.getMetrics(),
            progression.getMetricLabels(),
            progression.getIsPublic(),
            progression.getCreatedAt(),
            progression.getUpdatedAt()
        );
    }
}
