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
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
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
    private final SlotAudience slotAudience;

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

    // ------------------------------------------------------------------ outils

    private void inscrire(UUID watchId, WatchEventType type, Instant quand) {
        eventRepository.save(new WatchEvent(watchId, type, quand));
    }
}
