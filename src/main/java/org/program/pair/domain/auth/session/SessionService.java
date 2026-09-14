package org.program.pair.domain.auth.session;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.InvalidTokenException;
import org.program.pair.shared.observabilite.ScheduledJobMetricsAspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Les sessions révocables (P-BS-03).
 *
 * <p>Avant ce service, le jeton de rafraîchissement était un JWT que rien ne
 * retenait : se déconnecter ne l'invalidait pas, et un mot de passe changé ne
 * coupait aucun appareil. Chaque jeton émis est désormais une ligne
 * {@code refresh_tokens}, rattachée à une session {@code refresh_sessions}.
 *
 * <p><b>Rotation tolérante — P-BS/D4 option B.</b> Échanger un jeton en émet un
 * nouveau. L'ancien reste échangeable <i>tant qu'aucun de ses successeurs n'a
 * servi</i> : une réponse de rafraîchissement perdue en route — le cas même du
 * 10/09 — ne déconnecte donc personne, l'app réessaie avec l'ancien et reçoit un
 * frère. Mais dès qu'un successeur a servi, présenter l'ancien est un rejeu : deux
 * détenteurs du même jeton, dont l'un ne devrait pas l'être. La session entière
 * est alors révoquée.
 *
 * <p><b>Échéance glissante — P-BS/D5 option A.</b> Chaque échange repart de
 * trente jours, et aucune session n'a de plafond absolu : le contrat du 10/09
 * dit qu'une session utilisée au moins une fois par mois ne finit jamais.
 */
@Service
@Slf4j
public class SessionService {

    static final String MOTIF_DECONNEXION = "DECONNEXION";
    static final String MOTIF_REJEU = "REJEU";
    static final String MOTIF_BOUCLE = "BOUCLE";
    public static final String MOTIF_MDP_REINITIALISE = "MDP_REINITIALISE";
    public static final String MOTIF_MDP_CHANGE = "MDP_CHANGE";
    public static final String MOTIF_COMPTE_DESACTIVE = "COMPTE_DESACTIVE";

    /** Au-delà, un jeton qui engendre des frères sans qu'aucun ne serve est une boucle, pas une réponse perdue. */
    static final int SUCCESSEURS_MAX = 5;

    private final RefreshSessionRepository sessions;
    private final RefreshTokenRepository jetons;
    private final UserRepository users;
    private final JwtTokenProvider tokenProvider;
    private final ScheduledJobMetricsAspect metriques;

    /**
     * Adopter les jetons émis avant P-BS-03 — voir
     * {@code pair.session.adoption-jetons-historiques} dans
     * {@code application.properties} pour la raison, la date de bascule et la
     * preuve qui l'autorise.
     */
    private final boolean adoption;

    public SessionService(RefreshSessionRepository sessions,
                          RefreshTokenRepository jetons,
                          UserRepository users,
                          JwtTokenProvider tokenProvider,
                          ScheduledJobMetricsAspect metriques,
                          @Value("${pair.session.adoption-jetons-historiques:true}") boolean adoption) {
        this.sessions = sessions;
        this.jetons = jetons;
        this.users = users;
        this.tokenProvider = tokenProvider;
        this.metriques = metriques;
        this.adoption = adoption;
    }

    /** Les deux jetons d'une session, et la session qui les porte. */
    public record Jetons(String acces, String rafraichissement, UUID session) {}

    /** Ouvre une session pour un compte qui vient de prouver qui il est (connexion, inscription). */
    @Transactional
    public Jetons ouvrir(User user) {
        Instant maintenant = Instant.now();
        RefreshSession session = sessions.save(RefreshSession.ouvrir(user.getId(), maintenant));
        return emettre(user, session, null, maintenant);
    }

    /**
     * Échange un jeton de rafraîchissement contre un nouveau couple.
     *
     * <p>{@code noRollbackFor} n'est pas un détail : un rejeu révoque la session
     * <i>puis</i> refuse. Sans lui, le refus annulerait la transaction, et la
     * révocation avec — le voleur et la victime garderaient la session.
     *
     * @throws InvalidTokenException pour tout jeton qui ne vaut plus rien — même
     *         message quelle que soit la raison, pour ne rien apprendre à qui essaie
     */
    @Transactional(noRollbackFor = InvalidTokenException.class)
    public Jetons echanger(String jetonBrut) {
        if (!(tokenProvider.lire(jetonBrut) instanceof JetonLu.Valide lu) || !lu.rafraichissement()) {
            throw invalide();
        }
        User user = users.findById(lu.sujet())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(SessionService::invalide);

        if (lu.jti() == null) {
            return adopter(user);
        }

        Instant maintenant = Instant.now();
        RefreshToken jeton = jetons.findForUpdate(lu.jti()).orElseThrow(SessionService::invalide);
        RefreshSession session = jeton.getSession();
        if (!session.getUserId().equals(user.getId())
                || session.revoquee() || jeton.revoque() || jeton.expire(maintenant)) {
            throw invalide();
        }

        if (jeton.getUsedAt() != null) {
            if (jetons.nombreDeSuccesseursUtilises(jeton.getJti()) > 0) {
                session.revoquer(maintenant, MOTIF_REJEU);
                log.warn("Session {} révoquée : rejeu d'un jeton dont un successeur a servi", session.getId());
                throw invalide();
            }
            if (jetons.nombreDeSuccesseurs(jeton.getJti()) >= SUCCESSEURS_MAX) {
                session.revoquer(maintenant, MOTIF_BOUCLE);
                log.warn("Session {} révoquée : {} échanges du même jeton sans qu'aucun ne serve",
                    session.getId(), SUCCESSEURS_MAX);
                throw invalide();
            }
            // Réponse perdue : l'ancien reste échangeable, il reçoit un frère.
        } else {
            jeton.marquerUtilise(maintenant);
            if (jeton.getParentJti() != null) {
                // Premier usage de ce jeton : ses frères nés de la même réponse
                // perdue n'ont plus de raison de valoir.
                jetons.revoquerFreres(jeton.getParentJti(), jeton.getJti(), maintenant);
            }
        }

        session.toucher(maintenant);
        user.setLastActiveAt(maintenant);
        return emettre(user, session, jeton.getJti(), maintenant);
    }

