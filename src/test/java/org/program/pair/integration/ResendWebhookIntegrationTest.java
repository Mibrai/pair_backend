package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.outbox.OutboxDelivery;
import org.program.pair.domain.outbox.OutboxMessage;
import org.program.pair.repository.OutboxMessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'accusé de remise Resend : un webhook fait basculer l'état de remise d'un
 * message de l'outbox.
 *
 * <p>Sous le profil {@code test}, aucun secret n'est configuré : le webhook est
 * traité sans vérification de signature, ce qui laisse éprouver la logique de
 * recoupement et de mise à jour. La vérification Svix elle-même est couverte par
 * un test unitaire distinct.
 */
class ResendWebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxMessageRepository outboxRepository;

    @Test
    void unRebond_faitBasculerLeMessage_enBounced() {
        // Un message e-mail déposé et « envoyé », avec un id fournisseur connu.
        String providerId = "resend-" + UUID.randomUUID();
        OutboxMessage message = OutboxMessage.email(
            "camille@example.org", "Alerte retour — meetDo", "<p>...</p>", 0, null);
        message.markSent(providerId, java.time.Instant.now());
        outboxRepository.saveAndFlush(message);

        webTestClient.post().uri("/public/resend-webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""
                {"type":"email.bounced","data":{"email_id":"%s","to":["camille@example.org"]}}
                """.formatted(providerId))
            .exchange().expectStatus().isOk();

        assertThat(outboxRepository.findByProviderMessageId(providerId))
            .get()
            .extracting(OutboxMessage::getDeliveryState)
            .isEqualTo(OutboxDelivery.BOUNCED);
    }

    @Test
    void unDelivre_faitBasculerEnDelivered() {
        String providerId = "resend-" + UUID.randomUUID();
        OutboxMessage message = OutboxMessage.email(
            "rene@example.org", "Alerte retour — meetDo", "<p>...</p>", 0, null);
        message.markSent(providerId, java.time.Instant.now());
        outboxRepository.saveAndFlush(message);

        webTestClient.post().uri("/public/resend-webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + providerId + "\"}}")
            .exchange().expectStatus().isOk();

        assertThat(outboxRepository.findByProviderMessageId(providerId))
            .get().extracting(OutboxMessage::getDeliveryState)
            .isEqualTo(OutboxDelivery.DELIVERED);
    }

    @Test
    void unRebond_neSeFaitPasEcraserParUnRetardTardif() {
        String providerId = "resend-" + UUID.randomUUID();
        OutboxMessage message = OutboxMessage.email(
            "sam@example.org", "Alerte retour — meetDo", "<p>...</p>", 0, null);
        message.markSent(providerId, java.time.Instant.now());
        outboxRepository.saveAndFlush(message);

        envoyer(providerId, "email.bounced");
        envoyer(providerId, "email.delivery_delayed"); // arrive après, en retard

        assertThat(outboxRepository.findByProviderMessageId(providerId))
            .get().extracting(OutboxMessage::getDeliveryState)
            .isEqualTo(OutboxDelivery.BOUNCED); // le fait terminal tient
    }

    @Test
    void unEvenementInconnu_estAccepte_sansRienChanger() {
        String providerId = "resend-" + UUID.randomUUID();
        OutboxMessage message = OutboxMessage.email(
            "alex@example.org", "Alerte retour — meetDo", "<p>...</p>", 0, null);
        message.markSent(providerId, java.time.Instant.now());
        outboxRepository.saveAndFlush(message);

        envoyer(providerId, "email.opened"); // on ne suit pas les ouvertures

        assertThat(outboxRepository.findByProviderMessageId(providerId))
            .get().extracting(OutboxMessage::getDeliveryState)
            .isEqualTo(OutboxDelivery.UNKNOWN);
    }

    private void envoyer(String providerId, String type) {
        webTestClient.post().uri("/public/resend-webhook")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"type\":\"" + type + "\",\"data\":{\"email_id\":\"" + providerId + "\"}}")
            .exchange().expectStatus().isOk();
    }
}
