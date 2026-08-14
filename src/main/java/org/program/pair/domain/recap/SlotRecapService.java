package org.program.pair.domain.recap;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.recap.dto.NextSlotDto;
import org.program.pair.domain.recap.dto.RecapFeedRequest;
import org.program.pair.domain.recap.dto.SlotRecapDto;
import org.program.pair.domain.recap.dto.VibeCountDto;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.domain.user.dto.UserPublicDto;
import org.program.pair.repository.AttendanceRepository;
import org.program.pair.repository.RecapParticipantConsentRepository;
import org.program.pair.repository.RecapVibeVoteRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotRecapRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ForbiddenException;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.sanitizer.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Les cartes-souvenirs de créneau : agrégation et présentation de ce que le
 * produit possède déjà.
 *
 * <p><b>Le principe qui contraint tout le reste</b> : la carte porte sur le
 * moment collectif, jamais sur les individus qui y étaient. C'est ce qui
 * permet une trace publique attirante sans réintroduire la comparaison sociale
 * que meetDo refuse depuis sa conception. Son unique but est de donner envie de
 * rejoindre le <b>prochain</b> créneau — d'où le soin porté à
 * {@link NextSlotDto}, qui est le champ le plus important de la lecture.
 *
 * <p>Quatre règles s'appliquent partout ici, et rien ne les contourne :
 * <ul>
 *   <li>la carte naît à la <b>première contribution</b>, jamais d'avance ;</li>
 *   <li>contribuer suppose une présence <b>confirmée</b>, vérifiée par la
 *       requête qui sert déjà la boucle de recommandation ;</li>
 *   <li>la fenêtre se referme <b>sept jours</b> après la fin du créneau, et la
 *       carte se fige — pour qu'elle ne change pas des mois plus tard sous les
 *       yeux de quelqu'un qui l'a partagée ;</li>
 *   <li>apparaître nommé se <b>demande</b> : sans consentement explicite, on
 *       est compté et jamais montré.</li>
 * </ul>
 */
@Service
@Transactional
@RequiredArgsConstructor
public class SlotRecapService {

    /** Après quoi la carte se fige, comptée depuis la fin du créneau. */
    static final Duration CONTRIBUTION_WINDOW = Duration.ofDays(7);

    /** Au-delà de deux ambiances par personne, l'agrégation perd son sens. */
    static final int MAX_VIBES_PER_USER = 2;

    static final int MAX_TOP_VIBES = 3;
    static final int MAX_PHOTOS = 3;

    /** Même plafond que le feed de créneaux. */
    private static final int FEED_LIMIT = 100;

    private final SlotRecapRepository recapRepository;
    private final RecapVibeVoteRepository vibeVoteRepository;
    private final RecapParticipantConsentRepository consentRepository;
    private final AttendanceRepository attendanceRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final UserService userService;
    private final SlotAudience slotAudience;
    private final HtmlSanitizer sanitizer;

    // ————————————————————————— contribution —————————————————————————

    /**
     * Pose — ou remplace — les ambiances de l'appelant.
     *
     * <p>Remplacement et non ajout : le client renvoie l'ensemble de sa
     * sélection à chaque tap, et une liste vide vaut retrait.
     */
    public SlotRecapDto voteVibes(UUID userId, UUID scheduleId, List<String> rawVibes) {
        Schedule slot = loadSlot(scheduleId);
        requirePresence(userId, scheduleId);
        requireWindowOpen(slot);

        Set<SlotVibe> vibes = parseVibes(rawVibes);

        SlotRecap recap = openRecap(slot);
        vibeVoteRepository.deleteByRecapIdAndUserId(recap.getId(), userId);

        User voter = userRepository.getReferenceById(userId);
        for (SlotVibe vibe : vibes) {
            RecapVibeVote vote = new RecapVibeVote();
            vote.setRecap(recap);
            vote.setUser(voter);
            vote.setVibe(vibe);
            vibeVoteRepository.save(vote);
        }

        return toDto(touch(recap), userId);
    }

    /** Retire la contribution d'ambiance de l'appelant. Équivaut à un tableau vide. */
    public SlotRecapDto clearVibes(UUID userId, UUID scheduleId) {
        return voteVibes(userId, scheduleId, List.of());
    }

