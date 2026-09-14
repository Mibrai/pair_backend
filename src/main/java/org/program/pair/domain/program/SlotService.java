package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.chat.dto.CreateConversationRequest;
import org.program.pair.domain.notification.NotificationPayload;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.program.dto.JoinSlotRequest;
import org.program.pair.domain.program.dto.SlotCoParticipantDto;
import org.program.pair.domain.program.dto.ScheduleConflictDto;
import org.program.pair.domain.program.dto.SlotBoundsRequest;
import org.program.pair.domain.program.dto.SlotBoundsResponse;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.program.dto.SlotFeedRequest;
import org.program.pair.domain.program.dto.SlotParticipantDto;
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
    /**
     * La liste ordonnée des refus d'<b>entrée</b>, partagée avec
     * {@code ProgramEnrollmentService} : voir {@link SlotEntryGuard}. Le blocage
     * en fait partie, si bien que ce service ne décide plus lui-même de qui peut
     * s'inscrire — la règle et son ordre vivent d'un seul côté.
     */
    private final SlotEntryGuard entryGuard;
    /**
     * Le blocage pour les <b>lectures</b>, que la garde d'entrée ne couvre pas.
     *
     * <p>La distinction est celle qu'{@link SlotEntryGuard} porte dans son nom :
     * elle répond à « peut-on entrer ? », sur un créneau qu'on a déjà trouvé.
     * Restent deux questions de visibilité qui n'ont pas de porte —
     * {@link #getSlot} et {@link #getMySlots} — et dont la mauvaise réponse était
     * le défaut le plus visible de ce module : le fil et la carte masquaient bien
     * les créneaux d'une personne bloquée (voir {@code BlockSql}), mais leur fiche
     * répondait encore à qui en connaissait l'identifiant, et « mes créneaux »
     * continuait de les lister.
     */
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

        Instant now = Instant.now();
        boolean includePast = request.effectiveIncludePast();

        // Le passé se demande, il ne s'obtient pas en reculant `from`. Sans
        // includePast, la fenêtre honorait déjà n'importe quelle borne basse —
        // mais aucun créneau terminé ne pouvait y répondre, parce qu'ils sont
        // tous passés au statut PAST dans l'heure suivant leur fin. Le drapeau
        // gouverne les deux choses ensemble : le statut admis, et jusqu'où la
        // fenêtre a le droit de reculer.
        Instant floor = now.minus(SlotBoundsRequest.PAST_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant defaultFrom = includePast ? floor : now;
        Instant from = request.from() != null ? request.from() : defaultFrom;

        if (includePast && from.isBefore(floor)) {
            throw new ValidationException(ErrorCode.SLOT_PAST_WINDOW_TOO_WIDE,
                "Le paramètre 'from' ne peut pas remonter à plus de "
                    + SlotBoundsRequest.PAST_WINDOW_DAYS + " jours quand includePast=true.");
        }

        Instant to = request.to() != null ? request.to() : now.plus(7, ChronoUnit.DAYS);

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
            filterByTags, filterByTags ? tags : ScheduleRepository.NO_TAG_FILTER, tags.size(),
            includePast);

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
            includePast, request.limit(), request.offset());

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

    /**
     * La fiche d'un créneau, par son identifiant.
     *
     * <p><b>Introuvable quand un blocage sépare l'appelant de l'organisateur</b>,
     * dans un sens comme dans l'autre. Sans ce refus, le blocage ne masquait le
     * créneau que là où il se découvre — le fil, la carte, la recherche — et la
     * fiche restait ouverte à qui en avait l'identifiant : une notification reçue
     * la veille, un lien partagé, un écran resté ouvert. Elle porte le nom de
     * l'organisateur, l'heure, le lieu et, pour un inscrit, l'adresse exacte
     * ({@link SlotAddressVisibility}).
     *
     * <p><b>404 des deux côtés, y compris pour celle qui a bloqué</b> — c'est la
     * réponse que rend déjà la fiche de profil d'une personne bloquée, et une
     * lecture n'a pas de raison d'être plus bavarde ici que là-bas. La forme
     * nommée du refus ({@code USER_BLOCKED}) est réservée aux <i>gestes</i> —
     * s'inscrire, écrire —, où elle explique un échec que la personne vient de
     * provoquer.
     *
     * <p>Un participant encore inscrit reçoit le même 404 : le blocage retire ses
     * inscriptions croisées ({@code SlotBlockEffects}), et un reste d'inscription
     * — une donnée antérieure à cette règle, un retrait qui a échoué — ne doit pas
     * rouvrir la fiche.
     */
    @Transactional(readOnly = true)
    public SlotFeedItemDto getSlot(UUID scheduleId, UUID requesterId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();
        if (blockFilterService.blocked(requesterId, hostId)) {
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable.");
        }

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
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        User host = slot.getProgram().getUserActivity().getUser();

        // Blocage sous ses deux formes, propre créneau, ouverture aux
        // partenaires, statut, séance commencée — dans cet ordre, et écrits une
        // seule fois pour les deux portes d'entrée. Ce bloc vivait ici et nulle
        // part ailleurs, alors que POST /programs/{id}/join ouvre la même
        // séance : voir SlotEntryGuard.
        entryGuard.assertMayEnter(userId, slot, Instant.now());

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
        // Ici et pas dans la garde : après les deux réponses ci-dessus. Un
        // créneau à une place rejoint par une personne est complet à cause
        // d'elle, et « ce créneau est complet » serait une drôle de réponse à
        // celle qui l'occupe. Voir SlotEntryGuard.assertHasRoom.
        entryGuard.assertHasRoom(slot);

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
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_PARTICIPATION_INTROUVABLE", "Participation introuvable."));

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
     * c'est exactement celui pour lequel cette route existe. Un créneau qui
     * <b>n'est pas</b> complet, en revanche, se refuse : voir
     * {@link SlotEntryGuard#assertFull}.
     */
    public SlotFeedItemDto joinWaitlist(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.lockById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        // La même chaîne que joinSlot, aux deux différences que la file
        // implique : un créneau FULL est accepté, un créneau annulé rend
        // introuvable. Rien de tout cela n'était vérifié ici sauf le blocage,
        // le propre créneau, l'ouverture et le début — ni le statut, ni la
        // capacité (P-BL-14 étape 4).
        entryGuard.assertMayWait(userId, slot, Instant.now());

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
            // On n'attend que derrière un créneau réellement complet, et c'est
            // vérifié ici plutôt que dans la garde : après « vous y êtes déjà »
            // et sans toucher au cas de celui qui attend déjà, dont l'appel
            // reste idempotent. Le refus est un renvoi — l'app doit appeler
            // join — et non une impasse.
            entryGuard.assertFull(slot);

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
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        SlotParticipation participation = participationRepository
            .findByScheduleIdAndUserId(scheduleId, userId)
            .filter(p -> p.getStatus() == ParticipationStatus.WAITLISTED)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_PAS_EN_LISTE_ATTENTE", "Vous n'êtes pas en liste d'attente."));

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
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        if (!slot.getProgram().getUserActivity().getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable.");
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

    /**
     * Mes créneaux, hébergés et rejoints.
     *
     * <p><b>« À venir » se mesure sur la fin, jamais sur le début</b>, et
     * <b>un créneau annulé y reste</b> tant que sa date n'est pas passée : on
     * doit pouvoir ouvrir ce qu'annonce la notification d'annulation, et l'app
     * le barre. Il est reconnaissable à {@code status}, {@code cancelledAt} et
     * {@code cancellationReason} — trois champs que ce DTO ne portait pas, si
     * bien qu'un créneau annulé était strictement indiscernable d'un autre. Son
     * adresse exacte, elle, n'est plus rendue ({@link SlotAddressVisibility}).
     *
     * <p>Les deux frontières du produit, côte à côte : ici la fin, dans le fil et
     * à l'inscription le début. Voir {@code SlotController.getMySlots}.
     */
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

        // « À venir » se mesure sur la FIN, jamais sur le début.
        //
        // Le filtre portait sur startsAt : un créneau sortait de « mes créneaux »
        // à la seconde où il démarrait — mesuré le 03/09, créneau commencé depuis
        // 45 min absent, créneau à +2 h présent. C'est le moment où l'on ouvre
        // l'application pour retrouver l'adresse, et c'est précisément là qu'elle
        // cessait de la donner.
        //
        // La convention de fin est celle de SlotTiming, comme partout ailleurs :
        // fin déclarée, sinon deux heures. Un créneau ne quitte donc cette liste
        // qu'une fois réellement terminé.

        // Ce que masque le fil, cette liste-ci le masque aussi.
        //
        // Le blocage retire les inscriptions croisées à venir (SlotBlockEffects),
        // si bien qu'en régime normal aucun créneau d'une personne bloquée
        // n'arrive jusqu'ici. Ce filtre couvre ce qui reste : les blocages posés
        // avant cette règle et dont les inscriptions n'ont pas encore été
        // reprises, et les séances passées, que le retrait ne touche pas. Sans
        // lui, la liste annoncerait un créneau dont la fiche rend 404 —
        // l'application ouvrirait une erreur brute sur un simple tap.
        //
        // Un seul appel pour toute la liste, jamais un par créneau.
        Set<UUID> invisible = blockFilterService.invisibleTo(userId);

        Instant now = Instant.now();
        List<Schedule> slots = java.util.stream.Stream.concat(hosted.stream(), joined.stream())
            .distinct()
            .filter(s -> !invisible.contains(s.getProgram().getUserActivity().getUser().getId()))
            .filter(s -> !upcomingOnly || SlotTiming.endOf(s).isAfter(now))
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
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));

        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();
        if (!hostId.equals(userId)) {
            throw new ForbiddenException(ErrorCode.SLOT_PARTICIPANTS_HOST_ONLY, "Seul l'hôte peut voir les participants.");
        }

        // L'arrivée de chacun, en une lecture pour tout le créneau : une par ligne
        // ferait une requête par inscrit sur un écran qui les montre tous.
        Map<UUID, WatchService.ArrivalView> arrivees = watchService.arrivalsByUser(scheduleId);

        // Les seuls CONFIRMED (demande mobile du 13/09) : la file d'attente a son
        // propre endpoint, et une personne qui s'est retirée n'est plus inscrite —
        // la montrer ferait croire à l'hôte son créneau plus rempli qu'il n'est.
        // Le blocage vaut dans les deux sens (P-BL-05).
        Set<UUID> invisible = blockFilterService.invisibleTo(userId);
        return participationRepository.findByScheduleId(scheduleId).stream()
            .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED)
            .filter(p -> !invisible.contains(p.getUser().getId()))
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
     * Les autres inscrits d'un créneau, vus par un inscrit (demande mobile du
     * 13/09, P-MU-28 et P-BL-05).
     *
     * <p>Réservé aux inscrits {@code CONFIRMED} : un non-inscrit, une personne en
     * attente ou retirée reçoit {@code 403 SLOT_PARTICIPANTS_ENROLLED_ONLY}. Un hôte
     * bloqué avec l'appelant rend le créneau introuvable, comme sa fiche.
     *
     * <p>Seuls les {@code CONFIRMED}, l'appelant exclu, et personne qui soit
     * bloqué avec lui dans un sens ou dans l'autre — la règle est symétrique, donc
     * l'appelant disparaît aussi de la liste de qui il a bloqué. La réponse est
     * {@link SlotCoParticipantDto} : prénom et avatar, rien d'autre.
     */
    @Transactional(readOnly = true)
    public List<SlotCoParticipantDto> getCoParticipants(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable."));
        UUID hostId = slot.getProgram().getUserActivity().getUser().getId();
        if (blockFilterService.blocked(userId, hostId)) {
            throw new ResourceNotFoundException(ErrorCode.NOT_FOUND, "REFUS_CRENEAU_INTROUVABLE", "Créneau introuvable.");
        }
        if (!participationRepository.existsByScheduleIdAndUserIdAndStatus(
                scheduleId, userId, ParticipationStatus.CONFIRMED)) {
            throw new ForbiddenException(ErrorCode.SLOT_PARTICIPANTS_ENROLLED_ONLY,
                "Seuls les inscrits de ce créneau voient les autres inscrits.");
        }
        Set<UUID> invisible = blockFilterService.invisibleTo(userId);
        return participationRepository.findByScheduleId(scheduleId).stream()
            .filter(p -> p.getStatus() == ParticipationStatus.CONFIRMED)
            .map(SlotParticipation::getUser)
            .filter(u -> !u.getId().equals(userId) && !invisible.contains(u.getId()))
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .map(u -> new SlotCoParticipantDto(u.getId(),
                org.program.pair.domain.user.GivenName.from(u.getDisplayName()), u.getAvatarUrl()))
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
            // Le niveau du créneau, jamais celui de l'hôte : le niveau personnel
            // déclaré à l'onboarding s'affichait ici comme une exigence que
            // l'organisateur n'avait jamais formulée (P-MU-07).
            slot.getLevel() != null ? slot.getLevel().name() : null,
            userActivity.getFormat() != null ? userActivity.getFormat().name() : null,
            context.profiles().get(userActivity.getUser().getId()),
            slot.getPlaceName(),
            place.displayAddress(),
            place.lat(),
            place.lng(),
            distanceMeters,
            slot.getStartsAt(),
            slot.getEndsAt(),
            SlotTiming.endOf(slot),
            slot.getEndsAt() != null,
            slot.getRecurrenceRule(),
            sessionDurationMinutes(slot, program),
            slot.getCreatedAt(),
            // Le statut, l'instant et le motif d'annulation : trois champs que
            // le DTO ne portait pas, et sans lesquels un créneau annulé était
            // indiscernable d'un créneau normal — ni dans « Mes créneaux », ni
            // sur sa fiche. Les dates ne disent pas l'annulation.
            slot.getStatus() != null ? slot.getStatus().name() : null,
            slot.getCancelledAt(),
            slot.getCancellationReason(),
            slot.getMaxParticipants(),
            slot.getParticipantCount(),
            slot.getIsOpenToPartners(),
            slot.getWelcomeNote(),
            myParticipationStatus,
            myWaitlistPosition,
            slot.getPrimaryLanguage(),
            slot.getAccessibilityTags().stream().map(Enum::name).sorted().toList(),
            Boolean.TRUE.equals(program.getCostToShare()),
            Boolean.TRUE.equals(program.getCostToShare()) ? program.getCostNote() : null,
            // La ville telle que saisie, la même que PublicSlotView.city. Une
            // chaîne blanche (un PUT « city »: "") vaut une ville retirée.
            slot.getCity() != null && !slot.getCity().isBlank() ? slot.getCity() : null
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
