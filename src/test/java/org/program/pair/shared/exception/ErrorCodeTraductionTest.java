package org.program.pair.shared.exception;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le contrat de vocabulaire des trois bundles, tenu par un test plutôt que par
 * une relecture (P-BA-11).
 *
 * <p><b>Sans liste gelée de manquants.</b> La fiche P-BA-11 prévoyait de figer
 * les 49 codes sans traduction et de faire décroître la liste ; les clés ayant
 * été écrites, il n'y a plus rien à geler, et un test sans tolérance vaut mieux
 * qu'un compteur : un code ajouté sans ses trois clés échoue ici, le jour même.
 *
 * <p><b>La seule exception est structurelle, et elle joue dans l'autre sens.</b>
 * Les cinq codes <i>génériques</i> — ceux que {@code GlobalExceptionHandler}
 * déduit du type d'exception quand le refus n'en porte aucun — ne doivent
 * <b>pas</b> avoir de clé (P-BA-11 étape 4). {@code messageOf} cherche
 * {@code error.<CODE>} sur le code résolu : une clé {@code error.CONFLICT}
 * remplacerait d'un texte vague le message propre de chacun des ~70 refus qui
 * n'ont pas encore de code nommé, et le remplacerait en silence. Le test exige
 * donc leur absence, et cette exigence tombera d'elle-même quand ces refus
 * auront tous un code : il ne restera alors plus rien à écraser.
 *
 * <p><b>Unitaire, sans contexte Spring.</b> Deux lectures se complètent :
 * <ul>
 *   <li>les fichiers, lus un par un, pour la <b>présence</b> d'une clé dans
 *       chaque langue. Un {@code MessageSource} ne peut pas répondre à cette
 *       question : son repli sur le français masque exactement le trou qu'on
 *       cherche — une clé absente de {@code messages_de} rend un texte, en
 *       français ;</li>
 *   <li>un {@link ResourceBundleMessageSource} monté à la main, pour le
 *       <b>rendu</b> : il prouve que chaque texte se compose réellement, ce
 *       qu'un ensemble de clés ne dit pas (un motif {@code MessageFormat} mal
 *       formé ne se voit qu'à la composition).</li>
 * </ul>
 */
class ErrorCodeTraductionTest {

    private static final Locale FRANCAIS = Locale.forLanguageTag("fr");
    private static final Locale ANGLAIS = Locale.forLanguageTag("en");
    private static final Locale ALLEMAND = Locale.forLanguageTag("de");

    /** Bundle → nom du fichier, dans l'ordre où on les cite dans les échecs. */
    private static final Map<String, String> BUNDLES = Map.of(
        "fr", "messages.properties",
        "en", "messages_en.properties",
        "de", "messages_de.properties");

    /**
     * Les codes que {@code GlobalExceptionHandler} déduit du type d'exception,
     * et qui servent donc de repli à tout refus levé sans code explicite.
     *
     * <p>La liste n'est pas une tolérance : c'est l'inverse. Le test vérifie
     * qu'aucun de ces cinq-là n'a de clé, dans aucune des trois langues.
     */
    private static final Set<ErrorCode> GENERIQUES = Set.of(
        ErrorCode.VALIDATION_ERROR,
        ErrorCode.NOT_FOUND,
        ErrorCode.FORBIDDEN,
        ErrorCode.CONFLICT,
        ErrorCode.BUSINESS_RULE_VIOLATION);

    @Test
    void chaqueErrorCode_aSaTraductionEnFrancaisEnAnglaisEtEnAllemand() {
        Map<String, Set<String>> cles = clesParLangue();

        List<String> manquantes = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            if (GENERIQUES.contains(code)) {
                continue;
            }
            for (String langue : new TreeSet<>(BUNDLES.keySet())) {
                if (!cles.get(langue).contains("error." + code.name())) {
                    manquantes.add(langue + " : error." + code.name());
                }
            }
        }

