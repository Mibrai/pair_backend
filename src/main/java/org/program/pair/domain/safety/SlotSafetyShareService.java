package org.program.pair.domain.safety;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotOccurrence;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.safety.dto.SafetyShareLinkDto;
import org.program.pair.domain.user.GivenName;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.SlotSafetyShareRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Le partage de sécurité : dire à un proche où l'on va, sans lui ouvrir de compte.
 *
 * <p><b>Qui peut créer un lien.</b> Un inscrit au créneau ou son organisateur,
 * et personne d'autre — la question est posée à {@link SlotAudience}, qui réunit
 * les trois façons d'être sur un créneau. Les deux définitions voisines du dépôt
 * ne conviendraient pas : celle de la visibilité d'adresse ne connaît que les
 * participations confirmées, celle des participants ne connaît que l'hôte.
 *
 * <p><b>404, jamais 403.</b> Un créneau auquel on n'est pas inscrit est
 * introuvable, pas interdit. Le refus habituel du dépôt est un
 * {@code ForbiddenException} ; le reprendre ici révélerait l'existence du
 * créneau à qui essaie des identifiants, et l'existence d'un lien expiré à qui
 * essaie des jetons.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SlotSafetyShareService {

    /**
     * Six heures après la fin prévue. Assez pour couvrir un retour tardif, assez
     * court pour qu'un lien oublié cesse rapidement de dire où quelqu'un se rend.
     */
    private static final Duration GRACE = Duration.ofHours(6);

    private final SlotSafetyShareRepository shareRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final SlotAudience slotAudience;

    @Value("${pair.public.base-url:https://meetdo.fun}")
    private String publicBaseUrl;

    public SafetyShareLinkDto create(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!slotAudience.participantIds(slot).contains(userId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        // La séance partagée et l'échéance sont figées maintenant. Les relire plus
        // tard depuis le créneau ferait mentir la page sur un créneau récurrent,
        // dont le rollover avance starts_at toutes les dix minutes.
        SlotOccurrence occurrence = SlotTiming.currentOccurrence(slot);

        SlotSafetyShare share = shareRepository.save(SlotSafetyShare.builder()
            .user(user)
            .schedule(slot)
            .shareToken(ShareToken.nextUnique(shareRepository::existsByShareToken))
            .occurrenceStartsAt(occurrence.startsAt())
            .occurrenceEndsAt(occurrence.endsAt())
            .expiresAt(occurrence.endsAt().plus(GRACE))
            .build());

        return new SafetyShareLinkDto(
            share.getShareToken(),
            publicBaseUrl + "/public/safety/" + share.getShareToken(),
            share.getExpiresAt());
    }

    /**
     * Le contenu de la page, ou rien.
     *
     * <p>Un jeton inconnu et un lien expiré rendent la même chose : il ne doit
     * pas être possible de distinguer « ce lien n'a jamais existé » de « ce lien
     * a existé », ce qui reviendrait à confirmer qu'un rendez-vous a eu lieu.
     */
    @Transactional
    public SafetyShareView view(String token, Instant now) {
        SlotSafetyShare share = shareRepository.findByShareToken(token)
            .filter(s -> s.getExpiresAt().isAfter(now))
            .orElseThrow(() -> new ResourceNotFoundException("Lien introuvable ou expiré."));

        if (share.getViewedAt() == null) {
            share.setViewedAt(now);
        }

        Schedule slot = share.getSchedule();
        User organizer = slot.getProgram().getUserActivity().getUser();

        return new SafetyShareView(
            slot.getProgram().getUserActivity().getActivity().getName(),
            share.getOccurrenceStartsAt(),
            share.getOccurrenceEndsAt(),
            slot.getPlaceName(),
            slot.getCity(),
            GivenName.from(organizer.getDisplayName()));
    }
}
