package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.alert.ActivityAlertService;
import org.program.pair.domain.media.StoredImageResolver;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.*;
import org.program.pair.domain.subscription.SubscriptionService;
import org.program.pair.repository.*;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final SlotParticipationRepository slotParticipationRepository;
    private final NotificationService notificationService;
    private final ActivityAlertService activityAlertService;
    private final SubscriptionService subscriptionService;
    private final HtmlSanitizer sanitizer;
    private final RecurrenceExpander recurrenceExpander;
    private final StoredImageResolver storedImageResolver;
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

        Program saved = programRepository.save(program);
        subscriptionService.notifySubscribersOfNewProgram(saved);
        return toDto(saved, userId);
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

    /**
     * Duplique un programme de l'auteur : métadonnées et créneaux, en une
     * transaction.
     *
     * <p>Sans cet endpoint, le client enchaînait {@code GET} + {@code POST} +
     * N × {@code POST /schedules} — une transaction distribuée sans rollback, qui
     * laissait un programme à moitié copié au premier échec. Ici, tout aboutit ou
     * rien n'est créé.
     *
     * <p>La copie naît en <b>brouillon non public</b>, et surtout <b>sans passer
     * par {@code createProgram}</b> : celui-ci notifie les abonnés de l'auteur
     * ({@code AUTHOR_NEW_PROGRAM}), et une duplication qui notifie est une
     * duplication qui spamme.
     *
     * <p><b>La copie naît sans couverture</b> ({@code imageUrl == null}), et
     * aucun octet n'est écrit sur le stockage pour elle. C'est un revirement
     * assumé du contrat B4 (lot 7), qui copiait le fichier physiquement dans
     * cette transaction. Deux raisons, dans cet ordre :
     * <ul>
     *   <li><b>le coût de stockage</b> (arbitrage produit du 2026-08-11) :
     *       dupliquer trois fois faisait payer quatre fois les mêmes octets,
     *       pour une couverture que personne n'avait choisie. Le client
     *       l'effaçait d'ailleurs déjà par un {@code DELETE /programs/{id}/image}
     *       juste après — des octets écrits pour être supprimés trois requêtes
     *       plus tard ;</li>
     *   <li><b>la robustesse</b> : la copie du fichier échouait quand la source
     *       avait disparu du stockage, et la transaction étant tout-ou-rien,
     *       <i>aucune</i> copie n'était créée. Un fichier manquant empêchait donc
     *       de copier des métadonnées et des créneaux qui, eux, allaient
     *       parfaitement bien.</li>
     * </ul>
     * Les médias additionnels ({@code ProgramMedia}) ne sont pas copiés non plus.
     *
     * <p>Chaque créneau copié repart à zéro : aucun participant, statut
     * {@code OPEN}. Les inscriptions appartiennent au créneau d'origine, pas à sa
     * copie.
     */
    public ProgramDto duplicateProgram(UUID userId, UUID programId, String requestedTitle) {
        Program original = findProgramOwnedBy(programId, userId);

        Program copy = new Program();
        copy.setUserActivity(original.getUserActivity());
        copy.setTitle(duplicateTitle(original.getTitle(), requestedTitle));
        copy.setDescription(original.getDescription());
        copy.setStatus(ProgramStatus.DRAFT);
        copy.setIsPublic(false);
        copy.setOrganizerName(original.getOrganizerName());
        copy.setOrganizerAvatarUrl(original.getOrganizerAvatarUrl());
        copy.setDurationWeeks(original.getDurationWeeks());
        copy.setSessionsPerWeek(original.getSessionsPerWeek());
        copy.setSessionDurationMinutes(original.getSessionDurationMinutes());
        copy.setPreferredDays(original.getPreferredDays() != null
            ? original.getPreferredDays().clone() : null);
        copy.setPreferredTime(original.getPreferredTime());
        copy.setMaxParticipants(original.getMaxParticipants());
        copy.setPrivacy(original.getPrivacy());
        copy.setGoals(original.getGoals());
        copy.setPrerequisites(original.getPrerequisites());
        copy.setLocationType(original.getLocationType());

        Program saved = programRepository.save(copy);

        for (Schedule schedule : scheduleRepository.findByProgramId(programId)) {
            Schedule scheduleCopy = new Schedule();
            scheduleCopy.setProgram(saved);
            scheduleCopy.setPlaceName(schedule.getPlaceName());
            scheduleCopy.setPlaceType(schedule.getPlaceType());
            scheduleCopy.setLocation(schedule.getLocation() != null
                ? geometryFactory.createPoint(schedule.getLocation().getCoordinate()) : null);
            scheduleCopy.setAddressPublic(schedule.getAddressPublic());
            scheduleCopy.setShowExactAddress(schedule.getShowExactAddress());
            scheduleCopy.setStartsAt(schedule.getStartsAt());
            scheduleCopy.setEndsAt(schedule.getEndsAt());
            scheduleCopy.setRecurrenceRule(schedule.getRecurrenceRule());
            scheduleCopy.setMaxParticipants(schedule.getMaxParticipants());
            scheduleCopy.setIsOpenToPartners(schedule.getIsOpenToPartners());
            scheduleCopy.setStatus(SlotStatus.OPEN);
            scheduleCopy.setParticipantCount(0);
            scheduleCopy.setWelcomeNote(schedule.getWelcomeNote());
            scheduleRepository.save(scheduleCopy);
        }

        refreshNextSessionAt(saved);
        return toDto(saved, userId);
    }

    /**
     * Titre du duplicata : celui demandé s'il est utilisable, sinon l'original
     * suffixé — tronqué pour que le suffixe tienne toujours dans les
     * 150 caractères de la colonne.
     */
    private String duplicateTitle(String originalTitle, String requestedTitle) {
        if (requestedTitle != null && !requestedTitle.isBlank()) {
            return sanitizer.sanitize(requestedTitle).strip();
        }
        String suffix = " (copie)";
        int maxBase = 150 - suffix.length();
        String base = originalTitle.length() > maxBase
            ? originalTitle.substring(0, maxBase) : originalTitle;
        return base + suffix;
    }

    public ProgramDto updateProgramImage(UUID userId, UUID programId, String imageUrl) {
        Program program = findProgramOwnedBy(programId, userId);
        program.setImageUrl(imageUrl);
        return toDto(programRepository.save(program), userId);
    }

    public String removeProgramImage(UUID userId, UUID programId) {
        Program program = findProgramOwnedBy(programId, userId);
        String previousImageUrl = program.getImageUrl();
        program.setImageUrl(null);
        programRepository.save(program);
        return previousImageUrl;
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

    @Transactional(readOnly = true)
    public List<ProgramDto> getPublicProgramsByUser(UUID userId) {
        return programRepository.findActivePublicByUserId(userId)
            .stream()
            .map(p -> toDto(p, null))
            .collect(Collectors.toList());
    }

    private static final double DEFAULT_RADIUS_KM = 5.0;
    private static final double MIN_RADIUS_KM = 0.01;
    private static final double MAX_RADIUS_KM = 100.0;

    @Transactional(readOnly = true)
    public List<ProgramDto> getNearbyPrograms(UUID requesterId, Double lat, Double lng, Double radiusKm) {
        if (lat == null || lng == null) {
            throw new ValidationException("Les paramètres 'lat' et 'lng' doivent être fournis ensemble.");
        }
        if (lat < -90 || lat > 90) {
            throw new ValidationException("Le paramètre 'lat' doit être compris entre -90 et 90.");
        }
        if (lng < -180 || lng > 180) {
            throw new ValidationException("Le paramètre 'lng' doit être compris entre -180 et 180.");
        }

        double effectiveRadiusKm = radiusKm != null ? radiusKm : DEFAULT_RADIUS_KM;
        if (effectiveRadiusKm < MIN_RADIUS_KM || effectiveRadiusKm > MAX_RADIUS_KM) {
            throw new ValidationException(
                "Le paramètre 'radius_km' doit être compris entre " + MIN_RADIUS_KM + " et " + MAX_RADIUS_KM + ".");
        }

        int radiusMeters = Math.max(1, (int) Math.round(effectiveRadiusKm * 1000));

        return programRepository.findVisibleNearScheduleOrOrganizer(lat, lng, radiusMeters, 100)
            .stream()
            .map(p -> toDto(p, requesterId))
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
        if (request.isOpenToPartners() != null) {
            schedule.setIsOpenToPartners(request.isOpenToPartners());
        }
        if (request.welcomeNote() != null) {
            schedule.setWelcomeNote(sanitizer.sanitize(request.welcomeNote()).strip());
        }

        Schedule saved = scheduleRepository.save(schedule);
        ScheduleDto dto = toScheduleDto(saved, userId);
        refreshNextSessionAt(program);
        activityAlertService.evaluateAndNotify(saved);
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
        if (request.isOpenToPartners() != null) schedule.setIsOpenToPartners(request.isOpenToPartners());
        if (request.welcomeNote() != null)    schedule.setWelcomeNote(sanitizer.sanitize(request.welcomeNote()).strip());

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

        List<UUID> slotParticipantIds = slotParticipationRepository.findByScheduleId(scheduleId).stream()
            .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED || p.getStatus() == ParticipationStatus.INTERESTED)
            .map(p -> p.getUser().getId())
            .toList();
        List<UUID> programParticipantIds = userProgramRepository.findByProgramIdAndStatus(prog.getId(), UserProgramStatus.ACTIVE)
            .stream()
            .filter(up -> up.getSchedule() != null && up.getSchedule().getId().equals(scheduleId))
            .map(up -> up.getUser().getId())
            .toList();

        if (slotParticipantIds.isEmpty() && programParticipantIds.isEmpty()) {
            // Aucun participant : suppression définitive comme avant.
            scheduleRepository.delete(schedule);
        } else {
            // Des personnes comptent sur ce créneau : on annule et on les
            // prévient plutôt que de supprimer silencieusement la ligne
            // (une suppression aurait cascade-delete les participations sans
            // possibilité de notifier qui que ce soit).
            schedule.setStatus(SlotStatus.CANCELLED);
            scheduleRepository.save(schedule);

            java.util.stream.Stream.concat(slotParticipantIds.stream(), programParticipantIds.stream())
                .distinct()
                .forEach(participantId -> notificationService.notify(participantId,
                    NotificationType.SLOT_CANCELLED,
                    NotificationPayload.ofSchedule(schedule).build()));
        }

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

    /**
     * Prochaine séance du programme, <b>récurrences développées</b>.
     *
     * <p>Le balayage naïf d'avant prenait le plus petit {@code startsAt} futur :
     * un créneau hebdomadaire dont la première séance était passée donnait
     * {@code null}, et le programme paraissait terminé. Le développement se fait
     * ici plutôt que d'attendre {@code RecurringSlotRolloverJob}, pour qu'un
     * programme créé ou modifié porte immédiatement la bonne date au lieu de la
     * porter au prochain passage du job.
     */
    private void refreshNextSessionAt(Program program) {
        Instant now = Instant.now();
        Instant next = scheduleRepository.findByProgramId(program.getId()).stream()
            .map(s -> recurrenceExpander.nextOccurrence(s.getStartsAt(), s.getRecurrenceRule(), now))
            .filter(java.util.Objects::nonNull)
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

        var ua       = p.getUserActivity();
        var user     = ua.getUser();
        var activity = ua.getActivity();
        var category = activity.getCategory();

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
            // Vérifiée sur le stockage : une couverture dont le fichier a disparu
            // se résout en image nulle plutôt qu'en URL qui répondra 404 à chaque
            // affichage. Voir StoredImageResolver.
            storedImageResolver.resolveOrNull(p.getImageUrl()),
            ua.getId(),
            activity.getName(),
            activity.getIcon(),
            category != null ? category.getId() : null,
            category != null ? category.getName() : null,
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
        UUID ownerId = s.getProgram().getUserActivity().getUser().getId();
        boolean isOwner = requesterId != null && ownerId.equals(requesterId);

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
            s.getMaxParticipants(),
            s.getIsOpenToPartners(),
            s.getStatus().name(),
            s.getParticipantCount(),
            s.getWelcomeNote()
        );
    }
}
