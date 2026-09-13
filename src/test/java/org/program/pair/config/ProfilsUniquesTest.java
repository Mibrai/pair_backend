package org.program.pair.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-07 — une seule liste des profils de déploiement : {@link Profils}.
 *
 * <p>L'ensemble {@code {"prod", "railway", "staging"}} existait en trois copies,
 * et le seed de démonstration ignorait la sienne : il a démarré en production.
 * Ce test refuse qu'une classe réécrive une liste de profils au lieu de lire
 * celle-ci.
 */
class ProfilsUniquesTest {

    private static final Pattern LISTE_DE_PROFILS =
        Pattern.compile("Set\\.of\\(\\s*\"(prod|railway|staging)\"");

    @Test
    void aucuneClasse_neReecritLaListeDesProfilsDeDeploiement() throws IOException {
        List<Path> copies;
        try (Stream<Path> fichiers = Files.walk(Path.of("src/main/java"))) {
            copies = fichiers
                .filter(f -> f.toString().endsWith(".java"))
                .filter(f -> !f.getFileName().toString().equals("Profils.java"))
                .filter(f -> LISTE_DE_PROFILS.matcher(lire(f)).find())
                .toList();
        }

        assertThat(copies).as("classes qui portent leur propre liste de profils").isEmpty();
    }

    private static String lire(Path fichier) {
        try {
            return Files.readString(fichier, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
