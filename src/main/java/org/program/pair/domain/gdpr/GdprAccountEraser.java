package org.program.pair.domain.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.repository.MessageRepository;
import org.program.pair.repository.PeerRecommendationRepository;
import org.program.pair.repository.ReviewRepository;
import org.program.pair.repository.SearchLogRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * L'effacement d'<b>un seul</b> compte, dans sa propre transaction (RGPD article 17).
 *
 * <p><b>Le défaut que cette classe existe pour fermer.</b> Le contenu de cette
 * méthode vivait dans {@code GdprService.anonymizeUserData}, et
 * {@code GdprService.purgeInactiveAccounts} l'appelait — sur {@code this}. Une
 * auto-invocation ne passe pas par le proxy Spring : l'annotation
 * {@code @Transactional} de la méthode appelée était purement décorative, et
 * <b>toute la purge de la nuit tenait dans une seule transaction</b>, celle de la
 * boucle. Les conséquences s'enchaînaient :
 *
 * <ul>
 *   <li>le {@code DELETE} du compte n° 1, bloqué par une clé étrangère sans
 *       {@code ON DELETE}, n'était pas envoyé à la base au moment du
 *       {@code deleteById} mais au premier flush suivant — donc pendant le
 *       traitement du compte n° 2. L'exception tombait dans le {@code catch} du
 *       <b>mauvais compte</b>, et le journal accusait un compte innocent ;</li>
 *   <li>attrapée ou non, elle avait déjà marqué la transaction
 *       {@code rollback-only}. Le commit final levait
 *       {@code UnexpectedRollbackException}, que le job attrapait en
 *       {@code log.error} : <b>aucun</b> compte n'était effacé cette nuit-là, pas
 *       même ceux qui n'avaient rien de bloquant ;</li>
 *   <li>et la valeur rendue comptait les candidats, pas les effacements. Le
 *       journal annonçait « N comptes purgés » pour N comptes toujours là.</li>
 * </ul>
 *
 * <p><b>Pourquoi un bean séparé plutôt qu'un {@code self}-injection.</b> Le
 * {@code REQUIRES_NEW} doit franchir un proxy, ce qu'une classe ne peut pas faire
 * sur elle-même sans s'injecter elle-même — un détour qui se relit mal et qui
 * retombe silencieusement dans le défaut ci-dessus dès qu'un appel est déplacé.
 * La frontière transactionnelle est ici une frontière de classe, visible.
 *
 * <p><b>{@code REQUIRES_NEW} et non {@code REQUIRED}</b> : la boucle appelante
 * n'ouvre plus de transaction du tout, si bien que {@code REQUIRED} suffirait
 * aujourd'hui. {@code REQUIRES_NEW} tient la propriété même si un appelant futur
 * — un endpoint d'administration, un test — appelle depuis une transaction : un
 * échec reste alors circonscrit à ce compte-là.
 *
 * <h2>Ce qui reste suspendu à l'avis juridique, et que ce code ne tranche pas</h2>
 *
 * <p>La décision D2 retient l'option 1 — {@code CASCADE} et un délai compté
 * depuis la demande — mais la fiche d'audit note elle-même « à faire valider »
 * par le juridique, et <b>cet avis n'a pas été donné</b>. Trois questions sont
 * donc ouvertes, et le fait qu'elles aient une réponse dans le code ne veut pas
 * dire qu'elles sont réglées :
 *
 * <ol>
 *   <li><b>La longueur du délai.</b> Trente jours est l'usage du secteur, pas une
 *       obligation ; l'article 17 dit « sans délai excessif » sans chiffre.</li>
 *   <li><b>La réversibilité pendant ce délai — et aujourd'hui elle n'existe
 *       pas.</b> Un compte désactivé ne peut plus se connecter : les trente jours
 *       ne sont pas un délai de rétractation, c'est un sursis pendant lequel
 *       personne ne peut rien faire. Soit la réactivation est écrite (par
 *       courriel, pendant le délai), soit le délai est raccourci parce qu'il ne
 *       protège personne. Les deux sont défendables ; ce n'est pas au code de
 *       choisir.</li>
 *   <li><b>{@code CASCADE} contre {@code SET NULL} sur les présences.</b>
 *       {@code CASCADE} efface les lignes {@code attendances} du compte, ce qui
 *       fait baisser le {@code distinct_partners_count} et les cartes-souvenir
 *       <b>des autres participants</b> au recalcul suivant. {@code SET NULL}
 *       aurait gardé la trace d'une présence anonyme — mais suppose de rendre
 *       {@code attendances.user_id} nullable (il est {@code NOT NULL} depuis
 *       V41), donc d'apprendre à tout le calcul de fiabilité à lire une présence
 *       sans personne. V111 a suivi D2 ; l'arbitrage se rejoue par une migration
 *       si l'avis dit autre chose.</li>
 * </ol>
 *
 * <h2>Ce qui échouait pour toute personne qui avait écrit, et ce qui l'a fermé</h2>
 *
 * <p>Jusqu'à V123, {@link #eraseOne(UUID)} <b>échouait pour tout compte qui avait
 * envoyé un message, laissé un avis ou une recommandation</b> : l'anonymisation
 * pose l'auteur à {@code null}, et {@code messages.sender_id},
 * {@code reviews.reviewer_id} et {@code peer_recommendations.recommender_id}
 * étaient {@code NOT NULL} depuis V6 et V7. L'échec était compté et circonscrit
 * au compte, mais le compte n'était jamais effacé — c'est-à-dire presque tous
 * les comptes réels.
 *
 * <p>V123 rend les trois colonnes nullables, en {@code ON DELETE SET NULL}, et
 * la lecture des messages sait rendre un expéditeur absent
 * ({@code ChatService.senderIdOf}) : sans ce second volet, un message anonymisé
 * aurait fait tomber <b>toute la conversation de l'autre personne</b>, ce qui
 * est pire que de ne pas purger.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GdprAccountEraser {

    private final UserRepository userRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;
    private final PeerRecommendationRepository recommendationRepository;
    private final SearchLogRepository searchLogRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;

    /**
     * Efface un compte et tout ce qui le désigne, ou échoue sans rien laisser à
     * moitié fait.
     *
     * <p>L'ordre des opérations n'est pas indifférent. Ce qui doit <b>survivre</b>
     * au compte est détaché avant : les messages (dont le contenu devient
     * {@code [Message supprimé]} et l'expéditeur disparaît, pour que le fil de
     * l'autre personne garde sa forme), les avis, les recommandations, les lignes
     * d'audit. Vient ensuite la suppression de la ligne {@code users}, qui emporte
     * par {@code ON DELETE CASCADE} tout ce qui n'a aucune raison de lui survivre
     * — contacts d'urgence, veilles, incidents, partages de sécurité,
     * participations et présences depuis V111.
     *
     * <p><b>Le {@code flush()} final est la moitié du correctif.</b> Sans lui, le
     * {@code DELETE} de {@code deleteById} reste dans le contexte de persistance
     * jusqu'au commit, c'est-à-dire jusqu'<i>après</i> la sortie de cette méthode
     * — hors du {@code try} de l'appelant, et hors de cette transaction du point
     * de vue de la pile d'appel. C'est précisément ce décalage qui faisait
     * attribuer l'échec au compte suivant. Avec le flush, l'instruction est
     * envoyée ici, l'exception est levée ici, et l'appelant sait de qui il parle.
     *
     * <p><b>La trace d'audit {@code GDPR_ANONYMIZE} n'est pas fiable, et ce
     * n'est pas cette classe qui peut la rendre fiable.</b>
     * {@code AuditLogService.log} porte {@code @Async} : l'insertion part sur un
     * autre fil, hors de cette transaction, et rien ne garantit qu'elle arrive
     * avant la suppression de la ligne {@code users}. Si elle arrive après, la
     * clé étrangère {@code audit_logs_user_id_fkey} la refuse et la trace est
     * perdue dans les journaux du fil d'arrière-plan. L'appel est conservé tel
     * quel — le changer voudrait dire toucher au service d'audit — mais aucun
     * relevé ne doit s'appuyer sur la présence de cette ligne pour établir qu'un
     * compte a été effacé : c'est la ligne {@code users} absente et le journal du
     * job qui l'établissent.
     *
     * @param userId le compte à effacer ; l'appelant a déjà vérifié qu'il est
     *               désactivé depuis plus longtemps que le délai
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void eraseOne(UUID userId) {
        log.info("Effacement des données du compte {}", userId);

        auditLogService.log(userId, AuditActionType.GDPR_ANONYMIZE, "USER", userId);

        messageRepository.anonymizeBySenderId(userId);
        reviewRepository.anonymizeByReviewerId(userId);
        recommendationRepository.anonymizeByRecommenderId(userId);
        searchLogRepository.deleteByUserId(userId);
        auditLogRepository.anonymizeByUserId(userId);

        userRepository.deleteById(userId);
        userRepository.flush();

        log.info("Compte {} effacé", userId);
    }
}
