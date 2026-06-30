package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.dto.*;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class ProgramService {

    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramMediaRepository programMediaRepository;
    private final org.program.pair.repository.ReviewRepository reviewRepository;
    private final HtmlSanitizer sanitizer;
    private final GeometryFactory geometryFactory = new GeometryFactory(
        new PrecisionModel(), 4326);

    public ProgramDto createProgram(UUID userId, CreateProgramRequest request) {
        UserActivity ua = userActivityRepository
            .findByIdAndUserId(request.userActivityId(), userId)
            .orElseThrow(() -> new ForbiddenException("Activité introuvable."));

        Program program = new Program();
        program.setUserActivity(ua);
        program.setTitle(sanitizer.sanitize(request.title()).strip());
        program.setDescription(sanitizer.sanitize(request.description()));
        program.setStatus(ProgramStatus.DRAFT);
        program.setIsPublic(request.isPublic() != null ? request.isPublic() : true);

        return toDto(programRepository.save(program), userId);
    }

    public ProgramDto updateProgram(UUID userId, UUID programId,
                                     UpdateProgramRequest request) {
        Program program = findProgramOwnedBy(programId, userId);

        if (request.title() != null)
            program.setTitle(sanitizer.sanitize(request.title()).strip());
        if (request.description() != null)
            program.setDescription(sanitizer.sanitize(request.description()));
        if (request.status() != null) {
            program.setStatus(request.status());
            if (request.status() == ProgramStatus.ARCHIVED) {
                program.setArchivedAt(Instant.now());
            }
        }
        if (request.isPublic() != null) program.setIsPublic(request.isPublic());

        return toDto(programRepository.save(program), userId);
    }

    public void deleteProgram(UUID userId, UUID programId) {
        Program program = findProgramOwnedBy(programId, userId);
        program.setStatus(ProgramStatus.ARCHIVED);
        program.setArchivedAt(Instant.now());
        programRepository.save(program);
    }

    @Transactional(readOnly = true)
    public ProgramDto getProgram(UUID programId, UUID requesterId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));

        // Check visibility
        boolean isOwner = program.getUserActivity().getUser().getId().equals(requesterId);
        if (!program.getIsPublic() && !isOwner) {
            throw new ForbiddenException("Ce programme est privé.");
        }

        return toDto(program, requesterId);
    }

    @Transactional(readOnly = true)
    public List<ProgramDto> getMyPrograms(UUID userId) {
        return programRepository.findByUserActivityUserIdAndStatusNot(
            userId, ProgramStatus.ARCHIVED)
            .stream()
            .map(p -> toDto(p, userId))
            .collect(Collectors.toList());
    }

    public ScheduleDto addSchedule(UUID userId, UUID programId,
                                    CreateScheduleRequest request) {
        Program program = findProgramOwnedBy(programId, userId);

        if (request.placeType() == PlaceType.PUBLIC && request.addressPublic() == null) {
            throw new ValidationException("L'adresse est obligatoire pour un lieu public.");
        }

        Schedule schedule = new Schedule();
        schedule.setProgram(program);
        schedule.setPlaceName(sanitizer.sanitize(request.placeName()).strip());
        schedule.setPlaceType(request.placeType());
        schedule.setLocation(geometryFactory.createPoint(
            new Coordinate(request.lng(), request.lat())));

        if (request.placeType() == PlaceType.PUBLIC) {
            schedule.setAddressPublic(request.addressPublic());
        } else if (Boolean.TRUE.equals(request.showExactAddress())) {
            schedule.setAddressPublic(request.addressPublic());
            schedule.setShowExactAddress(true);
        }

        schedule.setStartsAt(request.startsAt());
        schedule.setEndsAt(request.endsAt());
        schedule.setRecurrenceRule(request.recurrenceRule());
        schedule.setMaxParticipants(request.maxParticipants());

        return toScheduleDto(scheduleRepository.save(schedule), userId);
    }

    public ScheduleDto updateSchedule(UUID userId, UUID scheduleId,
                                       UpdateScheduleRequest request) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        UUID ownerId = schedule.getProgram().getUserActivity().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez pas modifier ce créneau.");
        }

        if (request.placeName() != null)
            schedule.setPlaceName(sanitizer.sanitize(request.placeName()).strip());

        if (request.placeType() != null)
            schedule.setPlaceType(request.placeType());

        if (request.lat() != null && request.lng() != null)
            schedule.setLocation(geometryFactory.createPoint(
                new Coordinate(request.lng(), request.lat())));

        PlaceType effectivePlaceType = schedule.getPlaceType();
        if (request.addressPublic() != null) {
            if (effectivePlaceType == PlaceType.PUBLIC
                    || Boolean.TRUE.equals(request.showExactAddress())) {
                schedule.setAddressPublic(request.addressPublic());
            }
        }
        if (request.showExactAddress() != null)
            schedule.setShowExactAddress(request.showExactAddress());

        if (request.startsAt() != null)   schedule.setStartsAt(request.startsAt());
        if (request.endsAt() != null)     schedule.setEndsAt(request.endsAt());
        if (request.recurrenceRule() != null) schedule.setRecurrenceRule(request.recurrenceRule());
        if (request.maxParticipants() != null) schedule.setMaxParticipants(request.maxParticipants());

        return toScheduleDto(scheduleRepository.save(schedule), userId);
    }

    public void deleteSchedule(UUID userId, UUID scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        UUID ownerId = schedule.getProgram().getUserActivity().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez pas supprimer ce créneau.");
        }

        scheduleRepository.delete(schedule);
    }

    private Program findProgramOwnedBy(UUID programId, UUID userId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));

        UUID ownerId = program.getUserActivity().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException("Vous n'êtes pas propriétaire de ce programme.");
        }

        return program;
    }

    private ProgramDto toDto(Program p, UUID requesterId) {
        List<ScheduleDto> schedules = scheduleRepository
            .findByProgramId(p.getId())
            .stream()
            .map(s -> toScheduleDto(s, requesterId))
            .collect(Collectors.toList());

        List<ProgramMediaDto> media = programMediaRepository
            .findByProgramIdOrderBySortOrder(p.getId())
            .stream()
            .map(m -> new ProgramMediaDto(
                m.getId(),
                m.getUrl(),
                m.getMediaType().name(),
                m.getSortOrder()
            ))
            .collect(Collectors.toList());

        Double avgDouble = reviewRepository.findAverageRatingByProgramId(p.getId());
        Float averageScore = avgDouble != null ? avgDouble.floatValue() : null;
        Integer reviewCount = (int) reviewRepository.countByProgramId(p.getId());

        return new ProgramDto(
            p.getId(),
            p.getTitle(),
            p.getDescription(),
            p.getStatus().name(),
            p.getIsPublic(),
            p.getCreatedAt(),
            p.getUpdatedAt(),
            schedules,
            media,
            averageScore,
            reviewCount
        );
    }

    private ScheduleDto toScheduleDto(Schedule s, UUID requesterId) {
        boolean isOwner = s.getProgram().getUserActivity().getUser().getId().equals(requesterId);

        Double lat = null;
        Double lng = null;
        String displayAddress = null;

        if (s.getPlaceType() == PlaceType.PUBLIC) {
            lat = s.getLocation().getY();
            lng = s.getLocation().getX();
            displayAddress = s.getAddressPublic();
        } else if (Boolean.TRUE.equals(s.getShowExactAddress()) || isOwner) {
            lat = s.getLocation().getY();
            lng = s.getLocation().getX();
            displayAddress = s.getAddressPublic();
        }

        return new ScheduleDto(
            s.getId(),
            s.getPlaceName(),
            s.getPlaceType().name(),
            lat,
            lng,
            displayAddress,
            s.getStartsAt(),
            s.getEndsAt(),
            s.getRecurrenceRule(),
            s.getMaxParticipants()
        );
    }
}
