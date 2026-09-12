package org.program.pair.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.config.Profils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Orchestrateur principal pour l'exécution des seeders au démarrage de l'application.
 * <p>
 * Ce composant s'exécute automatiquement via CommandLineRunner et contrôle
 * l'exécution des différents seeders selon la configuration et le profil Spring actif.
 * <p>
 * <b>Garde-fou de sécurité</b> : les données de démonstration sont interdites
 * sous tout profil de {@link Profils#PRODUCTION}, et une
 * {@link IllegalStateException} fait échouer le démarrage si la règle est
 * violée. Le refus doit rester un échec de démarrage et non un simple saut du
 * seeder : une configuration qui demande des comptes fictifs en production est
 * une erreur de configuration, et un serveur qui démarre quand même la laisse
 * passer inaperçue.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeedRunner implements CommandLineRunner {

    private final ReferenceDataSeeder referenceDataSeeder;
    private final DemoDataSeeder demoDataSeeder;

    /**
     * La seule source de vérité sur les profils actifs.
     *
     * <p>Ce champ remplace une lecture de la propriété
     * {@code spring.profiles.active} en chaîne, découpée sur les virgules. Les
     * deux ne disent pas la même chose :
     * un profil activé autrement que par cette propriété — par
     * {@code SPRING_PROFILES_ACTIVE}, par un {@code spring.profiles.include},
     * par un {@code @ActiveProfiles} de test — est bien actif sans y apparaître.
     * La garde pouvait donc lire une liste vide sous un profil de production.
     */
    private final Environment environment;

    @Value("${pair.seed.reference-data.enabled:false}")
    private boolean referenceDataEnabled;

    @Value("${pair.seed.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Override
    public void run(String... args) throws Exception {
        String[] profilsActifs = environment.getActiveProfiles();

        log.info("=== Démarrage de SeedRunner ===");
        log.info("Profils actifs: {}",
                profilsActifs.length == 0 ? "aucun" : String.join(", ", profilsActifs));
        log.info("Configuration - referenceDataEnabled: {}, demoDataEnabled: {}",
                referenceDataEnabled, demoDataEnabled);

        // Exécution du seeder de données de référence
        if (referenceDataEnabled) {
            log.info("Lancement du ReferenceDataSeeder...");
            try {
                referenceDataSeeder.run(args);
                log.info("ReferenceDataSeeder terminé avec succès");
            } catch (Exception e) {
                log.error("Erreur lors de l'exécution du ReferenceDataSeeder", e);
                throw e;
            }
        } else {
            log.info("ReferenceDataSeeder désactivé (pair.seed.reference-data.enabled=false)");
        }

        // Exécution du seeder de données de démonstration avec garde-fou de sécurité
        if (demoDataEnabled) {
            log.info("Vérification du garde-fou de sécurité pour DemoDataSeeder...");

            // GARDE-FOU DE SÉCURITÉ : Interdiction stricte des données de démo en production
            String profilDeProduction = profilDeProductionActif();
            if (profilDeProduction != null) {
                String errorMessage = String.format(
                        "REFUS DE SÉCURITÉ : pair.seed.demo-data.enabled=true sous le profil de "
                        + "production « %s ». Les données de démonstration ne doivent jamais être "
                        + "créées là où il y a de vrais comptes : elles portent un mot de passe "
                        + "commun et se mêlent aux vraies données. Posez "
                        + "pair.seed.demo-data.enabled=false dans application-%s.properties. "
                        + "Profils de production : %s.",
                        profilDeProduction, profilDeProduction, Profils.PRODUCTION);
                log.error(errorMessage);
                throw new IllegalStateException(errorMessage);
            }

            log.info("Garde-fou de sécurité validé - aucun profil de production parmi {}",
                    Arrays.toString(profilsActifs));
            log.info("Lancement du DemoDataSeeder...");
            try {
                demoDataSeeder.run(args);
                log.info("DemoDataSeeder terminé avec succès");
            } catch (Exception e) {
                log.error("Erreur lors de l'exécution du DemoDataSeeder", e);
                throw e;
            }
        } else {
            log.info("DemoDataSeeder désactivé (pair.seed.demo-data.enabled=false)");
        }

        log.info("=== SeedRunner terminé ===");
    }

    /**
     * Le profil de production actif, s'il y en a un.
     *
     * <p>Rend le nom plutôt qu'un booléen pour que le refus le cite : le message
     * est lu une fois, dans l'urgence d'un déploiement qui ne démarre pas, et
     * « profil de production détecté » n'indique pas quel fichier ouvrir.
     *
     * <p>La règle ne visait que {@code prod}, alors que la production s'appelle
     * {@code railway} : c'est cette seule différence de nom qui a laissé créer
     * les comptes de démonstration en production (fiche P-BS-02).
     * {@link Profils#PRODUCTION} exclut volontairement {@code staging}, où le
     * seed de démonstration est voulu.
     */
    private String profilDeProductionActif() {
        return Profils.premierProfilActif(environment, Profils.PRODUCTION);
    }
}
