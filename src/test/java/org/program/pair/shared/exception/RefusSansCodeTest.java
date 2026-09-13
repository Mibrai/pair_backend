package org.program.pair.shared.exception;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-11, étape 6 — le nombre de refus métier construits sans code ne peut que baisser.
 *
 * <p>Un refus sans code retombe sur le code générique de son type et sur son message
 * brut, en français, quelle que soit la langue du client. Il en reste beaucoup ; les
 * convertir se fait domaine par domaine, en vérifiant d'abord que l'app ne lit pas le
 * code à changer. Ce gel empêche d'en ajouter en attendant : à chaque conversion,
 * baisser {@link #PLAFOND} du nombre de refus convertis.
 */
class RefusSansCodeTest {

    /**
     * Relevé le 13/09/2026 : 193 refus sans code, tous convertis — code générique
     * gardé, messageKey traduite, avec arguments pour les huit qui portent une
     * valeur. Zéro : tout nouveau refus naît avec un code ou une clé.
     */
    static final int PLAFOND = 0;

    private static final Pattern REFUS_SANS_CODE = Pattern.compile(
        "new (BusinessException|ValidationException|ConflictException|ForbiddenException"
            + "|ResourceNotFoundException)\\(\\s*\"");

    @Test
    void leNombreDeRefusSansCode_nAugmenteJamais() throws IOException {
        long compte;
        try (Stream<Path> fichiers = Files.walk(Path.of("src/main/java"))) {
            compte = fichiers.filter(f -> f.toString().endsWith(".java"))
                .mapToLong(RefusSansCodeTest::occurrences)
                .sum();
        }

        assertThat(compte)
            .as("refus construits sans ErrorCode — donnez un code, ou une messageKey si l'app lit "
                + "le code générique")
            .isLessThanOrEqualTo(PLAFOND);
    }

    private static long occurrences(Path fichier) {
        try {
            Matcher m = REFUS_SANS_CODE.matcher(Files.readString(fichier, StandardCharsets.UTF_8));
            long n = 0;
            while (m.find()) {
                n++;
            }
            return n;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
