package org.program.pair;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Le magasin des règles d'architecture : ce que le code doit respecter et
 * qu'aucun test fonctionnel ne saurait dire.
 *
 * <p><b>Cette classe est faite pour grossir.</b> Elle n'en porte qu'une
 * aujourd'hui, et c'est délibéré : elle est posée par P-BA-04 pour que les
 * fiches suivantes aient un endroit où écrire, plutôt que chacune le sien. Les
 * règles attendues sont déjà nommées par l'audit — que les classes
 * {@code @Service} ne dépendent pas des contrôleurs et que les paquets
 * {@code domain} ne se référencent pas en cercle (P-BA-10), que chaque méthode
 * {@code @Scheduled} porte un {@code @SchedulerLock} nommé et qu'aucun nom ne
 * soit pris deux fois (P-BA-04 lot 2), puis le magasin complet de P-BA-12. Les
 * ajouter ici et pas ailleurs.
 *
 * <p><b>Comment en ajouter une.</b> Un champ {@code static final ArchRule}
 * annoté {@link ArchTest} devient un test à lui seul, dont le nom affiché est
 * celui du champ — d'où la convention de nommage habituelle du dépôt,
 * {@code sujet_doitFaire_quandCondition}, appliquée aux champs. Terminer chaque
 * règle par {@code .because(...)} : c'est ce texte, et non la trace, que lira la
 * personne dont la construction vient de casser.
 *
 * <p><b>Deux réglages qui ne sont pas des détails.</b>
 * <ul>
 *   <li>{@code DoNotIncludeTests} : seules les classes de {@code src/main} sont
 *       importées. Sans lui, les règles s'appliqueraient aussi aux classes de
 *       test, qui ont le droit de faire ce que le code de production n'a pas —
 *       un test d'intégration touche légitimement un contrôleur, un service et
 *       un dépôt dans la même méthode.</li>
 *   <li>{@code CacheMode.PER_CLASS} : les classes importées sont relâchées à la
 *       fin de cette classe au lieu d'être gardées pour toute la JVM. Toute la
 *       suite tourne dans une seule JVM à {@code -Xmx4g}, avec quatre contextes
 *       Spring vivants en même temps ; le 12/09, c'est la mémoire — et le
 *       ramasse-miettes derrière — qui a fait passer la suite de 9 à 37 minutes.
 *       Le cache partagé n'aurait de valeur que s'il y avait plusieurs classes
 *       ArchUnit, et il n'y en a qu'une.</li>
 * </ul>
 */
@AnalyzeClasses(
    packages = "org.program.pair",
    importOptions = ImportOption.DoNotIncludeTests.class,
    cacheMode = CacheMode.PER_CLASS)
class ArchitectureTest {

    /**
     * Quartz ne revient pas par la fenêtre.
     *
     * <p>Le starter {@code spring-boot-starter-quartz} était déclaré au
     * {@code pom.xml} et n'était utilisé par aucune ligne de code ; P-BA-04 l'a
     * retiré. {@code SchedulingConfigIntegrationTest} vérifie que
     * {@code org.quartz.Scheduler} a bien quitté le classpath ; cette règle-ci
     * vérifie l'autre moitié, et c'est celle qui protège l'avenir : que personne
     * n'écrive un {@code Job} ou un {@code Trigger} Quartz en croyant que le
     * dépôt s'en sert. La planification ici est celle de Spring
     * ({@code @EnableScheduling} et {@code config/SchedulingConfig}), et le
     * verrou distribué qui manque sera ShedLock (P-BA-04 lot 2), pas Quartz.
     *
     * <p>Elle passe aujourd'hui, et c'est voulu : une règle posée sur un code
     * déjà conforme est une règle qui restera lisible le jour où elle cassera.
     */
    @ArchTest
    static final ArchRule quartz_doitResterHorsDuCode_quandUneClasseEstEcrite = noClasses()
        .should().dependOnClassesThat().resideInAnyPackage("org.quartz..")
        .because("la planification de ce dépôt est celle de Spring (@EnableScheduling et "
            + "config/SchedulingConfig) ; le starter Quartz a été retiré parce qu'il était "
            + "déclaré et jamais utilisé, et le verrou distribué qui manque sera ShedLock "
            + "(P-BA-04 lot 2)");
}
