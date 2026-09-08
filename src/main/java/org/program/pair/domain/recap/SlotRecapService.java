package org.program.pair.domain.recap;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.attendance.Attendance;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotOccurrence;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /**
     * Par où sort {@link SlotRecapOpenedEvent}, et rien d'autre.
     *
     * <p>La carte-souvenir annonce ce qu'elle sait — une carte s'est ouverte —
     * sans connaître ceux qui l'écoutent. C'est ce qui permet au module
     * « affiche » d'en tirer sa notification sans que ce service ait à savoir
     * qu'il existe.
     */
    private final ApplicationEventPublisher events;

    // ————————————————————————— contribution —————————————————————————

    /**
     * Pose — ou remplace — les ambiances de l'appelant.
     *
     * <p>Remplacement et non ajout : le client renvoie l'ensemble de sa
     * sélection à chaque tap, et une liste vide vaut retrait.
     */
    public SlotRecapDto voteVibes(UUID userId, UUID scheduleId, List<String> rawVibes) {
        Schedule slot = loadSlot(scheduleId);
        SlotOccurrence occurrence = requireEndedOccurrence(slot);
        requirePresence(userId, scheduleId, occurrence);
        requireWindowOpen(occurrence);

        Set<SlotVibe> vibes = parseVibes(rawVibes);

        SlotRecap recap = openRecap(userId, slot, occurrence);
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
        SlotOccurrence occurrence = requireEndedOccurrence(slot);
        requirePresence(userId, scheduleId, occurrence);
        requireWindowOpen(occurrence);

        SlotRecap recap = openRecap(userId, slot, occurrence);
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
        SlotOccurrence occurrence = requireEndedOccurrence(slot);
        requirePresence(userId, scheduleId, occurrence);
        requireWindowOpen(occurrence);

        Attendance attendance = attendanceRepository
            .findByScheduleIdAndUserIdAndAttendedAt(scheduleId, userId, occurrence.startsAt())
            .orElseThrow(() -> new ForbiddenException(
                ErrorCode.RECAP_NOT_ATTENDEE, "Vous n'avez pas confirmé votre présence à ce créneau."));

        attendance.setMemoryPhotoUrl(photoUrl == null || photoUrl.isBlank() ? null : photoUrl.strip());
        // Une photo retirée ne peut pas rester publique.
        attendance.setMemoryIsPublic(attendance.getMemoryPhotoUrl() != null && isPublic);
        attendanceRepository.save(attendance);

        return toDto(touch(openRecap(userId, slot, occurrence)), userId);
    }

    // ————————————————————————— hôte —————————————————————————

    /** Le mot de l'hôte. Sanitizé : il est rendu tel quel sur une carte publique. */
    public SlotRecapDto setHostNote(UUID userId, UUID scheduleId, String note) {
        Schedule slot = loadSlot(scheduleId);
        requireHost(userId, slot);
        SlotOccurrence occurrence = requireEndedOccurrence(slot);
        requireWindowOpen(occurrence);

        SlotRecap recap = openRecap(userId, slot, occurrence);
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
        SlotOccurrence occurrence = requireEndedOccurrence(slot);
        requireWindowOpen(occurrence);

        RecapVisibility visibility = parseVisibility(rawVisibility);
        if (visibility.isPublic()
                && !attendanceRepository.existsByScheduleIdAndAttendedAtAndWasPresentTrueAndUserIdNot(
                        scheduleId, occurrence.startsAt(), userId)) {
            throw new ConflictException(ErrorCode.RECAP_NEEDS_ATTENDEE,
                "Attendez qu'au moins une autre personne confirme sa présence pour rendre cette carte publique.");
        }

        SlotRecap recap = openRecap(userId, slot, occurrence);
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
        // Un créneau récurrent porte désormais une carte par séance. « La carte
        // de ce créneau » est donc la plus récente que l'appelant ait le droit
        // de lire — et non la plus récente tout court, qui rendrait 404 sur une
        // séance privée alors qu'une séance publique plus ancienne existe.
        return recapRepository.findByScheduleIdOrderByOccurrenceStartDesc(scheduleId).stream()
            .filter(recap -> isPubliclyListable(recap.getSchedule()))
            .filter(recap -> recap.getVisibility().isPublic()
                || wasInvolved(requesterId, recap.getSchedule()))
            .findFirst()
            .map(recap -> toDto(recap, requesterId))
            .orElseThrow(SlotRecapService::noSuchRecap);
    }

    /**
     * Cartes d'un programme — la page programme.
     *
     * <p>Ni position ni rayon : on regarde CE programme, pas ce qui est autour
     * de soi. La visibilité graduée est portée par la requête, qui la calcule
     * séance par séance.
     */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getForProgram(UUID programId, UUID requesterId) {
        return render(recapRepository.findForProgram(programId, requesterId), requesterId);
    }

    /** Cartes publiques d'une activité du catalogue — la page activité. */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getForActivity(UUID activityId, UUID requesterId) {
        return render(recapRepository.findPublicForActivity(activityId), requesterId);
    }

    /** Cartes publiques des créneaux animés par quelqu'un — son profil. */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getForHost(UUID userId, UUID requesterId) {
        return render(recapRepository.findPublicForHost(userId), requesterId);
    }

    /** Les cartes publiques autour de moi. */
    @Transactional(readOnly = true)
    public List<SlotRecapDto> getFeed(RecapFeedRequest request, UUID requesterId) {
        return render(recapRepository
            .findPublicInRadius(request.lat(), request.lng(), request.radiusMeters(), FEED_LIMIT,
                requesterId), requesterId);
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
        return render(recapRepository.findMine(userId).stream()
            .filter(recap -> isHostActive(recap.getSchedule()))
            .toList(), userId);
    }

    /**
     * Réaligne l'effectif d'une carte après une confirmation de présence.
     *
     * <p>Appelé par la boucle de présence : sans cela, quelqu'un qui confirme
     * après la dernière contribution ne serait jamais compté. Sans effet quand
     * le créneau n'a pas (encore) de carte — on n'en crée pas ici, une carte
     * naît d'une contribution, pas d'une présence.
     */
    public void refreshAttendeeCount(UUID scheduleId, Instant occurrenceStart) {
        recapRepository.findByScheduleIdAndOccurrenceStart(scheduleId, occurrenceStart).ifPresent(recap -> {
            recap.setAttendeeCount(
                attendanceRepository.countPresentByOccurrence(scheduleId, occurrenceStart));
            recapRepository.save(recap);
        });
    }

    // ————————————————————————— garde-fous —————————————————————————

    private Schedule loadSlot(UUID scheduleId) {
        return scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
    }

    /**
     * La séance à laquelle une contribution se rapporte : la dernière
     * terminée.
     *
     * <p>Toute contribution parle d'un moment vécu. Sur un créneau récurrent,
     * la ligne pointe déjà sur la séance suivante — s'y fier attachait la
     * contribution au mauvais moment, quand elle ne la refusait pas.
     */
    private SlotOccurrence requireEndedOccurrence(Schedule slot) {
        SlotOccurrence occurrence = SlotTiming.lastEndedOccurrence(slot, Instant.now());
        if (occurrence == null) {
            // Même refus, et même formulation, que la confirmation de présence :
            // il n'y a rien à raconter d'une séance qui n'a pas eu lieu.
            throw new ValidationException("Ce créneau n'est pas encore terminé.");
        }
        return occurrence;
    }

    /**
     * Présence confirmée sur cette séance — la <b>même</b> vérification que la
     * boucle de recommandation, resserrée sur l'occurrence : être venu la
     * semaine dernière ne donne pas voix au chapitre sur celle-ci.
     */
    private void requirePresence(UUID userId, UUID scheduleId, SlotOccurrence occurrence) {
        if (!attendanceRepository.existsByScheduleIdAndUserIdAndAttendedAtAndWasPresentTrue(
                scheduleId, userId, occurrence.startsAt())) {
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

    private void requireWindowOpen(SlotOccurrence occurrence) {
        if (!Instant.now().isBefore(windowCloseOf(occurrence.endsAt()))) {
            throw new ConflictException(ErrorCode.RECAP_WINDOW_CLOSED,
                "Cette carte est figée : la période de contribution de sept jours est terminée.");
        }
    }

    /**
     * Quand une séance se fige. Compté depuis la <b>fin</b> du moment, pas
     * depuis son début : un créneau long donnerait sinon une fenêtre plus
     * courte qu'un créneau bref.
     */
    private static Instant windowCloseOf(Instant occurrenceEnd) {
        return occurrenceEnd.plus(CONTRIBUTION_WINDOW);
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

    /**
     * Crée la carte de cette séance si c'est la première contribution, la rend
     * sinon.
     *
     * <p>La création — et elle seule — publie {@link SlotRecapOpenedEvent} :
     * c'est l'instant où la séance entre dans {@code /api/recaps/mine}, donc
     * l'instant où une affiche devient calculable pour tous ceux qui y étaient.
     * L'événement est consommé après commit ; il ne part donc pas si la
     * contribution qui l'a provoqué échoue.
     *
     * @param contributorId qui contribue — transmis à l'événement pour n'avoir
     *                      pas à lui annoncer ce qu'il vient de faire
     */
    private SlotRecap openRecap(UUID contributorId, Schedule slot, SlotOccurrence occurrence) {
        return recapRepository
            .findByScheduleIdAndOccurrenceStart(slot.getId(), occurrence.startsAt())
            .orElseGet(() -> {
                SlotRecap recap = new SlotRecap();
                recap.setSchedule(slot);
                recap.setOccurrenceStart(occurrence.startsAt());
                recap.setOccurrenceEnd(occurrence.endsAt());
                recap.setVisibility(RecapVisibility.PRIVATE);
                recap.setAttendeeCount(attendanceRepository.countPresentByOccurrence(
                    slot.getId(), occurrence.startsAt()));
                SlotRecap opened = recapRepository.save(recap);
                events.publishEvent(new SlotRecapOpenedEvent(
                    slot.getId(), occurrence.startsAt(), contributorId));
                return opened;
            });
    }

    /** Réaligne l'effectif et l'horodatage à chaque contribution. */
    private SlotRecap touch(SlotRecap recap) {
        recap.setAttendeeCount(attendanceRepository.countPresentByOccurrence(
            recap.getSchedule().getId(), recap.getOccurrenceStart()));
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
    //
    // Tout ce qui suit obéit à une seule règle, et elle est arrivée tard :
    // AUCUNE lecture ne se fait carte par carte.
    //
    // Le contrat n'a pas bougé d'un champ ; c'est le nombre d'allers-retours
    // qui a changé. GET /recaps/mine coûtait HUIT requêtes par carte — trois
    // pour le profil de l'hôte, une pour les ambiances dominantes, une pour les
    // miennes, une pour les présences, une pour les consentements, une pour la
    // prochaine séance. Sur les 35 cartes d'un compte réel : ~288 requêtes.
    //
    // Ce n'était pas cher en calcul, c'était cher en DISTANCE : la base est à
    // San Francisco et le service en Europe, soit ~200 ms l'aller-retour. 288
    // requêtes font une minute, et le client abandonne à trente secondes — la
    // liste n'arrivait jamais, et le module « affiche » qui en dérive
    // entièrement restait muet sans que rien ne le signale.
    //
    // D'où {@link RenderContext} : tout ce qui se lit pour un LOT de cartes est
    // chargé une fois, avant de composer la première. Les lectures d'une seule
    // carte passent par le même chemin, avec un lot d'un élément — un second
    // chemin de rendu aurait fini par diverger du premier, et c'est le rendu
    // qui porte les règles de confidentialité.

    /** Un lot de cartes, rendu en un nombre de requêtes qui ne dépend pas de sa taille. */
    private List<SlotRecapDto> render(List<SlotRecap> recaps, UUID viewerId) {
        RenderContext context = new RenderContext(recaps, viewerId);
        return recaps.stream().map(recap -> toDto(recap, viewerId, context)).toList();
    }

    /** Une carte seule — le même rendu, sur un lot d'un élément. */
    private SlotRecapDto toDto(SlotRecap recap, UUID viewerId) {
        List<SlotRecap> lot = List.of(recap);
        return toDto(recap, viewerId, new RenderContext(lot, viewerId));
    }

    private SlotRecapDto toDto(SlotRecap recap, UUID viewerId, RenderContext context) {
        Schedule slot = recap.getSchedule();
        Program program = slot.getProgram();
        UserActivity userActivity = program != null ? program.getUserActivity() : null;
        Activity activity = userActivity != null ? userActivity.getActivity() : null;
        Category category = activity != null ? activity.getCategory() : null;
        User host = hostOf(slot);

        Instant windowClosesAt = windowCloseOf(recap.getOccurrenceEnd());
        boolean windowOpen = Instant.now().isBefore(windowClosesAt);

        List<Attendance> presences = context.presences(recap);

        boolean canContribute = windowOpen
            && viewerId != null
            && presences.stream().anyMatch(a -> viewerId.equals(a.getUser().getId()));

        return new SlotRecapDto(
            slot.getId(),
            program != null ? program.getTitle() : null,
            activity != null ? activity.getName() : null,
            category != null ? category.getName() : null,
            category != null ? category.getColorRamp() : null,
            recap.getOccurrenceStart(),
            // Figée à la naissance de la carte, comme la fenêtre qui en découle :
            // relire la ligne de créneau daterait ce souvenir de la séance à venir.
            recap.getOccurrenceEnd(),
            slot.getPlaceName(),
            slot.getCity(),
            recap.getAttendeeCount() != null ? recap.getAttendeeCount() : 0,
            context.topVibes(recap),
            publicPhotos(presences),
            recap.getHostNote(),
            host != null ? context.profile(host.getId()) : null,
            visibleAttendees(recap, host, presences, context),
            context.nextSlot(program),
            recap.getVisibility().name(),
            canContribute,
            // Nulle une fois la fenêtre refermée : il n'y a plus de délai à
            // annoncer, et une date passée serait affichée comme un compte à
            // rebours négatif.
            windowOpen ? windowClosesAt : null,
            context.myVibes(recap)
        );
    }

    /**
     * Trois photos au maximum, et uniquement celles que leur auteur a rendues
     * publiques. Le plafond est ici et pas seulement à l'écran : appliqué
     * seulement côté client, il laisserait passer des images que personne n'a
     * accepté de publier.
     */
    private static List<String> publicPhotos(List<Attendance> presences) {
        return presences.stream()
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
    private List<UserPublicDto> visibleAttendees(SlotRecap recap, User host,
                                                 List<Attendance> presences,
                                                 RenderContext context) {
        List<UUID> consenting = context.consenting(recap);
        if (consenting.isEmpty()) {
            return List.of();
        }
        UUID hostId = host != null ? host.getId() : null;

        List<UserPublicDto> visible = new ArrayList<>();
        for (Attendance attendance : presences) {
            UUID attendeeId = attendance.getUser().getId();
            if (attendeeId.equals(hostId) || !consenting.contains(attendeeId)) {
                continue;
            }
            if (!Boolean.TRUE.equals(attendance.getUser().getIsActive())) {
                continue;
            }
            visible.add(context.profile(attendeeId));
        }
        return visible;
    }

    /**
     * Tout ce qu'un lot de cartes doit lire, chargé une fois.
     *
     * <p>Trois requêtes groupées à la construction — ambiances, mes ambiances,
     * consentements — plus une pour les présences. Le profil d'une personne et
     * la prochaine séance d'un programme se résolvent à la demande, mais
     * <b>une seule fois chacun</b> : trois hôtes pour trente-cinq cartes, c'est
     * trois profils, pas trente-cinq.
     *
     * <p>Le lecteur est fixé pour tout le contexte, et c'est ce qui autorise ces
     * mémorisations : {@code subscribed} sur un profil et {@code alreadyJoined}
     * sur une prochaine séance dépendent de lui. Un contexte réutilisé d'un
     * lecteur à l'autre rendrait à l'un les relations de l'autre — c'est pour
     * cela qu'il naît et meurt avec l'appel, et n'est jamais un champ du
     * service.
     */
    private final class RenderContext {

        private final UUID viewerId;
        private final Map<UUID, List<VibeCountDto>> topVibes = new HashMap<>();
        private final Map<UUID, List<String>> myVibes = new HashMap<>();
        private final Map<UUID, List<UUID>> consenting = new HashMap<>();
        private final Map<Occurrence, List<Attendance>> presences = new HashMap<>();
        private final Map<UUID, UserPublicDto> profiles = new HashMap<>();
        private final Map<UUID, Optional<NextSlotDto>> nextSlots = new HashMap<>();

        /** La séance d'une carte : le couple qui identifie une présence. */
        private record Occurrence(UUID scheduleId, Instant startsAt) {}

        RenderContext(List<SlotRecap> recaps, UUID viewerId) {
            this.viewerId = viewerId;
            if (recaps.isEmpty()) {
                return;
            }

            List<UUID> recapIds = recaps.stream().map(SlotRecap::getId).toList();

            // Les ambiances dominantes arrivent triées par carte puis par
            // décompte : le plafond de trois se prend donc en tête de chaque
            // groupe, exactement comme le faisait la requête par carte.
            for (Object[] row : vibeVoteRepository.countByVibeForRecaps(recapIds)) {
                List<VibeCountDto> pourLaCarte =
                    topVibes.computeIfAbsent((UUID) row[0], k -> new ArrayList<>());
                if (pourLaCarte.size() < MAX_TOP_VIBES) {
                    pourLaCarte.add(new VibeCountDto(
                        ((SlotVibe) row[1]).name(), ((Number) row[2]).intValue()));
                }
            }

            if (viewerId != null) {
                for (Object[] row : vibeVoteRepository.findVibesByRecapIdsAndUserId(recapIds, viewerId)) {
                    myVibes.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                        .add(((SlotVibe) row[1]).name());
                }
            }

            for (Object[] row : consentRepository.findConsentingByRecapIds(recapIds)) {
                consenting.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add((UUID) row[1]);
            }

            // Les deux bornes sont croisées et non appariées : la requête peut
            // ramener la présence d'une séance qu'aucune carte du lot ne
            // demande. On réapparie donc ici, sur le couple exact — une carte
            // ne doit jamais hériter des présents d'une autre semaine.
            Set<UUID> scheduleIds = new LinkedHashSet<>();
            Set<Instant> starts = new LinkedHashSet<>();
            for (SlotRecap recap : recaps) {
                scheduleIds.add(recap.getSchedule().getId());
                starts.add(recap.getOccurrenceStart());
            }
            for (Attendance attendance : attendanceRepository
                    .findPresentForOccurrences(scheduleIds, starts)) {
                presences.computeIfAbsent(
                        new Occurrence(attendance.getSchedule().getId(), attendance.getAttendedAt()),
                        k -> new ArrayList<>())
                    .add(attendance);
            }

            // Les profils en dernier : on ne sait quels participants sont
            // nommables qu'une fois les consentements lus. Hôtes et participants
            // nommés partent ensemble — c'est le même lecteur, donc le même
            // calcul d'abonnement, et les séparer coûterait deux fois trois
            // requêtes pour rien.
            Set<UUID> aResoudre = new LinkedHashSet<>();
            for (SlotRecap recap : recaps) {
                User host = hostOf(recap.getSchedule());
                if (host != null) {
                    aResoudre.add(host.getId());
                }
            }
            consenting.values().forEach(aResoudre::addAll);
            profiles.putAll(userService.getPublicProfiles(aResoudre, viewerId));

            // La prochaine séance : une question par PROGRAMME, posée une fois
            // pour tous. Puis une seule interrogation d'audience pour savoir
            // lesquelles je rejoins déjà — la version unitaire en coûtait deux
            // par créneau.
            Set<UUID> programIds = new LinkedHashSet<>();
            for (SlotRecap recap : recaps) {
                Program program = recap.getSchedule().getProgram();
                if (program != null) {
                    programIds.add(program.getId());
                }
            }
            if (!programIds.isEmpty()) {
                List<Schedule> prochaines =
                    scheduleRepository.findNextOpenSlots(programIds, Instant.now());
                Set<UUID> rejointes = slotAudience.slotsWhereParticipant(viewerId, prochaines);
                for (Schedule prochaine : prochaines) {
                    nextSlots.put(prochaine.getProgram().getId(), Optional.of(new NextSlotDto(
                        prochaine.getId(),
                        prochaine.getStartsAt(),
                        prochaine.getPlaceName(),
                        prochaine.getParticipantCount() != null ? prochaine.getParticipantCount() : 0,
                        prochaine.getMaxParticipants(),
                        rejointes.contains(prochaine.getId()))));
                }
                // Un programme sans séance à venir : la réponse est « aucune »,
                // et elle doit être mémorisée comme telle, sans quoi le repli
                // unitaire la redemanderait carte par carte.
                for (UUID programId : programIds) {
                    nextSlots.putIfAbsent(programId, Optional.empty());
                }
            }
        }

        List<VibeCountDto> topVibes(SlotRecap recap) {
            return topVibes.getOrDefault(recap.getId(), List.of());
        }

        List<String> myVibes(SlotRecap recap) {
            return myVibes.getOrDefault(recap.getId(), List.of());
        }

        List<UUID> consenting(SlotRecap recap) {
            return consenting.getOrDefault(recap.getId(), List.of());
        }

        List<Attendance> presences(SlotRecap recap) {
            return presences.getOrDefault(
                new Occurrence(recap.getSchedule().getId(), recap.getOccurrenceStart()), List.of());
        }

        /**
         * Le profil public de quelqu'un, résolu une fois par personne et par lot.
         *
         * <p>Le repli unitaire n'est pas mort : la lecture groupée <b>omet</b>
         * les comptes inconnus ou désactivés, là où la variante unitaire lève.
         * Repasser par elle conserve donc la décision d'erreur telle qu'elle
         * était — un profil manquant ne doit pas devenir un profil vide au
         * détour d'une optimisation.
         */
        UserPublicDto profile(UUID userId) {
            return profiles.computeIfAbsent(userId, id -> userService.getPublicProfile(id, viewerId));
        }

        /**
         * La prochaine séance ouverte du même programme — le champ qui convertit
         * un lecteur en participant. Nulle franchement quand il n'y en a pas.
         *
         * <p>Résolue une fois par PROGRAMME : trente-cinq cartes d'un cours
         * hebdomadaire ne posent qu'une seule fois la question « et la
         * prochaine ? ».
         */
        NextSlotDto nextSlot(Program program) {
            if (program == null) {
                return null;
            }
            return nextSlots.computeIfAbsent(program.getId(), programId ->
                    scheduleRepository.findNextOpenSlot(programId, Instant.now())
                        .map(slot -> new NextSlotDto(
                            slot.getId(),
                            slot.getStartsAt(),
                            slot.getPlaceName(),
                            slot.getParticipantCount() != null ? slot.getParticipantCount() : 0,
                            slot.getMaxParticipants(),
                            viewerId != null && slotAudience.participantIds(slot).contains(viewerId))))
                .orElse(null);
        }
    }
}