    /**
     * Accepte — ou retire — d'apparaître nommé.
     *
     * <p>Retirable à tout moment, y compris après publication : la carte se
     * régénère alors sans l'appelant, sans que rien d'autre ne bouge.
     */
    public SlotRecapDto setConsent(UUID userId, UUID scheduleId, boolean showIdentity) {
        Schedule slot = loadSlot(scheduleId);
        requirePresence(userId, scheduleId);
        requireWindowOpen(slot);

        SlotRecap recap = openRecap(slot);
        RecapParticipantConsent consent = consentRepository
            .findByRecapIdAndUserId(recap.getId(), userId)
            .orElseGet(() -> {
                RecapParticipantConsent fresh = new RecapParticipantConsent();
                fresh.setRecapId(recap.getId());
                fresh.setUserId(userId);
                return fresh;
            });
        consent.setShowIdentity(showIdentity);
        consentRepository.save(consent);

        return toDto(touch(recap), userId);
    }

    /**
     * Rattache un souvenir photo déjà stocké à la présence de l'appelant.
     *
     * <p>Ce n'est <b>pas</b> un chemin d'upload : le fichier est passé par
     * {@code POST /api/media/upload/image}, le seul qui existe. Doubler ce
     * chemin nous ramènerait les incidents média d'août.
     */
    public SlotRecapDto setMemoryPhoto(UUID userId, UUID scheduleId, String photoUrl, boolean isPublic) {
        Schedule slot = loadSlot(scheduleId);
        requirePresence(userId, scheduleId);
        requireWindowOpen(slot);

        Attendance attendance = attendanceRepository.findByScheduleIdAndUserId(scheduleId, userId)
            .orElseThrow(() -> new ForbiddenException(
                ErrorCode.RECAP_NOT_ATTENDEE, "Vous n'avez pas confirmé votre présence à ce créneau."));

        attendance.setMemoryPhotoUrl(photoUrl == null || photoUrl.isBlank() ? null : photoUrl.strip());
        // Une photo retirée ne peut pas rester publique.
        attendance.setMemoryIsPublic(attendance.getMemoryPhotoUrl() != null && isPublic);
        attendanceRepository.save(attendance);

        return toDto(touch(openRecap(slot)), userId);
    }

    // ————————————————————————— hôte —————————————————————————

    /** Le mot de l'hôte. Sanitizé : il est rendu tel quel sur une carte publique. */
    public SlotRecapDto setHostNote(UUID userId, UUID scheduleId, String note) {
        Schedule slot = loadSlot(scheduleId);
        requireHost(userId, slot);
        requireWindowOpen(slot);

        SlotRecap recap = openRecap(slot);
        recap.setHostNote(note == null || note.isBlank() ? null : sanitizer.sanitize(note).strip());

        return toDto(touch(recap), userId);
    }

    /**
     * Passe la carte de {@code PRIVATE} à {@code PUBLIC}, ou l'inverse.
     *
     * <p><b>Garde-fou</b> : une carte ne devient publique que si quelqu'un
     * d'autre que l'hôte a confirmé sa présence. Sans cela, un hôte pourrait
     * publier une carte laissant croire qu'un créneau a rassemblé du monde
     * alors qu'il y était seul — et la preuve sociale, dès qu'elle est fausse,
     * se retourne contre le produit.
     */
    public SlotRecapDto setVisibility(UUID userId, UUID scheduleId, String rawVisibility) {
        Schedule slot = loadSlot(scheduleId);
        requireHost(userId, slot);
        requireWindowOpen(slot);

        RecapVisibility visibility = parseVisibility(rawVisibility);
        if (visibility.isPublic()
                && !attendanceRepository.existsByScheduleIdAndWasPresentTrueAndUserIdNot(scheduleId, userId)) {
            throw new ConflictException(ErrorCode.RECAP_NEEDS_ATTENDEE,
                "Attendez qu'au moins une autre personne confirme sa présence pour rendre cette carte publique.");
        }

        SlotRecap recap = openRecap(slot);
        recap.setVisibility(visibility);
        if (visibility.isPublic() && recap.getPublishedAt() == null) {
            recap.setPublishedAt(Instant.now());
        }

        return toDto(touch(recap), userId);
    }

