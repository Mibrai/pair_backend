package org.program.pair.domain.visibility;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLog;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.chat.ChatService;
import org.program.pair.domain.preference.UserPreferenceService;
import org.program.pair.domain.user.PrivacySettings;
import org.program.pair.domain.user.User;
import org.program.pair.domain.visibility.dto.VisibilityDtos.ChannelCut;
import org.program.pair.domain.visibility.dto.VisibilityDtos.ChannelState;
import org.program.pair.domain.visibility.dto.VisibilityDtos.CutAllResponse;
import org.program.pair.domain.visibility.dto.VisibilityDtos.VisibilityStateResponse;
import org.program.pair.domain.watch.PublicWatchService;
import org.program.pair.repository.AfficheRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.repository.WatchRepository;
import org.program.pair.shared.exception.UserNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * « Tout couper » et l'état de chaque canal de « Qui me voit » (demande mobile du
 * 14/09/2026, modules/tracabilite, P-MU-05 étapes 4 à 6).
 *
 * <p><b>Une transaction.</b> Un bouton qui coupe à moitié en disant « c'est
 * coupé » rassure à tort : si une étape échoue, rien n'a changé. Les partages de
 * position passent en dernier parce qu'ils sont les seuls à diffuser en
 * WebSocket pendant la transaction ; tout ce qui peut échouer avant eux échoue
 * donc avant la moindre diffusion.
 *
 * <p><b>Rien ne se rouvre.</b> La coupure ne mémorise pas l'état d'avant : rouvrir
 * un canal reste le geste de la personne, écran par écran.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class VisibilityService {

    /** La clé que l'app range pour la portée proposée à la prochaine affiche. */
    static final String CLE_AUDIENCE_AFFICHE = "affiche.audience";

    private final AfficheRepository afficheRepository;
    private final WatchRepository watchRepository;
    private final UserRepository userRepository;
    private final UserPreferenceService preferenceService;
    private final ChatService chatService;
    private final AuditLogRepository auditLogRepository;

    public CutAllResponse cutAll(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // AFFICHES — la portée opposable est par affiche ; la préférence ne règle
        // que la prochaine publication. Les deux, sans rafraîchir publishedAt.
        afficheRepository.fermerToutes(userId, now);
        preferenceService.write(userId, CLE_AUDIENCE_AFFICHE, "nobody");

        // WATCH_LINKS — y compris les veilles closes depuis moins de 24 h, que
        // /watches/active ne rend plus mais dont la page s'ouvre encore.
        watchRepository.revoquerLiensOuvrables(userId, now, now.minus(PublicWatchService.APRES_CLOTURE));

        // MAP_PRESENCE — les trois réglages : la recherche de personnes par
        // distance retient aussi show_location et show_on_map
        // (UserRepository.SEARCH_USERS_BODY). locationPublic seul laissait
        // quelqu'un trouvable dans un rayon.
        user.setLocationPublic(false);
        PrivacySettings privacy = user.getPrivacySettings();
        if (privacy != null) {
            privacy.setShowLocation(false);
            privacy.setShowOnMap(false);
        }
        userRepository.save(user);

        auditLogRepository.save(AuditLog.builder()
            .userId(userId)
            .actionType(AuditActionType.VISIBILITY_CUT_ALL)
            .entityType("USER")
            .entityId(userId)
            .createdAt(now)
            .build());

        // CHAT_LOCATION — en dernier, voir la tête de classe.
        chatService.echoirPartagesDePosition(userId, null);

        // LIVE_STATUS — rien à couper : le serveur ne sert aucun statut live.
        return new CutAllResponse(now, Arrays.stream(VisibilityChannel.values())
            .map(canal -> new ChannelCut(canal, true))
            .toList());
    }

    @Transactional(readOnly = true)
    public VisibilityStateResponse state(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
        Instant now = Instant.now();
        PrivacySettings privacy = user.getPrivacySettings();

        boolean presence = Boolean.TRUE.equals(user.getLocationPublic())
            || (privacy != null && (Boolean.TRUE.equals(privacy.getShowLocation())
                || Boolean.TRUE.equals(privacy.getShowOnMap())));

        List<ChannelState> channels = Arrays.stream(VisibilityChannel.values())
            .map(canal -> new ChannelState(canal, switch (canal) {
                case AFFICHES -> afficheRepository.existeOuverte(userId);
                case WATCH_LINKS -> watchRepository.existeLienOuvrable(userId,
                    now.minus(PublicWatchService.APRES_CLOTURE));
                case CHAT_LOCATION -> chatService.aDesPartagesDePositionOuverts(userId);
                case MAP_PRESENCE -> presence;
                case LIVE_STATUS -> false;
            }))
            .toList();

        List<Instant> coupures = auditLogRepository
            .findTop10ByUserIdAndActionTypeOrderByCreatedAtDesc(userId, AuditActionType.VISIBILITY_CUT_ALL)
            .stream().map(AuditLog::getCreatedAt).toList();

        return new VisibilityStateResponse(channels, coupures);
    }
}