        assertThat(manquantes)
            .as("un code d'erreur sans traduction rend le message brut de l'exception, "
                + "en français, quelle que soit la langue demandée — ajoutez la clé dans "
                + "les trois bundles en même temps que le code")
            .isEmpty();
    }

    @Test
    void lesCinqCodesGeneriques_nOntVolontairementAucuneCle() {
        Map<String, Set<String>> cles = clesParLangue();

        List<String> indues = new ArrayList<>();
        for (ErrorCode code : GENERIQUES) {
            for (String langue : new TreeSet<>(BUNDLES.keySet())) {
                if (cles.get(langue).contains("error." + code.name())) {
                    indues.add(langue + " : error." + code.name());
                }
            }
        }

        assertThat(indues)
            .as("messageOf cherche error.<CODE> sur le code RÉSOLU : une clé sur un code "
                + "générique écrase d'un texte vague le message de tous les refus qui n'ont "
                + "pas encore de code nommé. Donnez un code propre (ou une messageKey) au "
                + "refus, pas une clé au code générique")
            .isEmpty();
    }

    @Test
    void lesTroisBundles_ontExactementLesMemesCles() {
        Map<String, Set<String>> cles = clesParLangue();
        Set<String> reference = cles.get("fr");

        for (String langue : new TreeSet<>(BUNDLES.keySet())) {
            if (langue.equals("fr")) {
                continue;
            }
            Set<String> absentes = new TreeSet<>(reference);
            absentes.removeAll(cles.get(langue));
            Set<String> enTrop = new TreeSet<>(cles.get(langue));
            enTrop.removeAll(reference);

            assertThat(absentes)
                .as("clés du français absentes de " + BUNDLES.get(langue)
                    + " : elles retomberaient sur le français sans que rien ne le signale")
                .isEmpty();
            assertThat(enTrop)
                .as("clés de " + BUNDLES.get(langue) + " absentes du français, qui est le "
                    + "repli de toutes les langues : elles n'existent pour personne d'autre")
                .isEmpty();
        }
    }

    /**
     * Le piège {@code MessageFormat}, vérifié à la machine.
     *
     * <p>Dans une clé qui porte un argument {@code {n}}, une apostrophe simple
     * est lue comme un échappement : le rendu perd l'apostrophe <b>et</b>
     * l'argument n'est jamais substitué. Le lot 0 a corrigé exactement ce défaut
     * sur {@code push.WAITLIST_PROMOTED.title}, où « Une place s'est libérée :
     * {0} » rendait « Une place sest libérée : {0} ».
     *
     * <p>La règle ne vaut que pour les clés à argument :
     * {@code alwaysUseMessageFormat} n'est pas posé, donc une clé sans argument
     * garde son apostrophe simple — et doit la garder, sans quoi elle
     * afficherait deux apostrophes.
     */
    @Test
    void uneCleAArgument_neDoitJamaisPorterDApostropheSimple() {
        List<String> fautives = new ArrayList<>();

        for (String fichier : new TreeSet<>(BUNDLES.values())) {
            for (String ligne : lignes(fichier)) {
                if (ligne.startsWith("#") || ligne.startsWith("!") || !ligne.contains("=")) {
                    continue;
                }
                String valeur = ligne.substring(ligne.indexOf('=') + 1);
                if (!valeur.matches(".*\\{\\d.*")) {
                    continue;
                }
                // Les paires doublées sont correctes : ne reste que l'isolée.
                if (valeur.replace("''", "").contains("'")) {
                    fautives.add(fichier + " → " + ligne);
                }
            }
        }

        assertThat(fautives)
            .as("apostrophe simple dans une clé à argument : MessageFormat l'avale et "
                + "ne substitue plus l'argument. Doublez-la ('')")
            .isEmpty();
    }

    @Test
    void chaqueTexteDErreur_seComposeReellementDansLesTroisLangues() {
        ResourceBundleMessageSource source = messageSource();

        for (ErrorCode code : ErrorCode.values()) {
            if (GENERIQUES.contains(code)) {
                continue;
            }
            for (Locale locale : List.of(FRANCAIS, ANGLAIS, ALLEMAND)) {
                String texte = source.getMessage("error." + code.name(), null, locale);
                assertThat(texte)
                    .as("error." + code.name() + " en " + locale.getLanguage())
                    .isNotBlank();
            }
        }
    }

    /**
     * Les quatre textes de {@code SCHEDULE_CHANGED} (P-BL-06), vérifiés à leur
     * rendu et pas seulement à leur présence.
     *
     * <p>Ce type est {@code CRITICAL} et {@code EMAILED} depuis toujours, sans
     * aucun texte : l'émettre sans ces clés donnait « Nouvelle notification » en
     * titre. Les corps portent des arguments, donc passent par
     * {@code MessageFormat} — c'est là que l'apostrophe se paie, et ce test le
     * prouve en exigeant que chaque argument ressorte substitué.
     */
    @Test
    void lesTextesDeModificationDeCreneau_substituentLeursArguments() {
        ResourceBundleMessageSource source = messageSource();

        for (Locale locale : List.of(FRANCAIS, ANGLAIS, ALLEMAND)) {
            assertThat(source.getMessage("push.SCHEDULE_CHANGED.title",
                new Object[] {"Yoga du mardi"}, locale))
                .as("titre en " + locale.getLanguage())
                .contains("Yoga du mardi");

            assertThat(source.getMessage("push.SCHEDULE_CHANGED.body.time",
                new Object[] {"18:00", "19:00"}, locale))
                .as("corps « heure » en " + locale.getLanguage())
                .contains("18:00").contains("19:00");

            assertThat(source.getMessage("push.SCHEDULE_CHANGED.body.place",
                new Object[] {"Parc Monceau", "Gymnase Jean Jaurès"}, locale))
                .as("corps « lieu » en " + locale.getLanguage())
                .contains("Parc Monceau").contains("Gymnase Jean Jaurès");

            assertThat(source.getMessage("push.SCHEDULE_CHANGED.body.both",
                new Object[] {"18:00", "Parc Monceau", "19:00", "Gymnase Jean Jaurès"}, locale))
                .as("corps « heure et lieu » en " + locale.getLanguage())
                .contains("18:00").contains("Parc Monceau")
                .contains("19:00").contains("Gymnase Jean Jaurès");
        }
    }

    /**
     * Le même {@code MessageSource} que la production, monté à la main : mêmes
     * bundles, même encodage, et surtout même {@code fallbackToSystemLocale =
     * false} — sans quoi une clé absente irait chercher la langue de la JVM qui
     * lance les tests, et le résultat dépendrait de la machine.
     */
    private static ResourceBundleMessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        source.setDefaultLocale(FRANCAIS);
        return source;
    }

    /**
     * Les clés de chaque bundle, lues fichier par fichier.
     *
     * <p>Volontairement sans {@code Properties.load} : on veut les clés telles
     * qu'elles sont écrites, dans l'ordre du fichier, pour pouvoir citer la
     * ligne fautive dans l'échec.
     */
    private static Map<String, Set<String>> clesParLangue() {
        Map<String, Set<String>> parLangue = new LinkedHashMap<>();
        BUNDLES.forEach((langue, fichier) -> {
            Set<String> cles = new LinkedHashSet<>();
            for (String ligne : lignes(fichier)) {
                if (ligne.isBlank() || ligne.startsWith("#") || ligne.startsWith("!")
                    || !ligne.contains("=")) {
                    continue;
                }
                cles.add(ligne.substring(0, ligne.indexOf('=')).strip());
            }
            parLangue.put(langue, cles);
        });
        return parLangue;
    }

    private static List<String> lignes(String fichier) {
        try (InputStream flux = ErrorCodeTraductionTest.class.getClassLoader()
                 .getResourceAsStream(fichier)) {
            if (flux == null) {
                throw new IllegalStateException(fichier + " est introuvable sur le classpath");
            }
            try (BufferedReader lecteur = new BufferedReader(
                     new InputStreamReader(flux, StandardCharsets.UTF_8))) {
                return lecteur.lines().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("lecture de " + fichier, e);
        }
    }
}
