package org.program.pair.domain.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Le SMS quand aucun fournisseur n'est configuré : on journalise, et on rend un
 * échec explicite.
 *
 * <p>Actif par défaut ({@code matchIfMissing}), comme le no-op des notifications
 * push. En développement, il fait apparaître dans les logs ce qui serait parti,
 * sans rien envoyer. <b>{@code isEnabled()} rend {@code false}</b> : ce qui
 * dépend d'un vrai canal — la mesure du SLO de remise — sait ainsi qu'il n'a rien
 * à mesurer, plutôt que de croire à un envoi qui n'a pas eu lieu.
 *
 * <p>L'issue rendue est un <b>refus</b>, pas un succès silencieux : un message
 * d'alerte que personne n'envoie ne doit pas être marqué « remis » dans l'outbox.
 * En développement, cela laisse le message en attente — ce qui est la vérité.
 */
@Service
@ConditionalOnProperty(name = "twilio.enabled", havingValue = "false", matchIfMissing = true)
@Slf4j
public class NoOpSmsService implements SmsService {

    @Override
    public SmsSendResult send(String toE164, String body) {
        log.info("[DEV] SMS non envoyé (aucun fournisseur configuré) vers {} : {}", toE164, body);
        return SmsSendResult.refused("Aucun fournisseur SMS configuré (twilio.enabled=false).");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
