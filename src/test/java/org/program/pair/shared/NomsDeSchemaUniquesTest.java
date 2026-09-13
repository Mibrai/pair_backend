package org.program.pair.shared;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-17 — deux classes du contrat ne portent jamais le même nom simple.
 *
 * <p>springdoc nomme un schéma par le nom simple de sa classe, et n'en garde
 * qu'un. Deux {@code VisibilityRequest} — {@code {visibility}} pour une
 * carte-souvenir, {@code {visible}} pour une activité — se partageaient donc un
 * seul schéma : la spec annonçait pour l'une le champ de l'autre, et un client
 * qui la suivait envoyait un corps que le serveur ignorait sans erreur.
 *
 * <p>Le parcours part des corps de requête et des types de retour de chaque
 * contrôleur, et descend dans les champs de toute classe du dépôt qu'il
 * rencontre, paramètres génériques compris.
 */
class NomsDeSchemaUniquesTest {

    private static final String RACINE = "org.program.pair";

    @Test
    void aucunNomDeSchema_nEstPorteParDeuxClasses() throws ClassNotFoundException {
        Set<Class<?>> vues = new HashSet<>();
        Deque<Type> aVisiter = new ArrayDeque<>();

        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition candidat : scanner.findCandidateComponents(RACINE)) {
            for (Method methode : Class.forName(candidat.getBeanClassName()).getDeclaredMethods()) {
                if (!Modifier.isPublic(methode.getModifiers())) {
                    continue;
                }
                aVisiter.add(methode.getGenericReturnType());
                for (Parameter parametre : methode.getParameters()) {
                    if (parametre.isAnnotationPresent(RequestBody.class)) {
                        aVisiter.add(parametre.getParameterizedType());
                    }
                }
            }
        }

        while (!aVisiter.isEmpty()) {
            Type type = aVisiter.pop();
            if (type instanceof ParameterizedType parametre) {
                aVisiter.add(parametre.getRawType());
                aVisiter.addAll(java.util.List.of(parametre.getActualTypeArguments()));
            } else if (type instanceof GenericArrayType tableau) {
                aVisiter.add(tableau.getGenericComponentType());
            } else if (type instanceof WildcardType joker) {
                aVisiter.addAll(java.util.List.of(joker.getUpperBounds()));
            } else if (type instanceof Class<?> classe) {
                Class<?> reelle = classe.isArray() ? classe.getComponentType() : classe;
                if (!reelle.getName().startsWith(RACINE) || !vues.add(reelle)) {
                    continue;
                }
                for (Field champ : reelle.getDeclaredFields()) {
                    if (!Modifier.isStatic(champ.getModifiers())) {
                        aVisiter.add(champ.getGenericType());
                    }
                }
            }
        }

        Map<String, Set<String>> parNom = new TreeMap<>();
        for (Class<?> classe : vues) {
            parNom.computeIfAbsent(classe.getSimpleName(), n -> new TreeSet<>()).add(classe.getName());
        }
        parNom.values().removeIf(classes -> classes.size() < 2);

        assertThat(vues).as("le parcours atteint bien les DTO").hasSizeGreaterThan(50);
        assertThat(parNom)
            .as("noms simples portés par plusieurs classes du contrat : springdoc n'en publie qu'un")
            .isEmpty();
    }
}
