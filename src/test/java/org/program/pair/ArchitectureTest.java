package org.program.pair;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.junit.CacheMode;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Le magasin des règles d'architecture : ce que le code doit respecter et
 * qu'aucun test fonctionnel ne saurait dire.
 *
 * <p><b>Cette classe est faite pour grossir.</b> Elle en porte deux : le retrait
 * de Quartz (P-BA-04), et l'interdiction d'{@code IllegalStateException} comme
 * façon de dire un refus dans {@code domain} (P-BA-10). Elle est posée pour que
 * les fiches suivantes aient un endroit où écrire, plutôt que chacune le sien.
 * D'autres règles sont déjà nommées par l'audit — que les classes
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
     * verrou distribué est ShedLock (P-BA-04 lot 2), pas Quartz.
     *
     * <p>Elle passe aujourd'hui, et c'est voulu : une règle posée sur un code
     * déjà conforme est une règle qui restera lisible le jour où elle cassera.
     */
    @ArchTest
    static final ArchRule quartz_doitResterHorsDuCode_quandUneClasseEstEcrite = noClasses()
        .should().dependOnClassesThat().resideInAnyPackage("org.quartz..")
        .because("la planification de ce dépôt est celle de Spring (@EnableScheduling et "
            + "config/SchedulingConfig) ; le starter Quartz a été retiré parce qu'il était "
            + "déclaré et jamais utilisé, et le verrou distribué est ShedLock "
            + "(P-BA-04 lot 2)");

    /**
     * Tout ce qui construit une {@code IllegalStateException} dans un appel
     * d'une seule classe : le prédicat de la règle suivante.
     *
     * <p>{@code isAssignableTo} et non l'égalité : une sous-classe
     * d'{@code IllegalStateException} — il en existe dans le JDK comme chez
     * Spring — se comporterait exactement pareil vis-à-vis du gestionnaire
     * global, donc la règle doit la voir aussi.
     */
    private static final DescribedPredicate<JavaConstructorCall> CONSTRUIT_UNE_ILLEGAL_STATE =
        DescribedPredicate.describe(
            "construit une IllegalStateException",
            appel -> appel.getTargetOwner().isAssignableTo(IllegalStateException.class));

    /**
     * Un refus métier ne se dit pas par {@code IllegalStateException}.
     *
     * <p><b>Pourquoi une règle et pas une relecture.</b> Jusqu'au 12/09,
     * {@code GlobalExceptionHandler} attrapait {@code IllegalStateException} et
     * la rendait en {@code 409 CONFLICT} avec {@code ex.getMessage()} tel quel.
     * Lever cette exception était donc le raccourci le plus court pour obtenir un
     * {@code 409} — trois refus d'{@code ActivityService} l'avaient pris — et le
     * même raccourci envoyait au client le message interne de toute
     * {@code IllegalStateException} <b>imprévue</b>, y compris celles d'Hibernate
     * et de Spring, en la déguisant en conflit métier. Le gestionnaire a été
     * retiré (P-BA-10) : ces exceptions rendent maintenant {@code 500}. Cette
     * règle existe pour que personne ne réécrive le raccourci en croyant qu'il
     * marche encore, et n'obtienne à la place une panne là où il voulait un refus.
     *
     * <p><b>Ce qu'il faut lever à la place</b> : {@code ConflictException} avec un
     * {@code ErrorCode} nommé pour un {@code 409}, {@code ValidationException}
     * pour un {@code 400}, {@code BusinessException} pour un {@code 422} — toutes
     * portent un code stable et un message traduit, ce qu'{@code
     * IllegalStateException} ne sait pas faire.
     *
     * <p><b>Les quatre classes exemptées</b> lèvent sur des états réellement
     * internes, où {@code 500} est la bonne réponse : un type d'horodatage
     * inattendu venu de la base ({@code AfficheService}), un calendrier qu'on
     * n'arrive pas à sérialiser ({@code SlotCalendarService}), une JVM sans
     * {@code HmacSHA256} ({@code ResendWebhookVerifier}) et l'absence de
     * {@code JWT_SECRET} sous un profil de déploiement, qui refuse le démarrage
     * ({@code JwtTokenProvider}). La fiche P-BA-10 n'en nommait que deux, plus
     * {@code ShareToken} qui vit hors de {@code domain} et n'a donc pas besoin
     * d'être cité ; les deux autres sont dans {@code domain.outbox} et
     * {@code domain.auth}. Exempter par nom simple et non par paquet : c'est la
     * classe qu'on justifie, pas son voisinage.
     */
    @ArchTest
    static final ArchRule unRefusMetier_neDoitPasEtreUneIllegalStateException_dansLeDomaine =
        noClasses()
            .that().resideInAPackage("..domain..")
            .and().doNotHaveSimpleName("AfficheService")
            .and().doNotHaveSimpleName("SlotCalendarService")
            .and().doNotHaveSimpleName("ResendWebhookVerifier")
            .and().doNotHaveSimpleName("JwtTokenProvider")
            .should().callConstructorWhere(CONSTRUIT_UNE_ILLEGAL_STATE)
            .because("le gestionnaire qui transformait toute IllegalStateException en 409 avec "
                + "son message technique a été retiré (P-BA-10) : elle rend désormais 500, donc "
                + "elle ne peut plus servir à dire un refus. Pour un refus, lever "
                + "ConflictException (409), ValidationException (400) ou BusinessException (422) "
                + "avec un ErrorCode nommé et sa clé error.* dans les trois bundles. Les quatre "
                + "classes exemptées lèvent sur un état réellement interne, où 500 est juste");

    /**
     * Une entité JPA ne sert jamais de corps de réponse (P-BA-12).
     *
     * <p>{@code ReportController} rendait l'entité {@code Report} — directement,
     * et dans une {@code Page} — alors qu'elle portait les notes internes de
     * modération et l'identité du modérateur. Une entité rendue telle quelle
     * publie tout champ ajouté à sa table, sans que personne l'ait décidé, et
     * déclenche les chargements paresseux au moment de la sérialisation.
     *
     * <p>Le type de retour est parcouru avec ses paramètres génériques :
     * {@code ResponseEntity<Page<Report>>} doit être vu comme {@code Report}.
     */
    @ArchTest
    static final ArchRule unControleur_neDoitJamaisRendreUneEntite =
        methods()
            .that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and().arePublic()
            .should(new ArchCondition<JavaMethod>("ne pas rendre d'entité JPA") {
                @Override
                public void check(JavaMethod methode, ConditionEvents events) {
                    JavaClass entite = entiteDans(methode.getReturnType());
                    if (entite != null) {
                        events.add(SimpleConditionEvent.violated(methode,
                            methode.getFullName() + " rend l'entité " + entite.getSimpleName()));
                    }
                }
            })
            .because("une entité rendue telle quelle publie chaque champ de sa table — notes de "
                + "modération comprises dans le cas de Report — et charge ses associations "
                + "paresseuses à la sérialisation ; rendre un DTO dont la liste de champs est "
                + "une décision");

    /** La première entité trouvée dans un type, paramètres génériques compris. */
    private static JavaClass entiteDans(JavaType type) {
        if (type.toErasure().isAnnotatedWith(Entity.class)) {
            return type.toErasure();
        }
        if (type instanceof JavaParameterizedType parametre) {
            for (JavaType argument : parametre.getActualTypeArguments()) {
                JavaClass trouvee = entiteDans(argument);
                if (trouvee != null) {
                    return trouvee;
                }
            }
        }
        return null;
    }
}
