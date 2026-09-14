package org.program.pair.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-BA-06 — Flyway vérifie les sommes de contrôle au démarrage.
 *
 * <p><b>Le défaut.</b> {@code validate-on-migrate=false} : une migration modifiée
 * après son application passait sans erreur, et la base de production divergeait
 * en silence des fichiers du dépôt. Le relevé du 14/09/2026 en a trouvé quatre
 * (V27, V28, V47, V116). {@code repair-on-migrate=true}, à côté, n'était ni une
 * propriété de Spring Boot ni une option de Flyway : la ligne ne faisait rien.
 *
 * <p>Ce test tient la configuration effective du bean — pas le fichier de
 * propriétés, qu'une variable d'environnement peut contredire — et prouve que la
 * chaîne du dépôt valide contre l'historique qu'elle a elle-même produit.
 */
class FlywayValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Test
    void laValidation_doitEtreActiveAuDemarrage_sansLigneDeBaseImplicite() {
        assertThat(flyway.getConfiguration().isValidateOnMigrate()).isTrue();
        assertThat(flyway.getConfiguration().isBaselineOnMigrate()).isFalse();
        assertThat(flyway.getConfiguration().isCleanDisabled()).isTrue();
    }

    @Test
    void laChaineDuDepot_doitValiderContreLHistoriqueApplique() {
        ValidateResult resultat = flyway.validateWithResult();

        assertThat(resultat.validationSuccessful)
            .as("erreurs de validation : %s", resultat.invalidMigrations)
            .isTrue();
    }
}
