package org.program.pair.domain.block;

import lombok.RequiredArgsConstructor;
import org.program.pair.repository.UserBlockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * La décision : ces deux personnes se voient-elles encore ?
 *
 * <p><b>Ce service décide, il n'applique pas.</b> La distinction est la clé du
 * lot. Le dépôt n'a aucun point de passage unique — pas d'aspect, pas
 * d'intercepteur métier, pas de repository de base commun — et un filtre
 * Hibernate global serait inopérant, la plupart des requêtes concernées étant en
 * SQL natif ou en {@code JdbcTemplate} brut. Le filtrage descend donc dans
 * chaque requête, avec le même prédicat ; ce service n'existe que pour les
 * quelques endroits qui ne peuvent pas faire autrement, et pour les refus
 * impératifs.
 *
 * <p><b>Pourquoi pas de méthode {@code filter(List)} ici.</b> Ce serait
 * l'invitation exacte au post-filtrage, et le post-filtrage casse trois
 * surfaces : {@code /map/bounds} calcule sa troncature depuis un {@code COUNT}
 * séparé, {@code /api/search} pagine en mémoire sur des compteurs qui portent
 * sur la requête entière, et les fils tronqués par un {@code LIMIT} rendraient
 * des pages qui rétrécissent en silence.
 *
 * <p><b>Et jamais depuis un constructeur de DTO.</b> Dans ce code, des méthodes
 * de service sont réutilisées comme fabriques par d'autres services :
 * {@code UserService.getPublicProfile} est appelée par cinq endroits internes en
 * plus de son endpoint. Y faire lever une exception transformerait un masquage
 * en erreur serveur. La règle : décider au bord — contrôleur ou requête —
 * jamais au milieu.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BlockFilterService {

    private final UserBlockRepository userBlockRepository;

    /**
     * Vrai si l'un a bloqué l'autre, dans un sens ou dans l'autre.
     *
     * <p>Bilatéral par principe : un refus qui dépendrait du sens rendrait le
     * blocage détectable par comparaison. Deux identifiants identiques rendent
     * {@code false} — on ne se bloque pas soi-même, et la base l'interdit.
     */
    public boolean blocked(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) {
            return false;
        }
        return userBlockRepository.existsBetween(a, b);
    }

    /**
     * Vrai si c'est {@code caller} qui a bloqué {@code other}.
     *
     * <p>Sert uniquement à choisir la <b>forme du refus</b>, jamais à décider de
     * la visibilité. Celui qui a bloqué peut apprendre pourquoi on lui refuse
     * quelque chose — il l'a voulu. Celui qui a été bloqué doit recevoir le refus
     * d'une ressource qui n'existe pas : un code nommé lui apprendrait le
     * blocage, ce que toute la règle cherche à éviter.
     */
    public boolean blockedBy(UUID caller, UUID other) {
        if (caller == null || other == null || caller.equals(other)) {
            return false;
        }
        return userBlockRepository.existsByBlockerIdAndBlockedId(caller, other);
    }

    /**
     * Tous ceux qui sont invisibles pour cette personne.
     *
     * <p>Réservé aux surfaces qui n'ont pas d'autre choix que de filtrer en
     * mémoire. Rend un ensemble vide pour un appelant anonyme : sans identité,
     * il n'y a personne à masquer.
     */
    public Set<UUID> invisibleTo(UUID userId) {
        return userId == null ? Set.of() : userBlockRepository.findCounterpartIds(userId);
    }
}
