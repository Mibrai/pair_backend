package org.program.pair.shared;

import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BS-15 — tout corps de requête structuré d'un contrôleur est validé.
 *
 * <p>Quatre corps portaient des contraintes que rien n'appliquait, faute de
 * {@code @Valid} sur le paramètre : un {@code @NotNull} ou un {@code @Size} posé
 * sur un DTO ne fait rien tant que le contrôleur ne le demande pas. Ce test
 * empêche le cinquième.
 *
 * <p>Un corps brut ({@code String}, {@code byte[]}, {@code Map}) n'a pas de
 * contraintes déclarables et en est exempté. La liste blanche nomme les
 * exceptions voulues.
 */
class CorpsDeRequeteValidesTest {

    /** Lit un corps brut pour en vérifier la signature : rien à valider avant. */
    private static final Set<String> LISTE_BLANCHE = Set.of(
        "org.program.pair.domain.outbox.ResendWebhookController");

    private static final Set<Class<?>> CORPS_BRUTS = Set.of(String.class, byte[].class, Map.class);

    @Test
    void toutCorpsDeRequeteStructure_doitEtreValide() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> nonValides = new ArrayList<>();
        int corpsVus = 0;
        for (BeanDefinition candidate : scanner.findCandidateComponents("org.program.pair")) {
            String className = candidate.getBeanClassName();
            if (LISTE_BLANCHE.contains(className)) {
                continue;
            }
            for (Method method : Class.forName(className).getDeclaredMethods()) {
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.isAnnotationPresent(RequestBody.class)
                            || CORPS_BRUTS.contains(parameter.getType())) {
                        continue;
                    }
                    corpsVus++;
                    if (!parameter.isAnnotationPresent(Valid.class)
                            && !parameter.isAnnotationPresent(Validated.class)) {
                        nonValides.add(className + "#" + method.getName()
                            + "(" + parameter.getType().getSimpleName() + ")");
                    }
                }
            }
        }

        assertThat(corpsVus).as("le balayage trouve bien les contrôleurs").isGreaterThan(20);
        assertThat(nonValides)
            .as("corps de requête sans @Valid : leurs contraintes ne s'appliquent pas")
            .isEmpty();
    }
}