    // ————————————————————————— lecture —————————————————————————

    /**
     * Une carte, si elle est lisible par l'appelant.
     *
     * <p>Toute inaccessibilité rend {@code 404}, jamais {@code 403} : dire
     * « interdit » révèle qu'il y a quelque chose là où le demandeur n'a rien
     * à savoir. Même logique que les autres lectures publiques de l'API.
     */
    @Transactional(readOnly = true)
    public SlotRecapDto get(UUID scheduleId, UUID requesterId) {
        SlotRecap recap = recapRepository.findByScheduleId(scheduleId)
            .orElseThrow(SlotRecapService::noSuchRecap);

        Schedule slot = recap.getSchedule();
        if (!isPubliclyListable(slot)) {
            throw noSuchRecap();
        }
        if (!recap.getVisibility().isPublic() && !wasInvolved(requesterId, slot)) {
            throw noSuchRecap();
        }
        return toDto(recap, requesterId);
    }

    /** Les cartes publiques autour de moi. */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getFeed(RecapFeedRequest request, UUID requesterId) {
        return recapRepository
            .findPublicInRadius(request.lat(), request.lng(), request.radiusMeters(), FEED_LIMIT)
            .stream()
            .map(recap -> toDto(recap, requesterId))
            .toList();
    }

    /**
     * Les cartes des créneaux où j'étais.
     *
     * <p>Présence confirmée, pas simple inscription : la carte appartient à
     * ceux qui y étaient. Celles dont l'hôte n'est plus actif disparaissent
     * ici comme ailleurs.
     */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getMine(UUID userId) {
        return recapRepository.findMine(userId).stream()
            .filter(recap -> isHostActive(recap.getSchedule()))
            .map(recap -> toDto(recap, userId))
            .toList();
    }

    /**
     * Réaligne l'effectif d'une carte après une confirmation de présence.
     *
     * <p>Appelé par la boucle de présence : sans cela, quelqu'un qui confirme
     * après la dernière contribution ne serait jamais compté. Sans effet quand
     * le créneau n'a pas (encore) de carte — on n'en crée pas ici, une carte
     * naît d'une contribution, pas d'une présence.
     */
    public void refreshAttendeeCount(UUID scheduleId) {
        recapRepository.findByScheduleId(scheduleId).ifPresent(recap -> {
            recap.setAttendeeCount(attendanceRepository.countPresentByScheduleId(scheduleId));
            recapRepository.save(recap);
        });
    }

    // ————————————————————————— garde-fous —————————————————————————

    private Schedule loadSlot(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
    }

    /**
     * Présence confirmée sur ce créneau — la <b>même</b> vérification que la
     * boucle de recommandation, pas une variante.
     */
    private void requirePresence(UUID userId, UUID scheduleId) {
        if (!attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(scheduleId, userId)) {
            throw new ForbiddenException(ErrorCode.RECAP_NOT_ATTENDEE,
                "Seules les personnes présentes à ce créneau peuvent contribuer à sa carte.");
        }
    }

    private void requireHost(UUID userId, Schedule slot) {
        User host = hostOf(slot);
        if (host == null || !host.getId().equals(userId)) {
            throw new ForbiddenException(ErrorCode.RECAP_NOT_HOST,
                "Seul l'hôte du créneau peut modifier ce point.");
        }
    }

    private void requireWindowOpen(Schedule slot) {
        if (!isWindowOpen(slot)) {
            throw new ConflictException(ErrorCode.RECAP_WINDOW_CLOSED,
                "Cette carte est figée : la période de contribution de sept jours est terminée.");
        }
    }

    private boolean isWindowOpen(Schedule slot) {
        return Instant.now().isBefore(SlotTiming.endOf(slot).plus(CONTRIBUTION_WINDOW));
    }

