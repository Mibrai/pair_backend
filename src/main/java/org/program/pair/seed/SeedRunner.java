package org.program.pair.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Orchestrateur principal pour l'exécution des seeders au démarrage de l'application.
 * <p>
 * Ce composant s'exécute automatiquement via CommandLineRunner et contrôle
 * l'exécution des différents seeders selon la configuration et le profil Spring actif.
 * <p>
 * Garde-fous de sécurité :
 * - Les données de démonstration sont strictement interdites en profil 'prod'
 * - Une IllegalStateException est levée si cette règle est violée
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeedRunner implements CommandLineRunner {

    private final ReferenceDataSeeder referenceDataSeeder;
    private final DemoDataSeeder demoDataSeeder;

    @Value("${pair.seed.reference-data.enabled:false}")
    private boolean referenceDataEnabled;

    @Value("${pair.seed.demo-data.enabled:false}")
    private boolean demoDataEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Override
    public void run(String... args) throws Exception {
        log.info("=== Démarrage de SeedRunner ===");
        log.info("Profils actifs: {}", activeProfiles.isEmpty() ? "aucun" : activeProfiles);
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
            if (isProductionProfile()) {
                String errorMessage = "REFUS DE SÉCURITÉ : pair.seed.demo-data.enabled=true détecté en profil 'prod'. " +
                        "Les données de démonstration ne doivent jamais être créées en production.";
                log.error(errorMessage);
                throw new IllegalStateException(errorMessage);
            }

            log.info("Garde-fou de sécurité validé - pas de profil 'prod' détecté");
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
     * Vérifie si le profil 'prod' est actif.
     *
     * @return true si le profil 'prod' est présent dans les profils actifs
     */
    private boolean isProductionProfile() {
        if (activeProfiles == null || activeProfiles.trim().isEmpty()) {
            return false;
        }
        return Arrays.stream(activeProfiles.split(","))
                .map(String::trim)
                .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
    }
}
