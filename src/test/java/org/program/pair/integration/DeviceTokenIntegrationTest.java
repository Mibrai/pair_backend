package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.dto.DeviceTokenDto;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un appareil suit le compte qui s'y connecte (P-BL-12).
 *
 * <p><b>Le défaut fermé ici, et pourquoi il n'était pas seulement un 500.</b>
 * {@code registerToken} cherchait le couple {@code (compte, jeton)} alors que
 * {@code device_tokens.token} est {@code UNIQUE} : dès que le jeton appartenait
 * à un autre compte, le service repartait sur une insertion, l'index la
 * refusait, et l'appelant recevait {@code 500}. La moitié visible du défaut est
 * que le nouveau compte ne recevait plus aucune notification push. L'autre
 * moitié est la grave : la ligne restait au compte <b>précédent</b>, qui
 * continuait de recevoir les siennes sur cet appareil. Un rappel de séance
 * passe encore ; l'alerte d'une veille retour, ou le code de clôture d'un
 * proche, arrivait sur le téléphone de quelqu'un d'autre.
 *
 * <p>Le cas n'est pas théorique : FCM émet un jeton par <b>installation</b>, pas
 * par compte. Il suffit de deux comptes sur un même téléphone — un compte de
 * test et le sien, un prêt d'appareil — et que le désenregistrement de l'app à
 * la déconnexion ait échoué (réseau coupé, ou session déjà refusée).
 *
 * <p><b>Ce que cette classe ne couvre pas, et par qui.</b> Le détachement à la
 * déconnexion demande un corps {@code LogoutRequest} qui appartient à une autre
 * sous-vague ; le détachement de tous les appareils à la désactivation d'un
 * compte appartient au propriétaire de {@code UserService}. Les deux manquent
 * encore, et ce sont eux qui rendraient le cas ci-dessous rare au lieu de
 * courant.
 *
 * <p>Chaque méthode crée ses propres comptes et ses propres jetons : la base est
 * partagée par toute la suite, et un jeton codé en dur serait réattribué par la
 * méthode suivante.
 */
class DeviceTokenIntegrationTest extends AbstractIntegrationTest {

    @Test
    void unJetonDejaAttacheAUnAutreCompte_doitEtreReattribue_sans500() {
        String comptePrecedent = inscrire("device-a");
        String nouveauCompte = inscrire("device-b");
        String jeton = jetonFcm();

        enregistrer(comptePrecedent, jeton);

        // Le point du défaut : ce second enregistrement rendait 500.
        DeviceTokenDto reattribue = enregistrer(nouveauCompte, jeton);

        assertThat(reattribue).isNotNull();
        assertThat(reattribue.getToken()).isEqualTo(jeton);
    }

    @Test
    void apresReattribution_lesPushDeLAncienCompte_neDoiventPlusViserCetAppareil() {
        String comptePrecedent = inscrire("device-c");
        String nouveauCompte = inscrire("device-d");
        String jeton = jetonFcm();

        enregistrer(comptePrecedent, jeton);
        enregistrer(nouveauCompte, jeton);

        assertThat(jetonsDe(comptePrecedent))
            .as("l'ancien compte ne doit plus adresser cet appareil : c'est par là "
                + "qu'une alerte de veille arrivait sur le téléphone de quelqu'un d'autre")
            .doesNotContain(jeton);
        assertThat(jetonsDe(nouveauCompte)).contains(jeton);
    }

    /**
     * Le jeton voyage dans le chemin de l'URL — donc dans les journaux d'accès et
     * les mandataires. Cette route n'en vérifiait pas le propriétaire : en
     * connaître un suffisait à faire taire les notifications du compte auquel il
     * appartenait.
     */
    @Test
    void unTiers_neDoitPasPouvoirSupprimerLeJetonDAutrui() {
        String proprietaire = inscrire("device-e");
        String tiers = inscrire("device-f");
        String jeton = jetonFcm();

        enregistrer(proprietaire, jeton);

        detacher(tiers, jeton);

        assertThat(jetonsDe(proprietaire))
            .as("le jeton d'un autre compte ne se supprime pas, et le refus est muet")
            .contains(jeton);
    }

