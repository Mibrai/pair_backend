package org.program.pair.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-06 — aucune migration à venir ne publie une empreinte ni une adresse réelle.
 *
 * <p>Le dépôt est public. V27 et V33 y ont écrit des empreintes bcrypt valables et
 * les adresses de tiers sur des domaines réels ; elles restent en place, parce que
 * les sommes de contrôle Flyway en dépendent (décision P-BS/D6 option C), et les
 * mots de passe correspondants sont réputés brûlés. Ce test garde la suite de la
 * chaîne : à partir de V106, une empreinte ou une adresse hors domaine réservé
 * fait échouer la suite, et donc la CI, sans relecture humaine.
 *
 * <p>Domaines réservés : ceux de la RFC 2606/6761 ({@code .test}, {@code .example},
 * {@code .invalid}, {@code .localhost}, {@code example.com|net|org}). Une adresse
 * de démonstration se range là, jamais sur un domaine qui peut recevoir du courrier.
 */
class MigrationsSansSecretTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** Dernière migration écrite avant la convention : les suivantes y sont soumises. */
    private static final int DERNIERE_VERSION_HISTORIQUE = 105;

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
    private static final Pattern ADRESSE =
        Pattern.compile("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+)");

    private static final Set<String> SUFFIXES_RESERVES =
        Set.of(".test", ".example", ".invalid", ".localhost");
    private static final Set<String> DOMAINES_RESERVES =
        Set.of("example.com", "example.net", "example.org");

    @Test
    void aucuneMigrationRecente_neContientDEmpreinteNiDAdresseReelle() throws IOException {
        List<String> fautes = new ArrayList<>();
        int examinees = 0;

        try (Stream<Path> fichiers = Files.list(MIGRATIONS)) {
            for (Path fichier : fichiers.sorted().toList()) {
                Matcher version = VERSION.matcher(fichier.getFileName().toString());
                if (!version.matches() || Integer.parseInt(version.group(1)) <= DERNIERE_VERSION_HISTORIQUE) {
                    continue;
                }
                examinees++;
                fautes.addAll(fautesDe(fichier));
            }
        }

        assertThat(examinees).as("le test lit bien les migrations récentes").isPositive();
        assertThat(fautes)
            .as("empreinte ou adresse réelle dans une migration d'un dépôt public")
            .isEmpty();
    }

    @Test
    void laDetection_reconnaitUneEmpreinteEtUneAdresseReelle() {
        // Contre-épreuve : un test de garde qui ne sait rien détecter passe toujours.
        String sql = "UPDATE users SET password_hash = '$2a$12$" + "a".repeat(53) + "'"
            + " WHERE email = 'quelqu.un@gmx.de';"
            + " -- demo@meetdo.test et admin@example.com restent permis";

        List<String> fautes = fautesDans("V999__exemple.sql", sql);

        assertThat(fautes).hasSize(2);
        assertThat(fautes).anyMatch(f -> f.contains("empreinte"));
        assertThat(fautes).anyMatch(f -> f.contains("gmx.de"));
    }

    private static List<String> fautesDe(Path fichier) throws IOException {
        return fautesDans(fichier.getFileName().toString(),
            Files.readString(fichier, StandardCharsets.UTF_8));
    }

    private static List<String> fautesDans(String nom, String contenu) {
        List<String> fautes = new ArrayList<>();
        if (BCRYPT.matcher(contenu).find()) {
            fautes.add(nom + " : empreinte bcrypt");
        }
        Matcher adresse = ADRESSE.matcher(contenu);
        while (adresse.find()) {
            String domaine = adresse.group(1).toLowerCase(Locale.ROOT);
            if (!estReserve(domaine)) {
                fautes.add(nom + " : adresse sur un domaine réel (" + domaine + ")");
            }
        }
        return fautes;
    }

    private static boolean estReserve(String domaine) {
        return DOMAINES_RESERVES.contains(domaine)
            || SUFFIXES_RESERVES.stream().anyMatch(domaine::endsWith);
    }
}