    /**
     * Lecture des ambiances reçues : au plus deux, toutes connues, sans quoi un
     * {@code 422} nommé.
     *
     * <p>Refuser une valeur hors vocabulaire plutôt que la stocker n'est pas du
     * zèle : le client ignore ce qu'il ne connaît pas à l'affichage, donc une
     * valeur parasite en base fausserait les ambiances dominantes sans que rien
     * ne le signale.
     */
    private Set<SlotVibe> parseVibes(List<String> rawVibes) {
        if (rawVibes == null || rawVibes.isEmpty()) {
            return Set.of();
        }
        Set<SlotVibe> vibes = new LinkedHashSet<>();
        for (String raw : rawVibes) {
            vibes.add(SlotVibe.parse(raw).orElseThrow(() -> new BusinessException(
                ErrorCode.RECAP_INVALID_VIBES, "Ambiance inconnue : " + raw)));
        }
        if (vibes.size() > MAX_VIBES_PER_USER) {
            throw new BusinessException(ErrorCode.RECAP_INVALID_VIBES,
                "Choisissez au maximum deux ambiances.");
        }
        return vibes;
    }

    private RecapVisibility parseVisibility(String raw) {
        if (raw == null) {
            throw new ValidationException("La visibilité est obligatoire.");
        }
        try {
            return RecapVisibility.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Visibilité inconnue : " + raw);
        }
    }

    /** Crée la carte si c'est la première contribution, la rend sinon. */
    private SlotRecap openRecap(Schedule slot) {
        return recapRepository.findByScheduleId(slot.getId()).orElseGet(() -> {
            SlotRecap recap = new SlotRecap();
            recap.setSchedule(slot);
            recap.setVisibility(RecapVisibility.PRIVATE);
            recap.setAttendeeCount(attendanceRepository.countPresentByScheduleId(slot.getId()));
            return recapRepository.save(recap);
        });
    }

    /** Réaligne l'effectif et l'horodatage à chaque contribution. */
    private SlotRecap touch(SlotRecap recap) {
        recap.setAttendeeCount(attendanceRepository.countPresentByScheduleId(recap.getSchedule().getId()));
        recap.setUpdatedAt(Instant.now());
        return recapRepository.save(recap);
    }

    private static ResourceNotFoundException noSuchRecap() {
        return new ResourceNotFoundException("Carte-souvenir introuvable.");
    }

    // ————————————————————————— visibilité —————————————————————————

    /**
     * Le créneau est-il encore montrable ? Mêmes conditions que le feed de
     * créneaux, moins celles qui n'ont pas de sens pour un moment passé (statut
     * du créneau, fenêtre de dates, programme encore actif) : une carte est la
     * trace de ce qui a eu lieu, pas une invitation à s'inscrire.
     */
    private boolean isPubliclyListable(Schedule slot) {
        Program program = slot.getProgram();
        return program != null
            && Boolean.TRUE.equals(program.getIsPublic())
            && isHostActive(slot);
    }

    private boolean isHostActive(Schedule slot) {
        User host = hostOf(slot);
        return host != null && Boolean.TRUE.equals(host.getIsActive());
    }

    /**
     * L'appelant a-t-il quelque chose à voir avec ce créneau ? Soit il en fait
     * partie au sens de {@link SlotAudience} — hôte, inscrit au créneau ou
     * suiveur du programme —, soit il y a confirmé sa présence, ce qui reste
     * vrai même si son inscription a bougé depuis.
     */
    private boolean wasInvolved(UUID userId, Schedule slot) {
        if (userId == null) {
            return false;
        }
        return slotAudience.participantIds(slot).contains(userId)
            || attendanceRepository.existsByScheduleIdAndUserId(slot.getId(), userId);
    }

    private static User hostOf(Schedule slot) {
        Program program = slot.getProgram();
        if (program == null || program.getUserActivity() == null) {
            return null;
        }
        return program.getUserActivity().getUser();
    }

    // ————————————————————————— rendu —————————————————————————

