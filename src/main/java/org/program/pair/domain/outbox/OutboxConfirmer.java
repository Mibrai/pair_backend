package org.program.pair.domain.outbox;

import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.user.VerificationEmailDelivery;
import org.program.pair.repository.OutboxMessageRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.observabilite.MetriquesOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Écrit ce que le fournisseur a répondu, un message à la fois.
 *
 * <p><b>Pourquoi un bean à part.</b> Même raison que pour
 * {@link OutboxClaimer} : {@code OutboxService.dispatchPending} n'a plus de
 * transaction, et une méthode {@code @Transactional} appelée depuis la même
 * classe ne passerait pas par le proxy Spring — l'annotation serait décorative.
 * La méthode est donc publique, sur un bean distinct.
 *
 * <p><b>Une transaction par message, et c'est l'objet du lot.</b> Le lot entier
 * tenait auparavant dans la transaction de {@code dispatchPending} : une
 * exception sur le quarantième message annulait les {@code markSent} des
 * trente-neuf premiers, <b>alors que le fournisseur les avait acceptés</b>. Ils
 * repartaient au balayage suivant, et les proches recevaient deux fois la même
 * alerte. Ici, chaque confirmation est validée pour elle seule ; l'échec de l'une
 * ne peut pas défaire l'autre.
 *
 * <p><b>Un état terminal n'est jamais repris.</b> Un message déjà {@code SENT} ou
 * {@code FAILED} sort d'ici sans être touché. Le cas se produit pour de vrai :
 * quand le verrou d'un balayage tué expire, un second balayage reprend le
 * message ; si le premier revient à la vie et confirme un refus, il ne doit pas
 * ramener à {@code PENDING} un message que le second a réussi à envoyer — ce
 * serait le renvoyer une troisième fois.
 */
@Component
@Slf4j
public class OutboxConfirmer {

    private final OutboxMessageRepository repository;

    /**
     * Pour reporter sur le compte ce qu'un e-mail de vérification devient. Voir
     * {@link #reporterLEnvoiSurLeCompte}.
     */
    private final UserRepository userRepository;

    /**
     * L'âge au-delà duquel une alerte n'a plus d'objet — <b>désactivée</b>.
     *
     * <p><b>L'arbitrage que ce réglage prépare, sans le trancher.</b>
     * {@link OutboxService#PRIORITE_ALERTE} n'ordonne que le lot : elle fait
     * passer une alerte devant un e-mail de vérification, elle ne borne pas son
     * âge. Depuis le lot 0, une alerte disant qu'un proche n'est pas rentré
     * réessaie donc exactement aussi longtemps qu'un lien d'inscription — un peu
     * plus de deux heures — et {@code WatchService.deliveryOf} rend
     * « {@code PENDING} » pendant tout ce temps : l'application affiche
     * « en cours d'envoi » au lieu d'admettre que personne n'a été prévenu. Deux
     * heures de nouveaux essais n'ont de sens que si le message reste pertinent,
     * et cela ne se décide pas ici : c'est une question du module traçabilité.
     *
     * <p>Le réglage existe donc pour que la décision soit à un tour de clé, et
     * vaut <b>zéro — inactif</b>, comportement inchangé. Le mettre à 900, par
     * exemple, ferait passer une alerte de plus de quinze minutes en
     * {@code FAILED} au premier refus suivant, ce que l'écran de la veille dirait
     * alors franchement. Aucune propriété n'est déclarée dans les fichiers de
     * configuration : la valeur par défaut est ici, et il faut un geste délibéré
     * (la ligne {@code outbox.alerte.echeance-secondes} et la variable
     * d'environnement qui l'alimente) pour l'armer. Le réglage se compte en
     * secondes, et non en {@code Duration} : une conversion de moins à faire
     * confiance au démarrage, pour un réglage qui ne sert pas encore.
     */
    private final Duration echeanceAlerte;

    /**
     * Les trois issues d'un envoi sont mesurées ici, parce que c'est ici qu'elles
     * sont décidées — un seul point à tenir d'accord avec les alertes.
     *
     * <p><b>Le compteur est incrémenté dans la transaction, et c'est assumé.</b>
     * Si l'écriture échouait à la validation, le compteur garderait l'incrément
     * d'une issue qui n'a pas été écrite : une unité de trop sur un signal, à
     * comparer avec le fait de ne rien compter du tout quand le message, lui, est
     * bien parti chez le fournisseur. Ces compteurs servent à voir une panne, pas
     * à tenir une comptabilité — la vérité reste la table.
     */
    private final MetriquesOutbox metriques;