    /**
     * Révoque la session d'un jeton de rafraîchissement présenté à la
     * déconnexion. Silencieux sur un jeton inconnu, illisible ou déjà révoqué :
     * la déconnexion répond toujours de la même façon.
     *
     * @return le compte dont la session a été fermée, s'il a pu être établi
     */
    @Transactional
    public Optional<UUID> fermer(String jetonBrut) {
        if (jetonBrut == null || jetonBrut.isBlank()
                || !(tokenProvider.lire(jetonBrut) instanceof JetonLu.Valide lu)
                || !lu.rafraichissement() || lu.session() == null) {
            return Optional.empty();
        }
        return sessions.findById(lu.session())
            .filter(s -> s.getUserId().equals(lu.sujet()))
            .map(s -> {
                s.revoquer(Instant.now(), MOTIF_DECONNEXION);
                return s.getUserId();
            });
    }

    /**
     * Révoque toutes les sessions d'un compte, sauf {@code epargnee} si elle est
     * donnée, et incrémente la version de ses jetons : les jetons d'accès émis
     * avant sont refusés dès la requête suivante.
     *
     * <p>L'appareil épargné voit son jeton d'accès refusé une fois, comme les
     * autres ; son rafraîchissement, lui, réussit — sa session n'est pas révoquée
     * — et le jeton d'accès qu'il reçoit porte la nouvelle version.
     */
    @Transactional
    public void revoquerToutes(UUID userId, UUID epargnee, String motif) {
        users.findById(userId).ifPresent(user -> {
            user.setTokenVersion(user.getTokenVersion() + 1);
            users.save(user);
        });
        int revoquees = sessions.revoquerToutes(userId, epargnee, motif, Instant.now());
        log.info("Compte {} : {} session(s) révoquée(s) ({})", userId, revoquees, motif);
    }

    /**
     * Nettoyage quotidien : une ligne par rafraîchissement, soit une centaine par
     * jour par utilisateur actif, ne peut pas s'accumuler indéfiniment.
     */
    @SchedulerLock(name = "session-purge")
    @Scheduled(cron = "0 40 3 * * *")
    @Transactional
    public void purger() {
        try {
            Instant maintenant = Instant.now();
            int consommes = jetons.purgerConsommes(maintenant.minus(Duration.ofDays(2)));
            int anciennes = sessions.purger(
                maintenant.minus(tokenProvider.refreshTokenTtl()), maintenant.minus(Duration.ofDays(7)));
            log.info("Sessions : {} jeton(s) consommé(s) et {} session(s) purgé(s)", consommes, anciennes);
        } catch (RuntimeException e) {
            metriques.echecAvale(this, "purger");
            log.error("Purge des sessions en échec", e);
        }
    }

    /**
     * Un jeton émis avant P-BS-03 : ni session, ni {@code jti}. Tant que
     * l'adoption est allumée et que la version du compte vaut encore 0 — aucun mot
     * de passe changé depuis —, on ouvre une session et on rend un couple
     * persisté. Personne n'est déconnecté par le déploiement.
     */
    private Jetons adopter(User user) {
        if (!adoption || user.getTokenVersion() != 0) {
            throw invalide();
        }
        log.info("Session adoptée pour le compte {} (jeton émis avant la persistance)", user.getId());
        return ouvrir(user);
    }

    private Jetons emettre(User user, RefreshSession session, UUID parent, Instant maintenant) {
        Instant echeance = maintenant.plus(tokenProvider.refreshTokenTtl());
        RefreshToken jeton = jetons.save(RefreshToken.emettre(session, parent, maintenant, echeance));
        return new Jetons(
            tokenProvider.generateAccessToken(user.getId(), session.getId(), user.getTokenVersion()),
            tokenProvider.generateRefreshToken(user.getId(), session.getId(), jeton.getJti(), echeance),
            session.getId());
    }

    private static InvalidTokenException invalide() {
        return new InvalidTokenException("Refresh token invalide ou expiré.");
    }
}
