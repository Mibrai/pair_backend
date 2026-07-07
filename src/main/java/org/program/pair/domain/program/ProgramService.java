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
    private final ReviewRepository reviewRepository;
    private final UserProgramRepository userProgramRepository;
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
        program.setOrganizerName(ua.getUser().getDisplayName());
        program.setOrganizerAvatarUrl(ua.getUser().getAvatarUrl());

        applyOptionalFields(program, request.durationWeeks(), request.sessionsPerWeek(),
            request.sessionDurationMinutes(), request.preferredDays(), request.preferredTime(),
            request.maxParticipants(), request.privacy(), request.goals(),
            request.prerequisites(), request.locationType());

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

        applyOptionalFields(program, request.durationWeeks(), request.sessionsPerWeek(),
            request.sessionDurationMinutes(), request.preferredDays(), request.preferredTime(),
            request.maxParticipants(), request.privacy(), request.goals(),
            request.prerequisites(), request.locationType());

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

        if (request.placeType() != PlaceType.ONLINE) {
            schedule.setLocation(geometryFactory.createPoint(
                new Coordinate(request.lng(), request.lat())));
        }

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

        ScheduleDto dto = toScheduleDto(scheduleRepository.save(schedule), userId);
        refreshNextSessionAt(program);
        return dto;
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

        PlaceType effectivePlaceType = schedule.getPlaceType();

        if (request.lat() != null && request.lng() != null
                && effectivePlaceType != PlaceType.ONLINE) {
            schedule.setLocation(geometryFactory.createPoint(
                new Coordinate(request.lng(), request.lat())));
        }

        if (request.addressPublic() != null) {
            if (effectivePlaceType == PlaceType.PUBLIC
                    || Boolean.TRUE.equals(request.showExactAddress())) {
                schedule.setAddressPublic(request.addressPublic());
            }
        }
        if (request.showExactAddress() != null)
            schedule.setShowExactAddress(request.showExactAddress());

        if (request.startsAt() != null)       schedule.setStartsAt(request.startsAt());
        if (request.endsAt() != null)         schedule.setEndsAt(request.endsAt());
        if (request.recurrenceRule() != null) schedule.setRecurrenceRule(request.recurrenceRule());
        if (request.maxParticipants() != null) schedule.setMaxParticipants(request.maxParticipants());

        ScheduleDto dto = toScheduleDto(scheduleRepository.save(schedule), userId);
        refreshNextSessionAt(schedule.getProgram());
        return dto;
    }

    public void deleteSchedule(UUID userId, UUID scheduleId) {
        Schedule schedule = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        UUID ownerId = schedule.getProgram().getUserActivity().getUser().getId();
        if (!ownerId.equals(userId)) {
            throw new ForbiddenException("Vous ne pouvez pas supprimer ce créneau.");
        }

        Program prog = schedule.getProgram();
        scheduleRepository.delete(schedule);
        refreshNextSessionAt(prog);
    }

    private void applyOptionalFields(Program program,
                                      Integer durationWeeks, Integer sessionsPerWeek,
                                      Integer sessionDurationMinutes, int[] preferredDays,
                                      PreferredTime preferredTime, Integer maxParticipants,
                                      ProgramPrivacy privacy, String goals,
                                      String prerequisites, LocationType locationType) {
        if (durationWeeks != null)           program.setDurationWeeks(durationWeeks);
        if (sessionsPerWeek != null)         program.setSessionsPerWeek(sessionsPerWeek);
        if (sessionDurationMinutes != null)  program.setSessionDurationMinutes(sessionDurationMinutes);
        if (preferredDays != null)           program.setPreferredDays(preferredDays);
        if (preferredTime != null)           program.setPreferredTime(preferredTime);
        if (maxParticipants != null)         program.setMaxParticipants(maxParticipants);
        if (privacy != null)                 program.setPrivacy(privacy);
        if (goals != null)                   program.setGoals(sanitizer.sanitize(goals));
        if (prerequisites != null)           program.setPrerequisites(sanitizer.sanitize(prerequisites));
        if (locationType != null)            program.setLocationType(locationType);
    }

    private void refreshNextSessionAt(Program program) {
        Instant next = scheduleRepository.findByProgramId(program.getId()).stream()
            .map(Schedule::getStartsAt)
            .filter(t -> t != null && t.isAfter(Instant.now()))
            .min(Instant::compareTo)
            .orElse(null);
        program.setNextSessionAt(next);
        programRepository.save(program);
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
        Integer enrolledCount = (int) userProgramRepository.countActiveParticipantsByProgramId(p.getId());

        Instant nextSession = p.getSchedules().stream()
            .map(Schedule::getStartsAt)
            .filter(t -> t != null && t.isAfter(Instant.now()))
            .min(Instant::compareTo)
            .orElse(null);

        var user = p.getUserActivity().getUser();
        var activity = p.getUserActivity().getActivity();

        String organizerName = p.getOrganizerName() != null
            ? p.getOrganizerName()
            : user.getDisplayName();
        String organizerAvatarUrl = p.getOrganizerAvatarUrl() != null
            ? p.getOrganizerAvatarUrl()
            : user.getAvatarUrl();

        return new ProgramDto(
            p.getId(),
            p.getTitle(),
            p.getDescription(),
            p.getStatus().name(),
            p.getIsPublic(),
            user.getId(),
            organizerName,
            organizerAvatarUrl,
            activity.getName(),
            activity.getIcon(),
            nextSession,
            p.getCreatedAt(),
            p.getUpdatedAt(),
            schedules,
            media,
            averageScore,
            reviewCount,
            enrolledCount,
            p.getDurationWeeks(),
            p.getSessionsPerWeek(),
            p.getSessionDurationMinutes(),
            p.getPreferredDays(),
            p.getPreferredTime() != null ? p.getPreferredTime().name() : null,
            p.getMaxParticipants(),
            p.getPrivacy() != null ? p.getPrivacy().name() : ProgramPrivacy.PUBLIC.name(),
            p.getGoals(),
            p.getPrerequisites(),
            p.getLocationType() != null ? p.getLocationType().name() : null
        );
    }

    private ScheduleDto toScheduleDto(Schedule s, UUID requesterId) {
        boolean isOwner = s.getProgram().getUserActivity().getUser().getId().equals(requesterId);

        Double lat = null;
        Double lng = null;
        String displayAddress = null;

        if (s.getPlaceType() == PlaceType.ONLINE) {
            // Pas de coordonnées pour les séances en ligne
        } else if (s.getPlaceType() == PlaceType.PUBLIC) {
            if (s.getLocation() != null) {
                lat = s.getLocation().getY();
                lng = s.getLocation().getX();
            }
            displayAddress = s.getAddressPublic();
        } else if (Boolean.TRUE.equals(s.getShowExactAddress()) || isOwner) {
            if (s.getLocation() != null) {
                lat = s.getLocation().getY();
                lng = s.getLocation().getX();
            }
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
