package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.guardian.ConsentState;
import org.program.pair.domain.guardian.Guardian;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.watch.dto.CreateWatchRequest;
import org.program.pair.domain.watch.dto.WatchDetailDto;
import org.program.pair.domain.watch.dto.WatchDto;
import org.program.pair.domain.watch.dto.WatchEventDto;
import org.program.pair.domain.watch.dto.ArrivalRequest;
import org.program.pair.domain.watch.dto.ArrivalResponse;
import org.program.pair.domain.watch.dto.CloseRequest;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.ReturnCodeRepository;
import org.program.pair.repository.ScheduleRepository;
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

        Watch watch = watchRepository.save(Watch.builder()
            .scheduleId(req.scheduleId())
            .userId(userId)
            .state(WatchState.ARMED)
            .armedAt(now)
            .deadlineAt(deadline)
            .remindersSent(0)
            .guardianId(req.guardianId())
            .backupGuardianId(req.backupGuardianId())
            .build());

        inscrire(watch.getId(), WatchEventType.ARMED, now);
        return WatchDto.from(watch);
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

    @Transactional(readOnly = true)
    public List<WatchDto> listActive(UUID userId) {
        return watchRepository.findByUserIdAndStateNotInOrderByArmedAtDesc(userId, WatchState.TERMINAUX)
            .stream().map(WatchDto::from).toList();
    }

    @Transactional(readOnly = true)
    public WatchDetailDto detail(UUID userId, UUID watchId) {
        Watch watch = watchRepository.findByIdAndUserId(watchId, userId)
            .orElseThrow(() -> new ResourceNotFoundException("Veille introuvable."));
        List<WatchEventDto> timeline = eventRepository.findByWatchIdOrderByOccurredAtAsc(watchId)
            .stream().map(WatchEventDto::from).toList();
        return new WatchDetailDto(WatchDto.from(watch), timeline);
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

        return new ArrivalResponse(WatchDto.from(watch), code);
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
            // Le secret est consommé : la ligne est supprimée, pas marquée obsolète.
            returnCodeRepository.delete(rc);
            inscrire(watchId, WatchEventType.CLOSED_BY_CODE, req.enteredAt());

            if (okDuress) {
                // Escalade en silence. L'état ESCALATED est le signal que les
                // minuteurs de la priorité 4 reprendront pour envoyer l'alerte ;
                // l'envoi lui-même se fait hors de cette transaction de réponse.
                // On ne pose pas closedAt : la veille n'est pas réellement close.
                watch.setState(WatchState.ESCALATED);
            } else {
                watch.setState(WatchState.CLOSED);
                watch.setClosedAt(req.enteredAt());
            }
            // Succès, quel que soit le code : même statut, même corps, même travail.
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

    // ------------------------------------------------------------------ outils

    private void inscrire(UUID watchId, WatchEventType type, Instant quand) {
        eventRepository.save(new WatchEvent(watchId, type, quand));
    }
}
