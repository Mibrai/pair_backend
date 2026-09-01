package org.program.pair.domain.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * L'accusé de remise des e-mails d'alerte, reçu de Resend.
 *
 * <p><b>Une route publique, signée.</b> Resend l'appelle sans identité meetDo ;
 * la confiance repose donc sur la signature Svix, vérifiée par
 * {@link ResendWebhookVerifier}. Sous un profil de déploiement, un webhook non
 * signé — ou mal signé — est rejeté en {@code 401} ; sans secret configuré hors
 * déploiement, la vérification est sautée pour laisser tester la logique, et un
 * avertissement le dit.
 *
 * <p><b>On répond toujours {@code 200} à un webhook accepté</b>, même pour un
 * événement qu'on ne suit pas (ouverture, clic) : un {@code 4xx} ferait rejouer
 * Resend indéfiniment pour rien. Le corps brut est lu tel quel — la signature
 * porte sur les octets exacts, pas sur un objet re-sérialisé.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ResendWebhookController {

    private static final Set<String> PROFILS_DE_DEPLOIEMENT = Set.of("prod", "railway", "staging");

    private final ResendWebhookVerifier verifier;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final Environment environment;

    @PostMapping("/public/resend-webhook")
    public ResponseEntity<Void> receive(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "svix-id", required = false) String svixId,
            @RequestHeader(value = "svix-timestamp", required = false) String svixTimestamp,
            @RequestHeader(value = "svix-signature", required = false) String svixSignature) {

        boolean signatureOk = verifier.verifie(rawBody, svixId, svixTimestamp, svixSignature);
        if (!signatureOk) {
            if (verifier.isConfigured() || sousDeploiement()) {
                // Secret présent et signature invalide, OU déploiement sans secret :
                // dans les deux cas, on ne traite pas un accusé qu'on ne peut pas
                // authentifier.
                log.warn("Webhook Resend rejeté : signature absente ou invalide.");
                return ResponseEntity.status(401).build();
            }
            // Hors déploiement, sans secret : on laisse passer pour les tests, en le disant.
            log.warn("Webhook Resend traité SANS vérification de signature (aucun secret configuré).");
        }

        try {
            JsonNode event = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            String type = event.path("type").asText(null);
            String emailId = event.path("data").path("email_id").asText(null);
            outboxService.recordDelivery(emailId, type);
        } catch (Exception e) {
            // Corps illisible : on l'accepte quand même (200) pour ne pas faire
            // rejouer Resend, mais on le journalise. Un accusé mal formé n'est pas
            // une raison de boucler.
            log.error("Webhook Resend : corps illisible", e);
        }
        return ResponseEntity.ok().build();
    }

    private boolean sousDeploiement() {
        for (String profil : environment.getActiveProfiles()) {
            if (PROFILS_DE_DEPLOIEMENT.contains(profil)) {
                return true;
            }
        }
        return false;
    }
}
