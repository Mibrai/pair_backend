package org.program.pair.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Set;

/**
 * Les deux ensembles de profils Spring dont le code a besoin, et la seule façon
 * de demander si l'un d'eux est actif.
 *
 * <p><b>Pourquoi deux ensembles, et pas un seul.</b> Le dépôt les confondait, et
 * la confusion a coûté une fuite. {@code JwtTokenProvider} connaissait un
 * ensemble « de déploiement » contenant {@code staging}, parce que sous staging
 * l'absence de clé de signature doit bien faire échouer le démarrage : une clé
 * tirée au hasard y serait une porte ouverte comme ailleurs. Le seeder de
 * démonstration, lui, a besoin de l'inverse — il doit rester permis sous
 * staging, où {@code AdminSeedController} est justement exposé, et interdit
 * partout où il y a de vrais comptes. Un ensemble unique aurait donc soit
 * autorisé le seed en production, soit cassé staging. D'où {@link #PRODUCTION},
 * qui dit « il y a ici de vraies données », et {@link #DEPLOIEMENT}, qui dit
 * « il n'y a ici personne pour lire un journal de démarrage ».
 *
 * <p><b>Pourquoi {@code Environment} et pas {@code spring.profiles.active}.</b>
 * L'ancien garde-fou du seeder lisait la propriété
 * {@code spring.profiles.active} en chaîne et la découpait sur les virgules. Ce
 * n'est pas la même chose : un profil activé par
 * {@code SPRING_PROFILES_ACTIVE}, par un {@code spring.profiles.include}, par un
 * {@code @ActiveProfiles} de test ou par un {@code ApplicationContextInitializer}
 * n'apparaît pas forcément dans cette propriété, alors qu'il est bel et bien
 * actif. Une garde qui interroge la chaîne peut donc trouver la liste vide sous
 * un profil de production. {@code Environment.getActiveProfiles()} est la seule
 * réponse qui corresponde à ce que Spring a réellement activé.
 *
 * <p>La comparaison est <b>sensible à la casse</b>, comme celle de Spring : un
 * profil s'appelle exactement par son nom. Les noms voisins ne comptent pas non
 * plus — {@code production} n'est pas {@code prod}. Si un environnement nouveau
 * apparaît, il s'ajoute ici, en un seul endroit, et tous les appelants en
 * héritent du même coup : c'est la raison d'être de cette classe.
 */
public final class Profils {

    /**
     * Là où vivent de vraies données et de vrais comptes : rien de fictif ne
     * doit y être créé, rien de destructif ne doit y être lancé.
     *
     * <p>{@code railway} est le nom du profil de l'hébergeur, et il est aussi
     * la production : c'est précisément son absence de cet ensemble — le code ne
     * connaissait que {@code prod} — qui a laissé vingt comptes de démonstration
     * au mot de passe publié se créer en production (fiche P-BS-02).
     */
    public static final Set<String> PRODUCTION = Set.of("prod", "railway");

    /**
     * Tout ce qui est déployé, staging compris : ce qui manque ici doit faire
     * échouer le démarrage plutôt que se replier sur une valeur de secours.
     *
     * <p>Un secret absent y est une erreur de configuration, pas une commodité
     * de développement — voir {@code JwtTokenProvider}.
     */
    public static final Set<String> DEPLOIEMENT = Set.of("prod", "railway", "staging");

    private Profils() {
    }

    /**
     * Dit si l'un des profils de {@code ensemble} est actif.
     *
     * @param environment l'environnement Spring, seule source de vérité sur les
     *                    profils réellement actifs
     * @param ensemble    {@link #PRODUCTION} ou {@link #DEPLOIEMENT}
     */
    public static boolean actif(Environment environment, Set<String> ensemble) {
        if (environment == null) {
            return false;
        }
        return Arrays.stream(environment.getActiveProfiles()).anyMatch(ensemble::contains);
    }

    /**
     * Le premier profil actif qui appartient à {@code ensemble}, ou
     * {@code null}.
     *
     * <p>Sert aux messages de refus : « profil de production détecté » oblige à
     * relire la configuration pour savoir lequel, alors que « profil de
     * production détecté : railway » désigne le fichier à ouvrir. Un refus de
     * démarrage n'est lu qu'une fois, dans l'urgence, et il doit suffire.
     */
    public static String premierProfilActif(Environment environment, Set<String> ensemble) {
        if (environment == null) {
            return null;
        }
        return Arrays.stream(environment.getActiveProfiles())
            .filter(ensemble::contains)
            .findFirst()
            .orElse(null);
    }
}