    @Test
    void sonProprietaire_doitPouvoirDetacherSonAppareil() {
        String compte = inscrire("device-g");
        String jeton = jetonFcm();

        enregistrer(compte, jeton);
        detacher(compte, jeton);

        assertThat(jetonsDe(compte)).doesNotContain(jeton);
    }

    /**
     * Un jeton inconnu et le jeton d'un autre reçoivent la même réponse : un
     * {@code 404} sur l'un des deux ferait de cette route un oracle d'existence.
     * Le client, lui, n'a rien à distinguer — il vient d'oublier cet appareil.
     */
    @Test
    void unJetonInconnu_doitRendre204CommeLesAutres() {
        String compte = inscrire("device-h");

        detacher(compte, jetonFcm());
    }

    /**
     * Non-régression de la règle que la réécriture pouvait emporter : un
     * ré-enregistrement <b>sans</b> fuseau n'efface pas celui qui était posé. Le
     * chemin de mise à jour a changé de condition d'entrée — le jeton et non plus
     * le couple —, et c'est là que « ne pas écraser avec null » se perd.
     */
    @Test
    void reEnregistrerSonJetonSansFuseau_neDoitPasEffacerCeluiDejaPose() {
        String compte = inscrire("device-i");
        String jeton = jetonFcm();

        DeviceTokenDto premier = enregistrer(compte, jeton, "Europe/Berlin");
        assertThat(premier.getTimezone()).isEqualTo("Europe/Berlin");

        DeviceTokenDto second = enregistrer(compte, jeton, null);

        assertThat(second.getTimezone()).isEqualTo("Europe/Berlin");
    }

    // — helpers —

    /** Un jeton dont on est sûr qu'aucune autre méthode ne l'a déjà enregistré. */
    private static String jetonFcm() {
        return "fcm-" + UUID.randomUUID();
    }

    private String inscrire(String prefixe) {
        AuthResponse auth = webTestClient.post().uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail(prefixe), "Password123!", "Porteur d'appareil"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();
        assertThat(auth).isNotNull();
        return auth.accessToken();
    }

    private DeviceTokenDto enregistrer(String accessToken, String jeton) {
        return enregistrer(accessToken, jeton, "Europe/Paris");
    }

    private DeviceTokenDto enregistrer(String accessToken, String jeton, String fuseau) {
        Map<String, Object> corps = new HashMap<>();
        corps.put("token", jeton);
        corps.put("platform", "ANDROID");
        if (fuseau != null) {
            corps.put("timezone", fuseau);
        }

        return webTestClient.post().uri("/api/notifications/devices")
            .headers(h -> h.setBearerAuth(accessToken))
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(corps)
            .exchange()
            .expectStatus().isOk()
            .expectBody(DeviceTokenDto.class)
            .returnResult().getResponseBody();
    }

    /** Rend {@code 204} dans tous les cas : c'est l'objet de deux des tests. */
    private void detacher(String accessToken, String jeton) {
        webTestClient.delete().uri("/api/notifications/devices/{token}", jeton)
            .headers(h -> h.setBearerAuth(accessToken))
            .exchange()
            .expectStatus().isNoContent();
    }

    private List<String> jetonsDe(String accessToken) {
        List<DeviceTokenDto> appareils = webTestClient.get().uri("/api/notifications/devices")
            .headers(h -> h.setBearerAuth(accessToken))
            .exchange()
            .expectStatus().isOk()
            .expectBodyList(DeviceTokenDto.class)
            .returnResult().getResponseBody();
        assertThat(appareils).isNotNull();
        return appareils.stream().map(DeviceTokenDto::getToken).toList();
    }
}
