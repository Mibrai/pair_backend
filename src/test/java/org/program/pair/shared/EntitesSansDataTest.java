package org.program.pair.shared;

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
 * P-BA-12 — aucune entité n'est annotée {@code @Data}.
 *
 * <p>{@code @Data} génère un {@code equals}, un {@code hashCode} et un
 * {@code toString} sur <b>tous</b> les champs : sur une entité, cela compare et
 * imprime ses associations paresseuses — une requête cachée dans un {@code Set},
 * une boucle infinie dans un journal. Quatre entités le portaient.
 *
 * <p>L'annotation a une rétention {@code SOURCE} : invisible à ArchUnit, qui lit
 * le bytecode. D'où la lecture des fichiers eux-mêmes.
 */
class EntitesSansDataTest {

    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern ENTITE = Pattern.compile("(?m)^@Entity\\b");
    private static final Pattern DATA = Pattern.compile("(?m)^@(lombok\\.)?Data\\b");

    @Test
    void aucuneEntite_nEstAnnoteeData() throws IOException {
        List<Path> entites;
        try (Stream<Path> fichiers = Files.walk(SOURCES)) {
            entites = fichiers.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> ENTITE.matcher(lire(f)).find())
                .toList();
        }

        assertThat(entites).as("le parcours trouve bien les entités").hasSizeGreaterThan(20);
        assertThat(entites)
            .filteredOn(f -> DATA.matcher(lire(f)).find())
            .as("entités annotées @Data")
            .isEmpty();
    }

    private static String lire(Path fichier) {
        try {
            return Files.readString(fichier, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
