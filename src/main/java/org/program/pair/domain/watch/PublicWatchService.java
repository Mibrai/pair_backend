package org.program.pair.domain.watch;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.user.GivenName;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.repository.WatchEventRepository;
import org.program.pair.repository.WatchRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Ce que la page publique d'une veille montre, et ce que ses boutons remontent.
 *
 * <p><b>Un lien inconnu, révoqué ou expiré rend la même chose.</b> Comme le
 * partage de sécurité : il ne doit pas être possible de distinguer « ce lien n'a
 * jamais existé » de « ce lien a existé », ce qui reviendrait à confirmer, à qui
 * essaie des jetons, qu'une alerte a eu lieu.
 *
 * <p><b>Le lien expire 24 h après la clôture.</b> Passé ce délai, la page dit la
 * même chose qu'un lien inconnu. Le propriétaire peut aussi le révoquer à tout
 * moment — c'est son rendez-vous, pas celui du contact.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class PublicWatchService {

    /** Combien de temps la page reste lisible après la clôture. */
    private static final Duration APRES_CLOTURE = Duration.ofHours(24);

    private final WatchRepository watchRepository;
    private final WatchEventRepository eventRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;

    /**
     * Le contenu de la page, ou rien.
     *
     * <p>La première ouverture est enregistrée : c'est le « le principal a ouvert »
     * qui décidera si l'on prévient le contact de secours.
     */
    public PublicWatchView view(String token, Instant now) {
        Watch watch = ouvrable(token, now);

        if (watch.getPublicViewedAt() == null) {
            watch.setPublicViewedAt(now);
        }

        Schedule slot = scheduleRepository.findById(watch.getScheduleId()).orElse(null);
        User user = userRepository.findById(watch.getUserId()).orElse(null);

        String prenom = user != null ? GivenName.from(user.getDisplayName()) : "";
        Instant lastUpdate = eventRepository.findFirstByWatchIdOrderByOccurredAtDesc(watch.getId())
            .map(WatchEvent::getOccurredAt)
            .orElse(watch.getArmedAt());

        return new PublicWatchView(
            PublicWatchStatus.of(watch, now),
            prenom,
            slot != null ? titre(slot) : null,
            slot != null ? slot.getPlaceName() : null,
            slot != null ? slot.getCity() : null,
            watch.getOccurrenceStartsAt(),
            slot != null ? SlotTiming.endOf(slot) : null,
            watch.getDeadlineAt(),
            lastUpdate,
            watch.getState().estActive() ? false : true);
    }

    /**
     * Un bouton d'accusé : « j'ai vu » ou « je l'ai eue au téléphone ». Remonte
     * dans l'app par la chronologie, et vaut ouverture.
     *
     * <p><b>Aucun bouton ne clôture</b> : la page est publique et non
     * authentifiée ; un bouton de clôture clôturerait pour quiconque a le lien.
     */
    public void acknowledge(String token, WatchEventType type, Instant now) {
        if (type != WatchEventType.GUARDIAN_ACK_SEEN && type != WatchEventType.GUARDIAN_ACK_CALLED) {
            throw new IllegalArgumentException("Type d'accusé non reconnu.");
        }
        Watch watch = ouvrable(token, now);
        if (watch.getPublicViewedAt() == null) {
            watch.setPublicViewedAt(now);
        }
        eventRepository.save(new WatchEvent(watch.getId(), type, now));
    }

    private Watch ouvrable(String token, Instant now) {
        Watch watch = watchRepository.findByPublicToken(token)
            .orElseThrow(() -> new ResourceNotFoundException("Lien introuvable ou expiré."));

        if (watch.getPublicTokenRevokedAt() != null) {
            throw new ResourceNotFoundException("Lien introuvable ou expiré.");
        }
        if (watch.getClosedAt() != null && now.isAfter(watch.getClosedAt().plus(APRES_CLOTURE))) {
            throw new ResourceNotFoundException("Lien introuvable ou expiré.");
        }
        return watch;
    }

    private static String titre(Schedule slot) {
        try {
            return slot.getProgram().getUserActivity().getActivity().getName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
