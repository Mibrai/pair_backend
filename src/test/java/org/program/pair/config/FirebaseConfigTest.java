package org.program.pair.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D'où viennent les identifiants Firebase.
 *
 * <p>Seule la <b>résolution de la source</b> est vérifiée : initialiser
 * réellement Firebase demanderait un vrai compte de service et un appel réseau.
 * C'est aussi la seule partie qui casse en pratique — un secret mal collé, une
 * variable oubliée.
 */
class FirebaseConfigTest {

    private static final String JSON = "{\"type\":\"service_account\",\"project_id\":\"meetdo\"}";

    @Test
    void base64Valide_doitRendreLeJsonDecode() throws Exception {
        String encoded = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        FirebaseConfig.CredentialsSource source = FirebaseConfig.resolveCredentials(encoded, "");

        assertThat(source.label()).isEqualTo("base64");
        assertThat(read(source)).isEqualTo(JSON);
    }

    @Test
    void base64EntoureDEspaces_doitQuandMemeSeDecoder() throws Exception {
        // Une variable d'environnement collée depuis un terminal traîne presque
        // toujours un retour à la ligne : sans le trim, le décodage échoue sur
        // un message qui ne dit rien de la cause.
        String encoded = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        FirebaseConfig.CredentialsSource source =
            FirebaseConfig.resolveCredentials("  \n" + encoded + "\n  ", "");

        assertThat(read(source)).isEqualTo(JSON);
    }

    @Test
    void base64Invalide_doitNommerLaVariable_etTaireSonContenu() {
        String secret = "!!!ceci-n-est-pas-du-base64!!!";

        assertThatThrownBy(() -> FirebaseConfig.resolveCredentials(secret, ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FIREBASE_CREDENTIALS_BASE64")
            // Un secret mal collé est l'erreur la plus probable : le message doit
            // la nommer. Il ne doit pas pour autant recopier la valeur, qui part
            // dans les journaux.
            .hasMessageNotContaining(secret);
    }

    @Test
    void aucuneSource_doitRefuserLeDemarrage_enNommantLesDeuxVariables() {
        assertThatThrownBy(() -> FirebaseConfig.resolveCredentials("", ""))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("FIREBASE_CREDENTIALS_BASE64")
            .hasMessageContaining("FIREBASE_CREDENTIALS_PATH");
    }

    @Test
    void sansBase64_doitRetomberSurLeChemin_quiPeutFigurerDansLesJournaux() {
        FirebaseConfig.CredentialsSource source =
            FirebaseConfig.resolveCredentials("", "/etc/secrets/firebase.json");

        // Le chemin n'est pas un secret, contrairement au base64 : il sert
        // d'étiquette pour dire quelle source a servi.
        assertThat(source.label()).isEqualTo("/etc/secrets/firebase.json");
    }

    @Test
    void lesDeuxRenseignes_doiventDonnerLaPrioriteAuBase64() {
        // Ordre voulu : la production pose le base64, et un chemin resté dans la
        // configuration locale ne doit pas la détourner vers un fichier absent.
        String encoded = Base64.getEncoder().encodeToString(JSON.getBytes(StandardCharsets.UTF_8));

        FirebaseConfig.CredentialsSource source =
            FirebaseConfig.resolveCredentials(encoded, "/etc/secrets/firebase.json");

        assertThat(source.label()).isEqualTo("base64");
    }

    private static String read(FirebaseConfig.CredentialsSource source) throws Exception {
        try (InputStream stream = source.open()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
