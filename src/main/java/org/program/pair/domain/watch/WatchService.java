package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.domain.guardian.Guardian;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.user.User;
import org.program.pair.domain.watch.dto.CreateWatchRequest;
import org.program.pair.domain.watch.dto.WatchDetailDto;
import org.program.pair.domain.watch.dto.WatchDto;
import org.program.pair.domain.watch.dto.WatchEventDto;
import org.program.pair.domain.watch.dto.WatchHistoryDto;
import org.program.pair.domain.watch.dto.ArrivalRequest;
import org.program.pair.domain.watch.dto.ArrivalResponse;
import org.program.pair.domain.watch.dto.CloseRequest;
import org.program.pair.domain.watch.dto.InterruptRequest;
import org.program.pair.domain.watch.dto.ResendCodeRequest;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.ReturnCodeRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.Pepper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Les veilles retour : les armer, les lister, les lire, les désarmer avant départ.
 *
 * <p>C'est la priorité 2 du lot — « l'écran de veille armée et le miroir ». Elle
 * s'appuie sur la priorité 1 : <b>on n'arme rien sans contact accepté.</b> Les
 * gestes qui suivent l'armement — valider l'arrivée, générer le code, refermer,
 * les minuteurs — appartiennent aux priorités suivantes ; ici, on pose la veille
 * et on la relit.
 *
 * <p>Deux décisions structurent l'armement, et toutes deux viennent de notre
 * échange avec le chantier mobile.
 *
 * <p><b>L'échéance est figée maintenant.</b> Par défaut, la fin du créneau plus
 * une heure ; l'utilisateur peut la déplacer. Une fois posée, elle ne se redérive
 * plus du créneau — sans quoi le rollover d'un créneau récurrent la ferait fuir
 * devant elle. C'est {@code SlotTiming.endOf} qui donne la fin, et il gère déjà le
 * cas d'un créneau sans {@code ends_at} (fin conventionnelle à {@code starts_at + 2 h}).
 *
 * <p><b>Une seule veille vivante par créneau.</b> En armer une seconde pendant que
 * la première tourne dédoublerait rappels et alertes. La règle est tenue par le
 * service et doublée par un index unique partiel en base.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class WatchService {

    /** L'échéance par défaut : la fin du créneau, plus cette marge. */
    private static final Duration MARGE_RETOUR = Duration.ofHours(1);

    private final WatchRepository watchRepository;
    private final WatchEventRepository eventRepository;
    private final GuardianRepository guardianRepository;
    private final ScheduleRepository scheduleRepository;
    private final ReturnCodeRepository returnCodeRepository;
    private final SlotAudience slotAudience;
    private final Pepper pepper;
    private final WatchEscalationService escalation;
    private final UserRepository userRepository;
    private final org.program.pair.repository.OutboxMessageRepository outboxRepository;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    /** Trajet de retour par défaut, et ses bornes de bon sens (§7.5). */
    /**
     * Combien de temps une non-arrivée reste rendue par « mes veilles actives »
     * après sa clôture. Elle est terminale, mais c'est le seul endroit où la
     * personne concernée l'apprend — voir {@link #listActive}.
     */
    private static final Duration NON_ARRIVEE_VISIBLE = Duration.ofHours(24);

    private static final int TRAJET_DEFAUT_MIN = 45;
    private static final int TRAJET_MIN = 15;
    private static final int TRAJET_MAX = 240;

    // -------------------------------------------------------------------- armer

    public WatchDto arm(UUID userId, CreateWatchRequest req) {
        Schedule slot = scheduleRepository.findById(req.scheduleId())
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        // Un créneau auquel on n'est pas inscrit est introuvable, pas interdit —
        // même règle que le partage de sécurité : ne pas révéler son existence à
        // qui essaie des identifiants.
        if (!slotAudience.participantIds(slot).contains(userId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        exigerContactAccepte(userId, req.guardianId());
        if (req.backupGuardianId() != null && !req.backupGuardianId().equals(req.guardianId())) {
            exigerContactAccepte(userId, req.backupGuardianId());
        }

        if (watchRepository.existsByUserIdAndScheduleIdAndStateNotIn(
                userId, req.scheduleId(), WatchState.TERMINAUX)) {
            throw new ConflictException(ErrorCode.WATCH_ALREADY_ACTIVE,
                "Une veille est déjà en cours pour ce créneau.");
        }

        Instant now = Instant.now();
        Instant deadline = req.deadlineAt() != null
            ? req.deadlineAt()
            : SlotTiming.endOf(slot).plus(MARGE_RETOUR);
        if (!deadline.isAfter(now)) {
            throw new BusinessException(ErrorCode.WATCH_DEADLINE_PAST,
                "L'heure limite de retour est déjà passée.");
        }

        // Le début de l'occurrence est figé maintenant, comme l'échéance : la
        // boucle aller s'y appuie, et il ne doit pas fuir avec le rollover.
        Instant occurrenceStart = org.program.pair.domain.program.SlotTiming
            .currentOccurrence(slot).startsAt();

        Watch watch = watchRepository.save(Watch.builder()
            .scheduleId(req.scheduleId())
            .userId(userId)
            .state(WatchState.ARMED)
            .armedAt(now)
            .deadlineAt(deadline)
            .remindersSent(0)
            .occurrenceStartsAt(occurrenceStart)
            .outboundBaseAt(occurrenceStart)
            .arrivalPromptsSent(0)
            .guardianId(req.guardianId())
            .backupGuardianId(req.backupGuardianId())
            .build());

        inscrire(watch.getId(), WatchEventType.ARMED, now);
        return dto(watch);
    }

    private void exigerContactAccepte(UUID userId, UUID guardianId) {
        Guardian guardian = guardianRepository.findByIdAndOwnerId(guardianId, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WATCH_GUARDIAN_NOT_ACCEPTED,
                "Ce contact n'est pas l'un de vos contacts d'urgence."));
        if (guardian.getConsentState() != ConsentState.ACCEPTED) {
            throw new BusinessException(ErrorCode.WATCH_GUARDIAN_NOT_ACCEPTED,
                "Ce contact n'a pas encore accepté d'être prévenu.");
        }
    }

    // ------------------------------------------------------------------- lire

    /**
     * « Mes veilles actives » : les veilles vivantes, plus les non-arrivées
     * refermées depuis moins de 24 h.
     *
     * <p>Une non-arrivée est terminale et n'aurait donc rien à faire ici — sauf que
     * cette liste est le seul endroit où la personne concernée apprend que sa soirée
     * a été classée perdue en chemin. Après T+45, l'organisateur est prévenu par une
     * notification ; elle, rien ne le lui dirait. Vingt-quatre heures suffisent :
     * passé ce délai la veille ne vit plus que dans le journal, qui est sa place.
     *
     * <p>La ligne rendue porte {@code closedAt} non nul et {@code alertDelivery} à
     * {@code NONE} — aucun message n'est parti — et {@code publicToken} nul :
     * aucun jeton n'est créé sur cette branche.
     */
    @Transactional(readOnly = true)
    public List<WatchDto> listActive(UUID userId) {
        return watchRepository.findActivesEtNonArriveesRecentes(
                userId, WatchState.TERMINAUX, WatchState.NOT_ARRIVED,
                Instant.now().minus(NON_ARRIVEE_VISIBLE))
            .stream().map(this::dto).toList();
    }

    @Transactional(readOnly = true)
    public WatchDetailDto detail(UUID userId, UUID watchId) {
        Watch watch = watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));
        List<WatchEventDto> timeline = eventRepository.findByWatchIdOrderByOccurredAtAsc(watchId)
            .stream().map(WatchEventDto::from).toList();
        // Un seul calcul de remise, partagé entre l'objet watch et le champ de tête
        // (que le contrat porte depuis le 01/09, et que le client lit là).
        String delivery = deliveryOf(watchId);
        return new WatchDetailDto(WatchDto.from(watch, publicBaseUrl, delivery), timeline, delivery,
            retoursConfirmesDAffilee(userId));
    }

    /**
     * Combien de retours ont été confirmés d'affilée, le dernier compris.
     *
     * <p>C'est la seule chose que l'écran de fin de cycle ait à récompenser, et
     * c'est ce qui décide qu'on réarme une veille la fois suivante. Une
     * fonctionnalité de sécurité qu'on cesse d'armer ne protège plus personne :
     * ce compteur est du produit, pas de la décoration.
     *
     * <p><b>Trois issues, et une seule rompt.</b> En remontant des veilles les plus
     * récentes aux plus anciennes :
     *
     * <ul>
     *   <li>refermée par un code ({@code CLOSED_BY_CODE}) — elle compte ;</li>
     *   <li>mal finie sans code — escalade, abandon, perdue en chemin — la série
     *       s'arrête là ;</li>
     *   <li>tout le reste — désarmée avant le départ, encore en cours — n'est
     *       ni comptée ni rompante. Il n'y avait pas de retour à confirmer, ou
     *       il n'est pas encore dû ; le compter contre la personne serait faux.</li>
     * </ul>
     *
     * <p><b>Une clôture sous contrainte compte comme un retour confirmé</b>, et ce
     * n'est pas un oubli. Elle écrit le même {@code CLOSED_BY_CODE} qu'une clôture
     * normale ; le premier critère la retient donc avant que son escalade n'entre
     * en jeu. Toute autre règle ferait afficher un nombre différent au moment
     * précis où l'écran est regardé par quelqu'un d'autre — c'est la clause
     * d'indistinguabilité, appliquée à un compteur.
     *
     * <p>Ce n'est pas {@code PracticeStatsDto.currentStreakWeeks}, qui compte des
     * semaines de pratique et n'a rien à voir avec des retours.
     */
    @Transactional(readOnly = true)
    public int retoursConfirmesDAffilee(UUID userId) {
        int serie = 0;
        for (Object[] issue : watchRepository.issuesDesVeilles(userId)) {
            boolean confirme = Boolean.TRUE.equals(issue[1]);
            boolean rompu = Boolean.TRUE.equals(issue[2]);
            if (confirme) {
                serie++;
            } else if (rompu) {
                break;
            }
        }
        return serie;
    }

    /** Les veilles terminées de l'appelant, sans coordonnées : le journal. */
    @Transactional(readOnly = true)
    public List<WatchHistoryDto> history(UUID userId) {
        return watchRepository.findByUserIdAndStateInOrderByArmedAtDesc(userId, WatchState.TERMINAUX)
            .stream().map(this::historyDto).toList();
    }

    private WatchHistoryDto historyDto(Watch w) {
        Schedule slot = scheduleRepository.findById(w.getScheduleId()).orElse(null);
        String titre = null;
        if (slot != null) {
            try {
                titre = slot.getProgram().getUserActivity().getActivity().getName();
            } catch (RuntimeException ignore) {
                // Programme ou activité non chargés : le journal se passe du titre.
            }
        }
        return new WatchHistoryDto(
            w.getId(), w.getState().name(), titre,
            slot != null ? slot.getPlaceName() : null,
            slot != null ? slot.getCity() : null,
            w.getOccurrenceStartsAt(), w.getClosedAt(),
            // Une veille dont le lien public est né a vu une alerte partir.
            w.getPublicToken() != null);
    }

    /**
     * L'état de remise des alertes d'une veille, agrégé depuis l'outbox.
     *
     * <p>Avec un seul canal — l'e-mail, tant que le SMS est éteint — l'app a besoin
     * de savoir si le message est <b>parti</b>, pour ne pas laisser croire qu'un
     * proche a été prévenu quand l'adresse est en faute. On rend le pire état du
     * lot : {@code FAILED} l'emporte, puis {@code PENDING}, {@code SENT} sinon, et
     * {@code NONE} si aucune alerte n'a été déposée. Ce n'est pas encore le
     * « délivré » plein — les rebonds demanderaient les webhooks du fournisseur —
     * mais c'est le retour qui manquait.
     */
    private String deliveryOf(UUID watchId) {
        List<org.program.pair.domain.outbox.OutboxMessage> messages =
            outboxRepository.findByWatchId(watchId);
        if (messages.isEmpty()) {
            return "NONE";
        }
        // Le rebond prime sur tout : c'est le fait qui dit que le proche n'a pas
        // reçu, et avec un seul canal c'est celui qu'il fallait pouvoir voir.
        if (messages.stream().anyMatch(m ->
                m.getDeliveryState() == org.program.pair.domain.outbox.OutboxDelivery.BOUNCED
             || m.getDeliveryState() == org.program.pair.domain.outbox.OutboxDelivery.COMPLAINED)) {
            return "BOUNCED";
        }
        if (messages.stream().anyMatch(m ->
                m.getStatus() == org.program.pair.domain.outbox.OutboxStatus.FAILED)) {
            return "FAILED";
        }
        // Un accusé « délivré » est le meilleur retour possible : au moins un
        // message est arrivé.
        if (messages.stream().anyMatch(m ->
                m.getDeliveryState() == org.program.pair.domain.outbox.OutboxDelivery.DELIVERED)) {
            return "DELIVERED";
        }
        boolean toutParti = messages.stream().allMatch(m ->
            m.getStatus() == org.program.pair.domain.outbox.OutboxStatus.SENT);
        return toutParti ? "SENT" : "PENDING";
    }

    // ------------------------------------------------- trajet aller (organisateur)

    /**
     * « Je la vois, elle est là » — l'organisateur repousse la relance d'arrivée
     * de 15 min.
     *
     * <p>Un verbe à part de {@code still-coming}, qui appartient à l'intéressée sur
     * sa propre veille : le faire appeler par l'organisateur le ferait agir sous
     * l'identité de quelqu'un d'autre. L'organisateur <b>ne valide pas</b> l'arrivée
     * et ne crée aucun code — il gagne du temps, rien de plus.
     *
     * <p>Autorisé au seul organisateur du créneau. Une veille qui n'est pas la
     * sienne, ou qu'il n'organise pas, lui est <b>introuvable</b> (404), pas
     * interdite.
     */
    public void seenByHost(UUID hostId, UUID watchId) {
        Watch watch = watchRepository.findById(watchId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));
        Schedule slot = scheduleRepository.findById(watch.getScheduleId())
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));
        if (!hostId.equals(organisateurDe(slot))) {
            throw new ResourceNotFoundException("Veille introuvable.");
        }
        if (watch.getState() != WatchState.ARMED && watch.getState() != WatchState.EN_ROUTE) {
            throw new ConflictException(ErrorCode.WATCH_NOT_OUTBOUND,
                "Cette veille n'est plus sur le trajet aller.");
        }

        Instant base = watch.getOutboundBaseAt() != null
            ? watch.getOutboundBaseAt() : watch.getArmedAt();
        watch.setOutboundBaseAt(base.plus(Duration.ofMinutes(15)));
        inscrire(watchId, WatchEventType.SEEN_BY_HOST, Instant.now());
    }

    /**
     * Les inscrits qu'un organisateur attend encore sur son créneau.
     *
     * <p>Réservé à l'organisateur : un créneau qu'il n'organise pas — ou qui
     * n'existe pas — lui est <b>introuvable</b> (404), jamais interdit. La liste ne
     * porte que le nom, la veille et l'heure d'attente ; rien de ce que
     * l'organisateur n'a pas à voir (décision 15).
     */
    @Transactional(readOnly = true)
    public List<org.program.pair.domain.watch.dto.PendingArrivalDto> pendingArrivals(UUID hostId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
        if (!hostId.equals(organisateurDe(slot))) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }
        return watchRepository.findByScheduleIdAndStateIn(scheduleId,
                List.of(WatchState.ARMED, WatchState.EN_ROUTE)).stream()
            .map(w -> new org.program.pair.domain.watch.dto.PendingArrivalDto(
                w.getId(),
                org.program.pair.domain.user.GivenName.from(displayNameDe(w.getUserId())),
                w.getOccurrenceStartsAt()))
            .toList();
    }

    private String displayNameDe(UUID userId) {
        return userRepository.findById(userId).map(User::getDisplayName).orElse("Un inscrit");
    }

    private static UUID organisateurDe(Schedule slot) {
        try {
            return slot.getProgram().getUserActivity().getUser().getId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------- trajet aller

    /**
     * « Je suis en chemin » : repousse la relance d'arrivée de quinze minutes.
     *
     * <p>Ne vaut que sur le trajet aller, avant l'arrivée. La base des demandes
     * « tu y es ? » est décalée d'un quart d'heure, ce qui rachète autant de temps
     * avant la demande suivante — un métro en retard, une place de parking.
     */
    public WatchDto stillComing(UUID userId, UUID watchId) {
        Watch watch = exigerVeille(userId, watchId);
        exigerTrajetAller(watch);

        Instant base = watch.getOutboundBaseAt() != null
            ? watch.getOutboundBaseAt() : watch.getArmedAt();
        watch.setOutboundBaseAt(base.plus(Duration.ofMinutes(15)));
        watch.setState(WatchState.EN_ROUTE);
        inscrire(watchId, WatchEventType.STILL_COMING, Instant.now());
        return dto(watch);
    }

    /**
     * « Je n'y vais pas » : désarme sans message et <b>sans compter d'absence</b>.
     *
     * <p>Se décommander à l'avance n'est pas manquer à sa parole. Aucune ligne
     * {@code Attendance} n'est écrite, aucun contact n'est prévenu : la veille se
     * referme, un point c'est tout.
     *
     * <p><b>Accepté aussi sur une veille escaladée sans arrivée validée</b>, et
     * c'est une porte de secours, pas un élargissement de confort. Une veille
     * arrivée là par l'ancienne boucle aller n'avait <b>aucune sortie</b> : l'arrivée
     * est refusée (l'état n'est plus en attente d'arrivée), le snooze et
     * l'interruption supposent d'être sur place, le désarmement ne vaut qu'en
     * {@code ARMED}, et la clôture réclame un code qui n'a jamais existé. La veille
     * restait ouverte indéfiniment et bloquait l'armement d'une nouvelle sur le même
     * créneau. Ces veilles-là existent en production ; {@link WatchState#NOT_ARRIVED}
     * empêche qu'il s'en crée d'autres, il ne libère pas celles qui y sont.
     *
     * <p><b>Elle se referme en {@code NOT_ARRIVED}, jamais en {@code CLOSED}.</b>
     * Ces veilles ont un jeton public distribué — l'ancienne branche en créait un —
     * et {@code CLOSED} est terminal : la page publique dirait « Bien rentrée » au
     * proche de quelqu'un qui n'est jamais arrivé.
     *
     * <p><b>Si une alerte était réellement partie, un message de renoncement part.</b>
     * C'est la règle que le module applique déjà à la clôture par code, et elle vaut
     * ici pour la même raison : ces veilles ont fait partir le message ⑤, et un
     * proche prévenu que quelqu'un n'est pas arrivé doit apprendre que c'est fini.
     * Sans cela il resterait sur la dernière chose qu'on lui a dite.
     *
     * <p><b>Le gabarit ⑦, jamais ③.</b> La levée dit « vient de confirmer son
     * retour » — écrite pour la boucle retour, elle est fausse de quelqu'un qui n'est
     * jamais parti. ⑦ dit qu'elle a renoncé, qu'il n'y a plus lieu de s'inquiéter, et
     * que le message précédent est sans objet.
     *
     * <p><b>Une asymétrie assumée, écrite ici pour qu'on la trouve plutôt qu'on la
     * corrige.</b> C'est la seule surface du module où <b>un geste éteint une alerte
     * déjà partie</b>, sans code ni vérification : refermer après une arrivée exige
     * les cinq caractères du code de retour, qui prouve que c'est bien la personne
     * qui referme, et connaît une variante sous contrainte. Ici, un appel suffit.
     *
     * <p>Ce n'est pas un oubli, et les trois remèdes sont pires. Demander un code
     * est impossible : sans arrivée validée il n'en existe aucun — c'est la
     * définition de cette branche — donc aucun code de contrainte non plus. Exiger
     * le mot de passe, comme le renvoi de code, poserait une porte de plus sur la
     * même pièce et découragerait le geste au moment précis où il doit être facile.
     * Et ne rien accepter était l'impasse qu'on vient de refermer : une veille
     * escaladée sans arrivée n'avait aucune sortie, et bloquait son créneau.
     *
     * <p>Le cas se raréfie de lui-même : il faut qu'une alerte soit sortie sur une
     * veille sans arrivée, ce que {@link WatchState#NOT_ARRIVED} rend impossible aux
     * veilles neuves. Il ne reste que les héritées.
     */
    public WatchDto abandon(UUID userId, UUID watchId) {
        Watch watch = exigerVeille(userId, watchId);
        boolean escaladeSansArrivee = watch.getState() == WatchState.ESCALATED
            && watch.getArrivalConfirmedAt() == null;
        if (!escaladeSansArrivee) {
            exigerTrajetAller(watch);
        }

        Instant now = Instant.now();
        if (escaladeSansArrivee) {
            if (!outboxRepository.findByWatchId(watchId).isEmpty()) {
                escalation.sendRenoncement(watch);
            }
            watch.setState(WatchState.NOT_ARRIVED);
        } else {
            watch.setState(WatchState.CLOSED);
        }
        watch.setClosedAt(now);
        inscrire(watchId, WatchEventType.ABANDONED, now);
        return dto(watch);
    }

    /** Révoque le lien public : la page devient introuvable, même avant son expiration. */
    public void revokePublicLink(UUID userId, UUID watchId) {
        Watch watch = exigerVeille(userId, watchId);
        if (watch.getPublicTokenRevokedAt() == null) {
            watch.setPublicTokenRevokedAt(Instant.now());
        }
    }

    private Watch exigerVeille(UUID userId, UUID watchId) {
        return watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));
    }

    private void exigerTrajetAller(Watch watch) {
        if (watch.getState() != WatchState.ARMED && watch.getState() != WatchState.EN_ROUTE) {
            throw new ConflictException(ErrorCode.WATCH_NOT_OUTBOUND,
                "Ce geste ne vaut que sur le trajet aller, avant l'arrivée.");
        }
    }

    // --------------------------------------------------------------- désarmer

    /**
     * Désarmement avant départ : rien n'est parti, aucun message, aucune absence
     * comptée. N'est possible que tant que la veille est encore {@code ARMED} —
     * dès qu'un départ ou une arrivée a eu lieu, on la referme par les sorties
     * prévues des priorités suivantes, pas par ce geste.
     */
    public void disarm(UUID userId, UUID watchId) {
        Watch watch = watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));

        if (watch.getState() != WatchState.ARMED) {
            throw new ConflictException(ErrorCode.WATCH_NOT_DISARMABLE,
                "Cette veille ne peut plus être désarmée d'un simple geste.");
        }

        Instant now = Instant.now();
        watch.setState(WatchState.CLOSED);
        watch.setClosedAt(now);
        inscrire(watch.getId(), WatchEventType.DISARMED_BEFORE_DEPARTURE, now);
    }

    // ------------------------------------------------------------------ arrivée

    /**
     * Valide l'arrivée sur place et crée le code de retour.
     *
     * <p>Le code est tiré, son empreinte stockée sous le poivre, et le code en
     * clair rendu <b>une seule fois</b> dans la réponse — jamais reservi. Si
     * l'utilisateur a fourni un code de contrainte, son empreinte est stockée sous
     * le même sel et la même version de clé.
     */
    public ArrivalResponse arrival(UUID userId, UUID watchId, ArrivalRequest req) {
        Watch watch = watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));

        if (watch.getState() != WatchState.ARMED && watch.getState() != WatchState.EN_ROUTE) {
            throw new BusinessException(ErrorCode.WATCH_ARRIVAL_NOT_EXPECTED,
                "L'arrivée ne peut être validée que sur une veille en attente d'arrivée.");
        }

        String code = ReturnCodeGenerator.next();
        byte[] sel = pepper.nouveauSel();
        int kv = pepper.versionCourante();
        String selB64 = Base64.getEncoder().encodeToString(sel);

        String duressHash = (req != null && req.duressCode() != null && !req.duressCode().isBlank())
            ? pepper.empreinte(req.duressCode().strip(), sel, kv)
            : null;

        returnCodeRepository.save(new ReturnCode(
            watchId, pepper.empreinte(code, sel, kv), selB64, kv, 3, duressHash));

        Instant now = Instant.now();
        watch.setState(WatchState.ON_SITE);
        watch.setArrivalConfirmedAt(now);
        inscrire(watchId, WatchEventType.ARRIVED_ON_SITE, now);

        return new ArrivalResponse(dto(watch), code);
    }

    // ------------------------------------------------------------------ clôture

    /**
     * Referme une veille par son code.
     *
     * <p><b>Le code de contrainte répond exactement comme un succès.</b> Les deux
     * empreintes — le code normal et le code de contrainte — sont évaluées
     * <b>systématiquement</b>, y compris quand la première correspond : un
     * court-circuit rendrait un code normal en un temps et un code de contrainte
     * en un autre, et ce seul écart trahirait ce que la fonctionnalité existe pour
     * cacher. C'est peu coûteux parce que le poivre est un HMAC (quelques
     * microsecondes) ; avec un bcrypt, cette discipline aurait été intenable. La
     * fusion se fait en temps constant, sans {@code return} anticipé.
     *
     * <p>Corps, code HTTP et travail effectué sont identiques dans les deux cas de
     * succès. Ce qui diffère est l'état où passe la veille — {@code CLOSED} ou
     * {@code ESCALATED} — invisible dans la réponse de clôture, et que le client
     * sait ne pas montrer sous contrainte.
     *
     * <p>{@code enteredAt} fait foi : c'est lui qui date la clôture, pas l'heure de
     * réception.
     */
    public CloseOutcome close(UUID userId, UUID watchId, CloseRequest req) {
        Watch watch = watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));

        ReturnCode rc = returnCodeRepository.findByWatchId(watchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WATCH_NO_CODE_TO_CLOSE,
                "Cette veille n'attend pas de code : aucune arrivée n'a été validée."));

        if (rc.getAttemptsLeft() <= 0) {
            return new CloseOutcome(CloseStatus.LOCKED, 0);
        }

        byte[] sel = Base64.getDecoder().decode(rc.getSalt());
        String saisi = req.code().strip();

        // Les deux empreintes, toujours. correspond() calcule même face à une
        // empreinte nulle (pas de code de contrainte défini), pour que le temps
        // de réponse ne distingue pas non plus les veilles qui en ont un.
        boolean okNormal = pepper.correspond(saisi, sel, rc.getKeyVersion(), rc.getHash());
        boolean okDuress = pepper.correspond(saisi, sel, rc.getKeyVersion(), rc.getDuressHash());

        if (okNormal || okDuress) {
            boolean alerteEtaitPartie = watch.getState() == WatchState.ESCALATED;

            // Le secret est consommé : la ligne est supprimée, pas marquée obsolète.
            returnCodeRepository.delete(rc);
            inscrire(watchId, WatchEventType.CLOSED_BY_CODE, req.enteredAt());

            if (okDuress) {
                // Escalade en silence. L'état ESCALATED est le signal que les
                // minuteurs reprendront pour prévenir le contact ; l'envoi lui-même
                // se fait hors de cette transaction de réponse. On ne pose pas
                // closedAt : la veille n'est pas réellement close.
                watch.setState(WatchState.ESCALATED);
            } else if (alerteEtaitPartie) {
                // Une alerte était partie : la clôture est une levée. La veille est
                // résolue, et le message ③ repart là où l'alerte est allée.
                watch.setState(WatchState.RESOLVED);
                watch.setClosedAt(req.enteredAt());
                escalation.sendRenoncement(watch);
            } else {
                // Aucune alerte n'était partie (au plus des rappels à soi-même) :
                // clôture normale, personne à détromper.
                watch.setState(WatchState.CLOSED);
                watch.setClosedAt(req.enteredAt());

                // « Préviens Camille que je suis bien rentrée ». Ici et nulle part
                // ailleurs :
                //
                // — pas sur la branche de contrainte, qui ne referme rien. Annoncer
                //   un retour serein pendant qu'une escalade silencieuse part serait
                //   l'exact contraire de ce que la personne vient de demander, et
                //   rassurerait le contact au pire moment ;
                // — pas sur la levée, où le contact reçoit déjà le message ③, qui
                //   n'est pas facultatif et dit la même chose.
                if (req.veutPrevenirLeContact()) {
                    escalation.annoncerLeRetour(watch);
                }
            }
            // Succès, quel que soit le code : même statut HTTP, même corps.
            return new CloseOutcome(CloseStatus.CLOSED, rc.getAttemptsLeft());
        }

        // Code faux : un essai de moins. Le décrément doit survivre à la réponse
        // 409 — d'où le renvoi d'un résultat plutôt qu'une exception ici, qui
        // annulerait la transaction et rendrait le plafond de trois essais
        // inopérant. C'est le contrôleur qui traduit ce résultat en 409, une fois
        // cette transaction validée.
        rc.setAttemptsLeft(rc.getAttemptsLeft() - 1);
        return new CloseOutcome(CloseStatus.WRONG, rc.getAttemptsLeft());
    }

    /** L'issue d'une tentative de clôture, à traduire en réponse HTTP par le contrôleur. */
    public record CloseOutcome(CloseStatus status, int attemptsLeft) {}

    public enum CloseStatus { CLOSED, WRONG, LOCKED }

    // -------------------------------------------------------------- les sorties

    /**
     * Snooze : +30 min, sans code, et <b>toute la chaîne réarmée</b>.
     *
     * <p>Repousser l'échéance sans remettre les rappels à zéro laisserait la
     * personne à un rappel de l'escalade juste après avoir gagné du temps. Le
     * snooze rend donc les trois rappels à venir : {@code remindersSent} repart de
     * zéro, et une veille qui relançait revient en attente.
     */
    public WatchDto snooze(UUID userId, UUID watchId) {
        Watch watch = exigerVeille(userId, watchId);
        exigerSurPlace(watch);

        watch.setDeadlineAt(watch.getDeadlineAt().plus(Duration.ofMinutes(30)));
        watch.setRemindersSent(0);
        if (watch.getState() == WatchState.REMINDING) {
            watch.setState(WatchState.ON_SITE);
        }
        inscrire(watchId, WatchEventType.SNOOZED, Instant.now());
        return dto(watch);
    }

    /**
     * Panic : le message part immédiatement, sans attendre l'échéance ni les rappels.
     *
     * <p><b>Refusé tant que l'arrivée n'est pas validée</b>, depuis la décision du
     * 02/09. Le bouton signale un souci <em>au lieu de l'activité</em> : il suppose
     * qu'on y soit. L'app ne le propose plus sur le trajet aller, mais le refus
     * serveur n'est pas une redondance — une app plus ancienne, un rejeu de file
     * hors ligne ou un bouton d'écran verrouillé oublié suffiraient à faire partir
     * le message que cette décision retire.
     *
     * <p><b>Le critère est {@code arrivalConfirmedAt}, pas l'état</b>, et l'on
     * n'emploie donc pas {@link #exigerSurPlace} : ce garde refuse {@code ESCALATED},
     * ce qui retirerait le bouton d'alerte à une personne bien arrivée dont la veille
     * a déjà escaladé faute de retour confirmé — au moment précis où elle en a le
     * plus besoin.
     *
     * <p>Ce refus est aussi ce qui rend la page publique sans ambiguïté : un
     * {@code ESCALATED} sans arrivée validée ne peut plus être qu'une non-arrivée
     * héritée, jamais un panic.
     */
    public WatchDto panic(UUID userId, UUID watchId) {
        Watch watch = exigerVeille(userId, watchId);
        if (!watch.estActive()) {
            throw new ConflictException(ErrorCode.WATCH_NOT_DISARMABLE,
                "Cette veille est déjà close.");
        }
        if (watch.getArrivalConfirmedAt() == null) {
            throw new ConflictException(ErrorCode.WATCH_NOT_ON_SITE,
                "Ce geste suppose une arrivée validée.");
        }
        watch.setState(WatchState.ESCALATED);
        inscrire(watchId, WatchEventType.PANIC_TRIGGERED, Instant.now());
        // À la différence de la contrainte, panic est un geste explicite : rien à
        // cacher, on prévient tout de suite.
        escalation.ensureAlerted(watch, 0);
        return dto(watch);
    }

    /**
     * Renvoi du code : le régénère, sous mot de passe, une fois par cycle.
     *
     * <p>L'ancien code est oublié du serveur — on ne peut que le remplacer. Le mot
     * de passe est exigé pour que la route ne devienne pas une porte dérobée : sans
     * lui, un téléphone déverrouillé suffirait à se fabriquer un code. Un seul
     * renvoi par cycle, pour qu'il ne serve pas non plus à contourner le plafond de
     * trois essais.
     */
    public ArrivalResponse resendCode(UUID userId, UUID watchId, ResendCodeRequest req) {
        Watch watch = exigerVeille(userId, watchId);
        ReturnCode rc = returnCodeRepository.findByWatchId(watchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.WATCH_NOT_ON_SITE,
                "Aucun code à renvoyer : l'arrivée n'a pas été validée."));

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.WATCH_PASSWORD_REQUIRED,
                "Mot de passe incorrect.");
        }
        if (rc.isResent()) {
            throw new ConflictException(ErrorCode.WATCH_RESEND_ALREADY_USED,
                "Le code a déjà été renvoyé pour ce cycle.");
        }

        String code = ReturnCodeGenerator.next();
        byte[] sel = pepper.nouveauSel();
        int kv = pepper.versionCourante();
        rc.replaceWith(pepper.empreinte(code, sel, kv),
            java.util.Base64.getEncoder().encodeToString(sel), kv);

        inscrire(watchId, WatchEventType.CODE_RESENT, Instant.now());
        return new ArrivalResponse(dto(watch), code);
    }

    /**
     * Interruption d'une séance : on repart plus tôt.
     *
     * <p>{@code alreadyHome=true} : la personne est déjà rentrée, l'échéance passe à
     * maintenant — retour à confirmer sur-le-champ, avec le code. {@code false} :
     * elle prend le trajet, et l'échéance se recale sur maintenant + la durée reçue
     * de l'app (bornée 15–240 min), le code étant demandé à l'arrivée. Dans les deux
     * cas, la chaîne de rappels est réarmée sur la nouvelle échéance.
     */
    public WatchDto interrupt(UUID userId, UUID watchId, InterruptRequest req) {
        Watch watch = exigerVeille(userId, watchId);
        exigerSurPlace(watch);

        Instant now = Instant.now();
        watch.setInterruptedAt(now);
        watch.setRemindersSent(0);
        if (watch.getState() == WatchState.REMINDING) {
            watch.setState(WatchState.ON_SITE);
        }

        if (req.alreadyHome()) {
            watch.setDeadlineAt(now);
        } else {
            int minutes = req.travelMinutes() == null ? TRAJET_DEFAUT_MIN
                : Math.max(TRAJET_MIN, Math.min(TRAJET_MAX, req.travelMinutes()));
            watch.setDeadlineAt(now.plus(Duration.ofMinutes(minutes)));
        }

        inscrire(watchId, WatchEventType.INTERRUPTED, now,
            req.reason() == null || req.reason().isBlank() ? null : req.reason().strip());
        return dto(watch);
    }

    private void exigerSurPlace(Watch watch) {
        if (watch.getState() != WatchState.ON_SITE && watch.getState() != WatchState.REMINDING) {
            throw new ConflictException(ErrorCode.WATCH_NOT_ON_SITE,
                "Ce geste suppose une arrivée validée.");
        }
    }

    // ------------------------------------------------------------------ outils

    /** Fabrique le DTO en y composant l'URL du lien public et l'état de remise. */
    private WatchDto dto(Watch watch) {
        return WatchDto.from(watch, publicBaseUrl, deliveryOf(watch.getId()));
    }

    private void inscrire(UUID watchId, WatchEventType type, Instant quand) {
        eventRepository.save(new WatchEvent(watchId, type, quand));
    }

    private void inscrire(UUID watchId, WatchEventType type, Instant quand, String detail) {
        eventRepository.save(new WatchEvent(watchId, type, quand, detail));
    }
}