    private SlotRecapDto toDto(SlotRecap recap, UUID viewerId) {
        Schedule slot = recap.getSchedule();
        Program program = slot.getProgram();
        UserActivity userActivity = program != null ? program.getUserActivity() : null;
        Activity activity = userActivity != null ? userActivity.getActivity() : null;
        Category category = activity != null ? activity.getCategory() : null;
        User host = hostOf(slot);

        boolean canContribute = isWindowOpen(slot)
            && viewerId != null
            && attendanceRepository.existsByScheduleIdAndUserIdAndWasPresentTrue(slot.getId(), viewerId);

        return new SlotRecapDto(
            slot.getId(),
            program != null ? program.getTitle() : null,
            activity != null ? activity.getName() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColorRamp() : null,
            slot.getStartsAt(),
            slot.getPlaceName(),
            slot.getCity(),
            recap.getAttendeeCount() != null ? recap.getAttendeeCount() : 0,
            topVibes(recap),
            publicPhotos(slot),
            recap.getHostNote(),
            host != null ? userService.getPublicProfile(host.getId(), viewerId) : null,
            visibleAttendees(recap, slot, viewerId),
            nextSlot(program, viewerId),
            recap.getVisibility().name(),
            canContribute,
            myVibes(recap, viewerId)
        );
    }

    /** Trois ambiances au maximum, de la plus choisie à la moins choisie. */
    private List<VibeCountDto> topVibes(SlotRecap recap) {
        return vibeVoteRepository.countByVibe(recap.getId()).stream()
            .limit(MAX_TOP_VIBES)
            .map(row -> new VibeCountDto(((SlotVibe) row[0]).name(), ((Number) row[1]).intValue()))
            .toList();
    }

    /**
     * Trois photos au maximum, et uniquement celles que leur auteur a rendues
     * publiques. Le plafond est ici et pas seulement à l'écran : appliqué
     * seulement côté client, il laisserait passer des images que personne n'a
     * accepté de publier.
     */
    private List<String> publicPhotos(Schedule slot) {
        return attendanceRepository.findByScheduleIdAndWasPresentTrue(slot.getId()).stream()
            .filter(a -> Boolean.TRUE.equals(a.getMemoryIsPublic()))
            .map(Attendance::getMemoryPhotoUrl)
            .filter(Objects::nonNull)
            .limit(MAX_PHOTOS)
            .toList();
    }

    /**
     * Les seules personnes nommables : celles qui étaient là <b>et</b> qui ont
     * explicitement accepté de l'être. L'hôte n'y figure pas — il a son propre
     * champ, et l'y répéter le ferait apparaître deux fois sur la carte.
     */
    private List<UserPublicDto> visibleAttendees(SlotRecap recap, Schedule slot, UUID viewerId) {
        List<UUID> consenting = consentRepository.findConsentingUserIds(recap.getId());
        if (consenting.isEmpty()) {
            return List.of();
        }
        User host = hostOf(slot);
        UUID hostId = host != null ? host.getId() : null;

        List<UserPublicDto> visible = new ArrayList<>();
        for (Attendance attendance : attendanceRepository.findByScheduleIdAndWasPresentTrue(slot.getId())) {
            UUID attendeeId = attendance.getUser().getId();
            if (attendeeId.equals(hostId) || !consenting.contains(attendeeId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(attendance.getUser().getIsActive())) {
                continue;
            }
            visible.add(userService.getPublicProfile(attendeeId, viewerId));
        }
        return visible;
    }

    /**
     * La prochaine séance ouverte du même programme — le champ qui convertit un
     * lecteur en participant. Nul franchement quand il n'y en a pas.
     */
    private NextSlotDto nextSlot(Program program, UUID viewerId) {
        if (program == null) {
            return null;
        }
        Optional<Schedule> next = scheduleRepository.findNextOpenSlot(program.getId(), Instant.now());
        return next.map(slot -> new NextSlotDto(
            slot.getId(),
            slot.getStartsAt(),
            slot.getPlaceName(),
            slot.getParticipantCount() != null ? slot.getParticipantCount() : 0,
            slot.getMaxParticipants(),
            viewerId != null && slotAudience.participantIds(slot).contains(viewerId)
        )).orElse(null);
    }

    private List<String> myVibes(SlotRecap recap, UUID viewerId) {
        if (viewerId == null) {
            return List.of();
        }
        return vibeVoteRepository.findVibesByRecapIdAndUserId(recap.getId(), viewerId).stream()
            .map(SlotVibe::name)
            .toList();
    }
}
