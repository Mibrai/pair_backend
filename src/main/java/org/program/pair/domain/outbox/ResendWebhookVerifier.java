package org.program.pair.domain.outbox;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Vérifie la signature d'un webhook Resend — schéma Svix.
 *
 * <p><b>Pourquoi une signature sur une route publique.</b> Le webhook n'écrit
 * qu'un état de remise, pas une donnée sensible ; mais sans signature, n'importe
 * qui pourrait marquer une alerte « délivrée » alors qu'elle a rebondi, ou
 * l'inverse — et faire mentir à l'app le seul retour dont elle dispose sur un
 * canal unique. La signature est ce qui rend ce retour digne de confiance.
 *
 * <p><b>Le secret n'a pas de valeur de repli.</b> Comme le poivre et la clé JWT :
 * sous un profil de déploiement, un webhook non signé n'est pas traité (le
 * contrôleur rend 401). Hors déploiement, faute de secret, la vérification est
 * sautée — pour pouvoir tester la logique sans forger une signature Svix — et un
 * avertissement le dit.
 *
 * <p>Le calcul, à l'identique de Svix : {@code HMAC-SHA256} sur
 * {@code "<svix-id>.<svix-timestamp>.<corps brut>"}, clé = base64 du secret
 * débarrassé de son préfixe {@code whsec_}, comparé en temps constant à l'une des
 * signatures listées dans l'en-tête {@code svix-signature} (entrées
 * {@code v1,<base64>} séparées par des espaces).
 */
@Component
@Slf4j
public class ResendWebhookVerifier {

    private static final String PREFIXE = "whsec_";
    /** Tolérance sur l'horodatage, contre le rejeu. */
    private static final long TOLERANCE_SECONDES = 5 * 60;

    @Value("${resend.webhook.secret:}")
    private String secret;

    /** Vrai si un secret est configuré — donc si la vérification est réellement exigible. */
    public boolean isConfigured() {
        return secret != null && !secret.isBlank();
    }

    /**
     * La signature est-elle valide pour ce corps et ces en-têtes ?
     *
     * <p>Rend {@code false} si le secret est configuré et que rien ne concorde ;
     * lève {@link IllegalStateException} n'est pas nécessaire — l'appelant décide
     * quoi faire d'un {@code false} selon le profil.
     */
    public boolean verifie(String rawBody, String svixId, String svixTimestamp, String svixSignature) {
        if (!isConfigured()) {
            return false;
        }
        if (svixId == null || svixTimestamp == null || svixSignature == null) {
            return false;
        }
        if (horsTolerance(svixTimestamp)) {
            log.warn("Webhook Resend : horodatage hors tolérance, rejeté.");
            return false;
        }

        byte[] cle;
        try {
            String base = secret.startsWith(PREFIXE) ? secret.substring(PREFIXE.length()) : secret;
            cle = Base64.getDecoder().decode(base);
        } catch (IllegalArgumentException e) {
            log.error("Secret de webhook Resend illisible (pas du base64).");
            return false;
        }

        String signedContent = svixId + "." + svixTimestamp + "." + rawBody;
        String attendu = base64Hmac(cle, signedContent);

        for (String partie : svixSignature.split("\\s+")) {
            int virgule = partie.indexOf(',');
            String sig = virgule >= 0 ? partie.substring(virgule + 1) : partie;
            if (MessageDigest.isEqual(
                    attendu.getBytes(StandardCharsets.UTF_8),
                    sig.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    private boolean horsTolerance(String svixTimestamp) {
        try {
            long t = Long.parseLong(svixTimestamp.trim());
            long maintenant = System.currentTimeMillis() / 1000;
            return Math.abs(maintenant - t) > TOLERANCE_SECONDES;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private static String base64Hmac(byte[] cle, String contenu) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cle, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(
                mac.doFinal(contenu.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 indisponible", e);
        }
    }
}
