package org.program.pair.domain.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La vérification de signature Svix du webhook Resend.
 *
 * <p>On forge une signature valide avec le même calcul que Svix, et l'on vérifie
 * que le vérificateur l'accepte — et rejette une signature fausse, un horodatage
 * trop vieux, et l'absence de secret.
 */
class ResendWebhookVerifierTest {

    private static final byte[] CLE = "une-cle-secrete-de-webhook-svix!".getBytes(StandardCharsets.UTF_8);
    private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString(CLE);

    private ResendWebhookVerifier verifier(String secret) {
        ResendWebhookVerifier v = new ResendWebhookVerifier();
        ReflectionTestUtils.setField(v, "secret", secret);
        return v;
    }

    private static String signe(String id, String ts, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(CLE, "HmacSHA256"));
            byte[] sig = mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8));
            return "v1," + Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void uneSignatureValide_estAcceptee() {
        String id = "msg_123";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String body = "{\"type\":\"email.delivered\"}";
        assertThat(verifier(SECRET).verifie(body, id, ts, signe(id, ts, body))).isTrue();
    }

    @Test
    void uneSignatureFausse_estRejetee() {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        assertThat(verifier(SECRET).verifie("{}", "msg_123", ts, "v1,ZmF1eA==")).isFalse();
    }

    @Test
    void unCorpsModifie_invalideLaSignature() {
        String id = "msg_123";
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String signature = signe(id, ts, "{\"a\":1}");
        // Le même id/ts mais un corps différent : la signature ne concorde plus.
        assertThat(verifier(SECRET).verifie("{\"a\":2}", id, ts, signature)).isFalse();
    }

    @Test
    void unHorodatageTropVieux_estRejete() {
        String id = "msg_123";
        String vieux = String.valueOf(System.currentTimeMillis() / 1000 - 3600);
        String body = "{}";
        assertThat(verifier(SECRET).verifie(body, id, vieux, signe(id, vieux, body))).isFalse();
    }

    @Test
    void sansSecret_leVerificateurNestPasConfigure() {
        ResendWebhookVerifier v = verifier("");
        assertThat(v.isConfigured()).isFalse();
        assertThat(v.verifie("{}", "id", "1", "v1,x")).isFalse();
    }
}
