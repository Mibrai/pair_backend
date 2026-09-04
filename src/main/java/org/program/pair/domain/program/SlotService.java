package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.ScheduleConflictDto;
import org.program.pair.domain.program.dto.SlotBoundsRequest;
import org.program.pair.domain.program.dto.SlotBoundsResponse;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.dto.SlotFeedRequest;
import org.program.pair.domain.program.dto.SlotParticipantDto;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotParticipationRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.GeoBounds;
import org.program.pair.shared.GeoUtils;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ScheduleConflictException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.program.pair.domain.watch.WatchService;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rejoindre un créneau ouvert — le coeur du produit meetDo. Distinct de
 * ProgramEnrollmentService (inscription à un programme structuré multi-semaines) :
 * ici on parle d'un RSVP léger sur UNE occurrence précise. Les deux mécanismes
 * partagent la même capacité (Schedule.maxParticipants), voir
 * ScheduleRepository.countConfirmedParticipants / lockById.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SlotService {

    /**
     * Fuseau de référence pour rapprocher un instant UTC d'une case de
     * disponibilité. Le même que celui du développement des récurrences : deux
     * fuseaux différents rangeraient la même séance dans « mardi soir » ici et
     * « mardi après-midi » là.
     */
    @Value("${pair.recurrence.zone:Europe/Paris}")
    private String zoneId;

    private final ScheduleRepository scheduleRepository;
    private final SlotParticipationRepository participationRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final ChatService chatService;
    private final NotificationService notificationService;
    private final ScheduleConflictDetector conflictDetector;
    private final BlockFilterService blockFilterService;
    private final HtmlSanitizer sanitizer;
    private final ParticipantCounter participantCounter;
    private final WaitlistPromoter waitlistPromoter;
    /**
     * Pour la seule ligne d'arrivée de la liste des inscrits. La dépendance va bien
     * dans ce sens : le module veille lit les créneaux et leurs inscrits, il
     * n'appelle jamais ce service-ci.
     */
    private final WatchService watchService;

    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getSlotFeed(SlotFeedRequest request, UUID requesterId) {
        Instant from = request.from() != null ? request.from() : Instant.now();
        Instant to = request.to() != null ? request.to() : Instant.now().plus(7, ChronoUnit.DAYS);

        // Hibernate ne sait pas lier une liste vide dans un IN : quand aucune
        // catégorie n'est demandée, on passe le drapeau à faux et une liste
        // factice non vide, que la requête ne regarde alors pas.
        Set<UUID> categoryIds = request.effectiveCategoryIds();
        boolean filterByCategory = !categoryIds.isEmpty();

        Set<String> languages = request.effectiveLanguages();
        boolean filterByLanguage = !languages.isEmpty();

        Set<String> tags = request.effectiveAccessibilityTags();
        boolean filterByTags = !tags.isEmpty();

        // Des ids, puis un rechargement avec les LEFT JOIN FETCH : la requête
        // native ne peut pas en porter, et les entités qu'elle rendrait
        // directement arriveraient avec toutes leurs associations paresseuses.
        // Le mapping paierait alors, par créneau, la cascade program →
        // userActivity → activity → category.
        List<UUID> ids = scheduleRepository.findOpenSlotIdsInRadius(
            request.lat(), request.lng(), request.radiusMeters(),
            from, to, request.activityId(),
            filterByCategory, filterByCategory ? categoryIds : ScheduleRepository.NO_CATEGORY_FILTER,
            request.createdSince(), 100, requesterId,
            filterByLanguage, filterByLanguage ? languages : ScheduleRepository.NO_LANGUAGE_FILTER,
            filterByTags, filterByTags ? tags : ScheduleRepository.NO_TAG_FILTER, tags.size(),
            zoneId);

        if (ids.isEmpty()) {
            return List.of();
        }

        Map<UUID, Schedule> parId = scheduleRepository.findWithActivityDetailsByIds(ids).stream()
            .collect(Collectors.toMap(Schedule::getId, Function.identity()));

        // L'ordre est repris depuis la liste d'ids, et c'est essentiel :
        // findWithActivityDetailsByIds interroge par IN et ne garantit rien,
        // alors que le SQL natif porte le classement par jour, disponibilité,
        // heure puis distance. Sans cette reprise, le tri disparaîtrait sans
        // qu'aucune erreur ne soit levée.
        List<Schedule> slots = ids.stream()
            .map(parId::get)
            .filter(Objects::nonNull)
            .filter(s -> !s.getProgram().getUserActivity().getUser().getId().equals(requesterId))
            .toList();

        FeedContext context = feedContext(slots, requesterId);

        return slots.stream()
            .map(s -> toFeedItem(s, request.lat(), request.lng(), requesterId, context))
            .toList();
    }

    /**
     * Les créneaux d'un rectangle — l'onglet « Créneaux » de la carte.
     *
     * <p><b>Pourquoi cette route existe.</b> {@code /slots/feed} interroge un
     * disque plafonné à 50 km. Sur une vue à l'échelle d'un pays, l'onglet
     * Activités montrait tout et celui des créneaux cherchait dans un cinquantième
     * de l'écran — et affirmait ensuite qu'il n'y avait rien. Le défaut n'était
     * pas dans le plafond : il était dans le fait de répondre à une question
     * rectangulaire par un disque. Le plafond du fil ne change donc pas.
     *
     * <p><b>Deux requêtes, et il en faut deux.</b> Le compte porte sur le même
     * {@code WHERE} que la page ; c'est lui qui permet de dire « il y en a plus »
     * plutôt que de tronquer en silence. Voir
     * {@link ScheduleRepository#countOpenSlotsInBounds}.
     *
     * <p><b>Le lieu est filtré en base</b>, pas ici : un créneau dont la position
     * n'est pas partagée n'entre pas dans la réponse. Sur le fil il remonte sans
     * coordonnées, et c'est correct — il est trouvable sans être situé. Ici, la
     * question posée est géographique : appartenir au rectangle <i>est</i> une
     * position. Conséquence à retenir : tout élément rendu par cette route porte
     * des {@code lat}/{@code lng} non nuls, ce qu'aucune autre lecture de
     * {@code SlotFeedItemDto} ne garantit.
     *
     * <p>{@code distanceMeters} est nul : sans centre, il n'y a pas de distance à
     * mesurer, et en inventer une depuis le centre du rectangle serait rendre un
     * nombre que personne n'a demandé.
     */
    @Transactional(readOnly = true)
    public SlotBoundsResponse getSlotsInBounds(SlotBoundsRequest request, UUID requesterId) {
        GeoBounds.validateRectangle(
            request.north(), request.south(), request.east(), request.west());

        Instant from = request.from() != null ? request.from() : Instant.now();
        Instant to = request.to() != null ? request.to() : Instant.now().plus(7, ChronoUnit.DAYS);

        // Mêmes conventions de liaison que le fil : le drapeau porte « y a-t-il un
        // filtre », la liste ne doit jamais être vide même quand il est faux.
        Set<UUID> categoryIds = request.effectiveCategoryIds();
        boolean filterByCategory = !categoryIds.isEmpty();

        Set<String> languages = request.effectiveLanguages();
        boolean filterByLanguage = !languages.isEmpty();

        Set<String> tags = request.effectiveAccessibilityTags();
        boolean filterByTags = !tags.isEmpty();

        long total = scheduleRepository.countOpenSlotsInBounds(
            request.north(), request.south(), request.east(), request.west(),
            from, to, request.activityId(),
            filterByCategory, filterByCategory ? categoryIds : ScheduleRepository.NO_CATEGORY_FILTER,
            request.createdSince(), requesterId,
            filterByLanguage, filterByLanguage ? languages : ScheduleRepository.NO_LANGUAGE_FILTER,
            filterByTags, filterByTags ? tags : ScheduleRepository.NO_TAG_FILTER, tags.size());

        if (total == 0) {
            return new SlotBoundsResponse(List.of(), false, 0);
        }

        List<UUID> ids = scheduleRepository.findOpenSlotIdsInBounds(
            request.north(), request.south(), request.east(), request.west(),
            from, to, request.activityId(),
            filterByCategory, filterByCategory ? categoryIds : ScheduleRepository.NO_CATEGORY_FILTER,
            request.createdSince(), requesterId,
            filterByLanguage, filterByLanguage ? languages : ScheduleRepository.NO_LANGUAGE_FILTER,
            filterByTags, filterByTags ? tags : ScheduleRepository.NO_TAG_FILTER, tags.size(),
            request.limit(), request.offset());

        // Un offset au-delà du total rend une page vide sans que la zone le soit :
        // truncated doit alors valoir vrai, sans quoi le client conclurait de la
        // page vide que le rectangle l'est.
        if (ids.isEmpty()) {
            return new SlotBoundsResponse(List.of(), true, (int) total);
        }

        Map<UUID, Schedule> parId = scheduleRepository.findWithActivityDetailsByIds(ids).stream()
            .collect(Collectors.toMap(Schedule::getId, Function.identity()));

        // L'ordre vient de la liste d'ids, pas du rechargement : findWithActivityDetailsByIds
        // interroge par IN et ne garantit rien. Sans cette reprise, le classement
        // chronologique disparaîtrait sans qu'aucune erreur ne soit levée — et avec
        // lui la seule chose qui rende la troncature défendable.
        List<Schedule> slots = ids.stream()
            .map(parId::get)
            .filter(Objects::nonNull)
            .toList();

        FeedContext context = feedContext(slots, requesterId);

        List<SlotFeedItemDto> items = slots.stream()
            .map(s -> toFeedItem(s, null, null, requesterId, context))
            .toList();

        return new SlotBoundsResponse(
            items, total > (long) request.offset() + items.size(), (int) total);
    }

    @Transactional(readOnly = true)
    public SlotFeedItemDto getSlot(UUID scheduleId, UUID requesterId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
        return toFeedItem(slot, null, null, requesterId);
    }

    /**
     * Rejoindre un créneau. Effet de bord clé : ouvre automatiquement une
     * conversation avec l'hôte, contextualisée par l'activité.
     */
    public SlotFeedItemDto joinSlot(UUID userId, UUID scheduleId, JoinSlotRequest request) {
        // Verrou pessimiste : même ligne que ProgramEnrollmentService.joinProgram
        // pour empêcher un dépassement de maxParticipants par les deux chemins à la fois.
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        User host = slot.getProgram().getUserActivity().getUser();

        // En tête de la chaîne, et pas ailleurs : les refus qui suivent nomment
        // précisément ce qui cloche, et l'un d'eux rendu à une personne bloquée
        // lui apprendrait que le créneau existe, qu'il est ouvert, et qu'il a de
        // la place.
        if (blockFilterService.blockedBy(userId, host.getId())) {
            throw new ValidationException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué l'organisateur de ce créneau.");
        }
        if (blockFilterService.blocked(userId, host.getId())) {
            // Bloqué par l'hôte : le créneau a déjà disparu de son fil, il ne
            // doit pas réapparaître par son identifiant.
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        if (host.getId().equals(userId)) {
            throw new ValidationException(ErrorCode.SLOT_OWN_SLOT, "Vous ne pouvez pas rejoindre votre propre créneau.");
        }
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS, "Ce créneau n'est pas ouvert aux partenaires.");
        }
        if (slot.getStatus() != SlotStatus.OPEN) {
            throw new ValidationException(ErrorCode.SLOT_NOT_ACCEPTING_PARTICIPANTS, "Ce créneau n'accepte plus de participants.");
        }
        if (slot.getStartsAt().isBefore(Instant.now())) {
            throw new ValidationException(ErrorCode.SLOT_ALREADY_STARTED, "Ce créneau est déjà passé.");
        }
        // Sur l'ÉTAT de la participation, jamais sur l'existence de sa ligne.
        //
        // Le contrôle portait sur existsByScheduleIdAndUserId, donc sur la
        // présence d'une ligne quel que soit son état. Comme leaveSlot pose
        // WITHDRAWN sur cette même ligne — l'unicité (schedule_id, user_id) lui
        // interdit d'en créer une seconde — se désinscrire fermait la porte pour
        // de bon : le POST suivant refusait avec « Vous avez déjà rejoint ce
        // créneau », adressé à quelqu'un qui venait précisément de le quitter.
        // Signalé par le client le 04/09, reproduit trois fois sur trois.
        //
        // Ce n'est pas un cas de bord : hésiter entre deux séances du même soir,
        // c'est changer d'avis deux fois, et la première hésitation condamnait.
        //
        // WAITLISTED reste un refus, et c'est délibéré : la file existe pour
        // ordonner l'entrée, et convertir sa propre attente en inscription par
        // ce chemin doublerait tous ceux qui attendent devant.
        //
        // Sous un code à lui, en revanche : le refus ne change pas, sa raison
        // devient vraie. SLOT_ALREADY_JOINED disait « vous avez déjà rejoint ce
        // créneau » à quelqu'un qui attendait précisément de pouvoir le
        // rejoindre — et le message vient du bundle par error.<CODE>, jamais de
        // l'exception, donc le corriger imposait un code. Ajout additif : un
        // client qui ne connaît pas SLOT_ALREADY_WAITLISTED affiche le message
        // rendu, qui est juste.
        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElse(null);
        if (participation != null && participation.getStatus() == ParticipationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_JOINED, "Vous avez déjà rejoint ce créneau.");
        }
        if (participation != null && participation.getStatus() == ParticipationStatus.WAITLISTED) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_WAITLISTED,
                "Vous êtes déjà en liste d'attente sur ce créneau.");
        }
        if (slot.getMaxParticipants() != null
                && scheduleRepository.countConfirmedParticipants(scheduleId) >= slot.getMaxParticipants()) {
            throw new ValidationException(ErrorCode.SLOT_FULL, "Ce créneau est complet.");
        }

        // Même règle et même enveloppe que POST /programs/{id}/join : le chemin
        // d'entrée ne doit pas changer ce qui est autorisé. Vérifiée en dernier,
        // sous le verrou pessimiste posé plus haut — c'est ce qui empêche deux
        // appareils de s'inscrire en parallèle sur deux créneaux qui se chevauchent.
        List<ScheduleConflictDto> conflicts = conflictDetector.detect(userId, List.of(slot));
        if (!conflicts.isEmpty()) {
            throw new ScheduleConflictException(
                "Ce créneau chevauche un engagement que vous avez déjà pris.", conflicts);
        }

        // La ligne est réactivée quand elle existe, créée sinon — même geste que
        // joinWaitlist, pour la même raison : la contrainte d'unicité interdit
        // d'en poser une seconde.
        if (participation == null) {
            participation = new SlotParticipation();
            participation.setSchedule(slot);
            participation.setUser(userRepository.getReferenceById(userId));
        }
        participation.setStatus(ParticipationStatus.CONFIRMED);

        // Tout ce que la vie précédente de la ligne avait écrit est effacé, et
        // chacun de ces quatre champs a une conséquence s'il survit :
        // withdrawnAt ferait lire un désistement là où il y a une inscription ;
        // waitlistPosition se mettrait en travers du suivant (index unique
        // partiel, V67) ; promotedAt raconterait une promotion qui n'a pas eu
        // lieu ; attendanceClosedAt retirerait la séance du signal de fiabilité
        // pour toujours, puisque findUnansweredToClose exige qu'il soit nul.
        participation.setWithdrawnAt(null);
        participation.setWaitlistPosition(null);
        participation.setPromotedAt(null);
        participation.setAttendanceClosedAt(null);

        // Le message d'accompagnement n'est écrasé que s'il en vient un nouveau :
        // celui d'une inscription précédente vaut mieux que rien, et le taire
        // silencieusement ferait disparaître un texte que l'hôte a peut-être déjà
        // lu.
        if (request.joinMessage() != null) {
            participation.setJoinMessage(sanitizer.sanitize(request.joinMessage()).strip());
        }
        participationRepository.save(participation);

        participantCounter.refresh(slot);
        scheduleRepository.save(slot);

        // Ouvrir la conversation contextualisée (respecte receiveMessages de l'hôte).
        //
        // Le contexte est celui du créneau, pas seulement celui de l'activité :
        // c'est cette séance-là qui lie les deux personnes, et c'est sa date que
        // le client compare à maintenant pour griser le fil une fois passée.
        // L'activité seule ne désignerait pas la bonne séance dès que quelqu'un
        // suit deux programmes de la même activité.
        //
        // Deux réglages, deux portées : receiveMessages est celui de la personne,
        // allowParticipantMessages celui de ce programme-là. Un refus fait sauter
        // l'ouverture du fil, jamais l'inscription au créneau — rejoindre et
        // écrire sont deux choses, et fermer sa messagerie ne ferme pas ses
        // créneaux.
        if (Boolean.TRUE.equals(host.getReceiveMessages())
                && Boolean.TRUE.equals(slot.getProgram().getAllowParticipantMessages())) {
            chatService.createConversation(
                userId,
                new CreateConversationRequest(
                    host.getId(),
                    slot.getProgram().getUserActivity().getActivity().getId(),
                    slot.getProgram().getId()),
                slot.getProgram().getId(),
                slot.getId());
        }

        notificationService.notify(host.getId(), userId, NotificationType.SLOT_JOINED,
            NotificationPayload.ofSchedule(slot)
                .with("participantId", userId)
                .with("participantName", userRepository.findById(userId)
                    .map(User::getDisplayName).orElse("Quelqu'un"))
                .build());

        return toFeedItem(slot, null, null, userId);
    }

    public void leaveSlot(UUID userId, UUID scheduleId) {
        // Le verrou est pris en PREMIER, avant toute écriture.
        //
        // L'ordre inverse — écrire le désistement, puis verrouiller — laissait
        // une fenêtre non protégée entre les deux. Sans promotion automatique
        // elle ne coûtait qu'un compteur momentanément faux ; avec elle, deux
        // désistements simultanés pouvaient lire la même file et promouvoir deux
        // fois la même personne.
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Participation introuvable."));

        boolean wasConfirmed = participation.getStatus() == ParticipationStatus.CONFIRMED;

        participation.setStatus(ParticipationStatus.WITHDRAWN);
        participation.setWithdrawnAt(Instant.now());
        participation.setWaitlistPosition(null);
        participationRepository.save(participation);

        if (wasConfirmed) {
            waitlistPromoter.promoteFirstWaiting(slot);
        }
        participantCounter.refresh(slot);
        scheduleRepository.save(slot);
    }

    /**
     * Se mettre en liste d'attente sur un créneau complet.
     *
     * <p>La file est une <b>transition d'état sur la ligne unique</b>
     * {@code (schedule_id, user_id)}, jamais une seconde ligne : la contrainte
     * d'unicité l'interdit, et c'est heureux — une personne à la fois inscrite
     * et en attente sur le même créneau n'aurait aucun sens.
     *
     * <p>Contrairement à {@code joinSlot}, un créneau {@code FULL} est accepté :
     * c'est exactement celui pour lequel cette route existe.
     */
    public SlotFeedItemDto joinWaitlist(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        User host = slot.getProgram().getUserActivity().getUser();

        if (blockFilterService.blockedBy(userId, host.getId())) {
            throw new ValidationException(ErrorCode.USER_BLOCKED,
                "Vous avez bloqué l'organisateur de ce créneau.");
        }
        if (blockFilterService.blocked(userId, host.getId())) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }
        if (host.getId().equals(userId)) {
            throw new ValidationException(ErrorCode.SLOT_OWN_SLOT,
                "Vous ne pouvez pas vous mettre en attente de votre propre créneau.");
        }
        if (!Boolean.TRUE.equals(slot.getIsOpenToPartners())) {
            throw new ValidationException(ErrorCode.SLOT_NOT_OPEN_TO_PARTNERS,
                "Ce créneau n'est pas ouvert aux partenaires.");
        }
        if (slot.getStartsAt().isBefore(Instant.now())) {
            throw new ValidationException(ErrorCode.SLOT_ALREADY_STARTED, "Ce créneau est déjà passé.");
        }

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElseGet(() -> {
                SlotParticipation fresh = new SlotParticipation();
                fresh.setSchedule(slot);
                fresh.setUser(userRepository.getReferenceById(userId));
                return fresh;
            });

        if (participation.getStatus() == ParticipationStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.SLOT_ALREADY_JOINED,
                "Vous avez déjà rejoint ce créneau.");
        }
        if (participation.getStatus() != ParticipationStatus.WAITLISTED) {
            participation.setStatus(ParticipationStatus.WAITLISTED);
            participation.setWithdrawnAt(null);
            participation.setWaitlistPosition(
                participationRepository.lastWaitlistPosition(scheduleId) + 1);
            participationRepository.save(participation);
        }

        return toFeedItem(slot, null, null, userId);
    }

    /** Quitter la file. Les rangs suivants remontent d'un cran. */
    public void leaveWaitlist(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .filter(p -> p.getStatus() == ParticipationStatus.WAITLISTED)
            .orElseThrow(() -> new ResourceNotFoundException("Vous n'êtes pas en liste d'attente."));

        participation.setStatus(ParticipationStatus.WITHDRAWN);
        participation.setWithdrawnAt(Instant.now());
        participation.setWaitlistPosition(null);
        participationRepository.save(participation);

        waitlistPromoter.resequence(slot.getId());
    }

    /**
     * La file, réservée à l'organisateur.
     *
     * <p>404 et non 403 pour qui n'est pas l'hôte : {@code getParticipants} rend
     * un 403 depuis toujours, mais la règle transverse du produit demande de ne
     * pas confirmer l'existence d'une ressource qu'on n'a pas le droit de voir.
     */
    @Transactional(readOnly = true)
    public List<SlotParticipantDto> getWaitlist(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!slot.getProgram().getUserActivity().getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        return participationRepository.findWaitlist(scheduleId).stream()
            .map(p -> new SlotParticipantDto(
                p.getId(),
                userService.getPublicProfile(p.getUser().getId(), userId),
                p.getStatus().name(),
                p.getJoinMessage(),
                p.getCreatedAt(),
                // Toujours NONE, et jamais lu : on n'attend l'arrivée de personne
                // sur une file d'attente, et rien ne justifierait d'y porter un
                // état d'arrivée que l'organisateur n'a aucune raison de voir.
                SlotParticipantDto.Arrival.NONE))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SlotFeedItemDto> getMySlots(UUID userId, boolean upcomingOnly) {
        List<Schedule> hosted = scheduleRepository.findHostedOpenSlots(userId);

        List<Schedule> joined = participationRepository
            // WAITLISTED compris : un créneau où j'attends une place reste un
            // créneau qui me concerne, et le voir disparaître de « mes créneaux »
            // donnerait l'impression que l'inscription en file n'a pas pris.
            .findByUserIdAndStatusIn(userId, List.of(ParticipationStatus.INTERESTED,
                ParticipationStatus.CONFIRMED, ParticipationStatus.WAITLISTED))
            .stream()
            .map(SlotParticipation::getSchedule)
            .toList();

        List<Schedule> slots = java.util.stream.Stream.concat(hosted.stream(), joined.stream())
            .distinct()
            .filter(s -> !upcomingOnly || s.getStartsAt().isAfter(Instant.now()))
            .sorted(java.util.Comparator.comparing(Schedule::getStartsAt))
            .toList();

        FeedContext context = feedContext(slots, userId);

        return slots.stream()
            .map(s -> toFeedItem(s, null, null, userId, context))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<SlotParticipantDto> getParticipants(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();
        if (!hostId.equals(userId)) {
            throw new ForbiddenException(ErrorCode.SLOT_PARTICIPANTS_HOST_ONLY, "Seul l'hôte peut voir les participants.");
        }

        // L'arrivée de chacun, en une lecture pour tout le créneau : une par ligne
        // ferait une requête par inscrit sur un écran qui les montre tous.
        Map<UUID, WatchService.ArrivalView> arrivees = watchService.arrivalsByUser(scheduleId);

        // La file d'attente a son propre endpoint : sans ce filtre, les personnes
        // en attente arriveraient ici mêlées aux inscrits, et l'hôte croirait son
        // créneau plus rempli qu'il n'est.
        return participationRepository.findByScheduleId(scheduleId).stream()
            .filter(p -> p.getStatus() != ParticipationStatus.WAITLISTED)
            .map(p -> new SlotParticipantDto(
                p.getId(),
                userService.getPublicProfile(p.getUser().getId(), userId),
                p.getStatus().name(),
                p.getJoinMessage(),
                p.getCreatedAt(),
                arrivee(arrivees.get(p.getUser().getId()))
            ))
            .toList();
    }

    /**
     * L'arrivée d'un inscrit, ou {@code NONE}.
     *
     * <p><b>L'absence de veille et l'absence de déclaration rendent la même
     * valeur</b>, et c'est toute la protection : sans cela, l'organisateur
     * apprendrait qui se protège rien qu'en lisant sa liste d'inscrits.
     */
    private static SlotParticipantDto.Arrival arrivee(WatchService.ArrivalView vue) {
        return vue == null
            ? SlotParticipantDto.Arrival.NONE
            : new SlotParticipantDto.Arrival(vue.state(), vue.claimedAt(), vue.confirmedAt());
    }

    /**
     * Ce qu'il faut avoir sous la main pour rendre un lot de créneaux sans
     * retourner en base à chaque élément : le profil public de chaque hôte, et
     * ma participation à chacun des créneaux.
     *
     * <p>Les deux sont indexés par identifiant et calculés <b>une fois pour le
     * lot</b>. Un fil de vingt créneaux appelait auparavant, par élément, un
     * profil public complet (cinq requêtes, plus une par badge) et jusqu'à deux
     * lectures de participation — pour une information que plusieurs créneaux
     * partagent très souvent, le même hôte publiant plusieurs séances.
     *
     * <p>Une entrée absente de {@code participations} vaut « aucune
     * participation », jamais « pas encore chargée » : c'est ce qui autorise le
     * mapping à ne plus jamais toucher le dépôt.
     */
    private record FeedContext(Map<UUID, UserPublicDto> profiles,
                               Map<UUID, SlotParticipation> participations) {}

    private FeedContext feedContext(List<Schedule> slots, UUID requesterId) {
        if (slots.isEmpty()) {
            return new FeedContext(Map.of(), Map.of());
        }

        // distinct() avant l'appel, et non après : c'est tout l'intérêt: deux
        // créneaux du même hôte ne redemandent pas deux fois le même profil.
        Map<UUID, UserPublicDto> profiles = slots.stream()
            .map(s -> s.getProgram().getUserActivity().getUser().getId())
            .distinct()
            .collect(Collectors.toMap(Function.identity(),
                                      id -> userService.getPublicProfile(id, requesterId)));

        // Sans demandeur il n'y a pas de participation à chercher — la page
        // publique d'un créneau passe par ici.
        Map<UUID, SlotParticipation> participations = requesterId == null
            ? Map.of()
            : participationRepository
                .findByUserIdAndScheduleIdIn(requesterId, slots.stream().map(Schedule::getId).toList())
                .stream()
                .collect(Collectors.toMap(p -> p.getSchedule().getId(), Function.identity()));

        return new FeedContext(profiles, participations);
    }

    /**
     * Le cas d'un créneau seul, exprimé comme un lot d'un élément : une seule
     * écriture de la règle, donc pas de second chemin qui puisse en diverger.
     */
    private SlotFeedItemDto toFeedItem(Schedule slot, Double viewerLat, Double viewerLng, UUID requesterId) {
        List<Schedule> lot = List.of(slot);
        return toFeedItem(slot, viewerLat, viewerLng, requesterId, feedContext(lot, requesterId));
    }

    private SlotFeedItemDto toFeedItem(Schedule slot, Double viewerLat, Double viewerLng,
                                       UUID requesterId, FeedContext context) {
        Program program = slot.getProgram();
        UserActivity userActivity = program.getUserActivity();
        Activity activity = userActivity.getActivity();
        Category category = activity.getCategory();

        SlotParticipation myParticipation = context.participations().get(slot.getId());

        SlotAddressVisibility.Resolved place = SlotAddressVisibility.resolve(slot, myParticipation);

        Double distanceMeters = null;
        if (viewerLat != null && viewerLng != null && slot.getLocation() != null) {
            distanceMeters = GeoUtils.haversineMeters(viewerLat, viewerLng,
                slot.getLocation().getY(), slot.getLocation().getX());
        }

        String myParticipationStatus = myParticipation != null
            ? myParticipation.getStatus().name()
            : null;

        Integer myWaitlistPosition = myParticipation != null
                && myParticipation.getStatus() == ParticipationStatus.WAITLISTED
            ? myParticipation.getWaitlistPosition()
            : null;

        return new SlotFeedItemDto(
            slot.getId(),
            program.getId(),
            program.getTitle(),
            activity.getId(),
            activity.getName(),
            category != null ? category.getId() : null,
            category != null ? category.getColorRamp() : null,
            userActivity.getLevel() != null ? userActivity.getLevel().name() : null,
            userActivity.getFormat() != null ? userActivity.getFormat().name() : null,
            context.profiles().get(userActivity.getUser().getId()),
            slot.getPlaceName(),
            place.displayAddress(),
            place.lat(),
            place.lng(),
            distanceMeters,
            slot.getStartsAt(),
            slot.getEndsAt(),
            slot.getRecurrenceRule(),
            sessionDurationMinutes(slot, program),
            slot.getCreatedAt(),
            slot.getMaxParticipants(),
            slot.getParticipantCount(),
            slot.getIsOpenToPartners(),
            slot.getWelcomeNote(),
            myParticipationStatus,
            myWaitlistPosition,
            slot.getPrimaryLanguage(),
            slot.getAccessibilityTags().stream().map(Enum::name).sorted().toList()
        );
    }

    /**
     * Durée d'une séance, mesurée si possible, déclarée sinon, jamais devinée.
     *
     * <p>{@code endsAt} est nullable en base ; quand il manque, la durée déclarée
     * sur le programme est une meilleure réponse que rien. Quand les deux manquent,
     * on rend {@code null} plutôt qu'une convention : c'est à l'appelant de savoir
     * qu'il ne sait pas. La convention, elle, n'existe qu'à l'endroit où il faut
     * bien trancher — {@link ScheduleConflictDetector}.
     */
    private Integer sessionDurationMinutes(Schedule slot, Program program) {
        if (slot.getStartsAt() != null && slot.getEndsAt() != null
                && slot.getEndsAt().isAfter(slot.getStartsAt())) {
            return (int) Duration.between(slot.getStartsAt(), slot.getEndsAt()).toMinutes();
        }
        return program != null ? program.getSessionDurationMinutes() : null;
    }
}