    public OutboxConfirmer(OutboxMessageRepository repository,
                           UserRepository userRepository,
                           MetriquesOutbox metriques,
                           @Value("${outbox.alerte.echeance-secondes:0}") long echeanceAlerteSecondes) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.metriques = metriques;
        this.echeanceAlerte = Duration.ofSeconds(echeanceAlerteSecondes);
    }

    /**
     * Ce que le fournisseur a répondu à une remise.
     *
     * @param accepte           le fournisseur a-t-il pris le message en charge
     * @param providerMessageId son identifiant chez le fournisseur, pour recouper
     *                          l'accusé de remise ; nul s'il a refusé
     * @param erreur            de quoi journaliser le refus ; nul s'il a accepté
     */
    public record Resultat(boolean accepte, String providerMessageId, String erreur) {

        public static Resultat accepte(String providerMessageId) {
            return new Resultat(true, providerMessageId, null);
        }

        public static Resultat refuse(String erreur) {
            return new Resultat(false, null, erreur);
        }
    }

    /**
     * Enregistre l'issue d'un envoi : {@code SENT}, ou {@code PENDING} avec la
     * date de son prochain essai, ou {@code FAILED}.
     *
     * @param id       le message réclamé
     * @param resultat ce que le fournisseur a répondu
     * @param now      l'instant de la réponse, d'où se comptent les délais
     */
    @Transactional
    public void confirmer(UUID id, Resultat resultat, Instant now) {
        OutboxMessage message = repository.findById(id).orElse(null);
        if (message == null) {
            // Purgé entre la réclamation et la confirmation. Rien à écrire, et
            // rien d'alarmant : la purge n'efface que les messages déjà partis.
            log.debug("Outbox : message {} introuvable à la confirmation", id);
            return;
        }
        if (message.getStatus() == OutboxStatus.SENT || message.getStatus() == OutboxStatus.FAILED) {
            log.warn("Outbox : confirmation tardive ignorée, le message {} est déjà {}",
                id, message.getStatus());
            return;
        }

        String canal = message.getChannel().name();
        String objet = message.getPurpose().name();

        if (resultat.accepte()) {
            message.markSent(resultat.providerMessageId(), now);
            metriques.messageParti(canal, objet);
        } else if (echeanceDepassee(message, now)) {
            message.markExpired(now);
            metriques.messageEnEchec(canal, objet);
            log.error("Outbox : message {} abandonné, son échéance de {} min est passée ({}, veille {})",
                message.getId(), echeanceAlerte.toMinutes(), message.getChannel(), message.getWatchId());
        } else {
            message.markAttemptFailed(now, OutboxService.MAX_ESSAIS);
            if (message.getStatus() == OutboxStatus.FAILED) {
                metriques.messageEnEchec(canal, objet);
                // Le journal ne porte ni destinataire ni corps : il vit plus
                // longtemps que le message, que la purge efface à sept jours. De
                // quoi retrouver la ligne, juger de la gravité, et rattacher
                // l'abandon à ce qui l'a produit. Rien d'autre.
                log.error("Outbox : message {} abandonné après {} essais ({}, veille {})",
                    message.getId(), message.getAttempts(), message.getChannel(), message.getWatchId());
            } else {
                // Le compteur qui monte tout de suite : outbox.failed ne bougera
                // que deux heures plus tard, quand les dix essais seront épuisés.
                metriques.messageRefuse(canal, objet);
                log.debug("Outbox : message {} repoussé au {} ({} essai(s))",
                    message.getId(), message.getNextAttemptAt(), message.getAttempts());
            }
        }

        reporterLEnvoiSurLeCompte(message);
        repository.save(message);
    }

    /**
     * L'alerte a-t-elle passé l'âge où elle valait encore la peine d'être
     * envoyée ? Faux tant que le réglage n'est pas armé — voir
     * {@link #echeanceAlerte}.
     */
    private boolean echeanceDepassee(OutboxMessage message, Instant now) {
        if (echeanceAlerte.isZero() || echeanceAlerte.isNegative()
                || message.getPriority() != OutboxService.PRIORITE_ALERTE
                || message.getCreatedAt() == null) {
            return false;
        }
        return !now.isBefore(message.getCreatedAt().plus(echeanceAlerte));
    }

    /**
     * L'issue de la remise au fournisseur, portée sur le compte. Sans effet pour
     * tout autre message qu'un e-mail de vérification.
     *
     * <p>{@code SENT} dès que Resend accepte, {@code FAILED} quand les essais
     * sont épuisés — ou qu'il a refusé tout de suite, ce que produirait un compte
     * d'envoi resté en mode d'essai. Tant que des essais restent, l'état ne bouge
     * pas : {@code PENDING} est exact, et afficher un échec réparable serait
     * inviter à corriger une adresse qui n'a rien.
     *
     * <p><b>Cette méthode a suivi la confirmation.</b> Elle vivait dans
     * {@code OutboxService}, où elle s'exécutait dans la transaction du lot
     * entier : l'écriture sur {@code users} attendait la fin des cinquante appels
     * fournisseur pour être validée, et était annulée si l'un d'eux levait. Elle
     * appartient à la transaction du message qu'elle rapporte, c'est-à-dire ici.
     */
    private void reporterLEnvoiSurLeCompte(OutboxMessage message) {
        if (message.getPurpose() != OutboxPurpose.EMAIL_VERIFICATION
                || message.getUserId() == null) {
            return;
        }
        userRepository.findById(message.getUserId()).ifPresent(user -> {
            if (message.getStatus() == OutboxStatus.SENT) {
                user.setVerificationEmailMessageId(message.getProviderMessageId());
                user.setVerificationEmailDelivery(VerificationEmailDelivery.SENT);
                userRepository.save(user);
            } else if (message.getStatus() == OutboxStatus.FAILED) {
                user.setVerificationEmailDelivery(VerificationEmailDelivery.FAILED);
                userRepository.save(user);
            }
        });
    }
}
