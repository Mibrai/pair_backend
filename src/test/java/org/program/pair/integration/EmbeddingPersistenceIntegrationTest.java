package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.CategoryRepository;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Le vecteur d'une activité ou d'un programme doit pouvoir être écrit
 * <b>depuis un appelant qui n'ouvre aucune transaction</b>.
 *
 * <p>C'est la situation exacte des deux seeders. {@code ReferenceDataSeeder} est
 * un {@code CommandLineRunner} : il appelle {@code updateEmbedding} hors de tout
 * contexte transactionnel, et Hibernate refusait l'écriture avec « No active
 * transaction for update or delete query ». Relevé en production le 07/09 :
 * « Embeddings générés: 0, échecs: 125 » à chaque démarrage, donc 125 activités
 * absentes de la recherche sémantique. {@code DemoDataSeeder} avait la même
 * panne côté programmes, en plus discret — son {@code catch} la réduisait à un
 * avertissement.
 *
 * <p><b>Cette classe ne doit jamais devenir {@code @Transactional}</b>, et
 * l'appel ne doit jamais être enveloppé dans un {@code TransactionTemplate} :
 * c'est l'absence de transaction autour de l'appel qui est le sujet du test. Une
 * transaction fournie par le test rendrait les deux cas verts, y compris le code
 * défectueux — le défaut se cache précisément derrière une préparation trop
 * serviable.
 *
 * <p>La suite ne pouvait pas l'attraper jusqu'ici : {@code application-test.properties}
 * désactive le modèle d'embeddings, donc {@code generateMissingEmbeddings()}
 * sortait avant d'écrire. On attaque donc le dépôt directement, ce qui teste la
 * seule chose qui manquait — la transaction — sans dépendre du modèle ONNX.
 */
class EmbeddingPersistenceIntegrationTest extends AbstractIntegrationTest {

    private static final int DIMENSION = 384;

    @Autowired private ActivityRepository activityRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ProgramRepository programRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserActivityRepository userActivityRepository;

    /** Un vecteur de la bonne dimension, non nul, au format attendu par pgvector. */
    private static String vecteur(float valeur) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < DIMENSION; i++) {
            sb.append(valeur);
            if (i < DIMENSION - 1) sb.append(',');
        }
        return sb.append(']').toString();
    }

    private Activity activiteSansVecteur() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Category categorie = categoryRepository.save(Category.builder()
            .name("Catégorie vecteur " + unique)
            .icon("science")
            .colorRamp("sky-blue")
            .build());

        return activityRepository.save(Activity.builder()
            .name("Activité vecteur " + unique)
            .slug("activite-vecteur-" + unique)
            .description("Créée sans vecteur, pour vérifier qu'on sait lui en poser un.")
            .category(categorie)
            .build());
    }

    @Test
    void poseLeVecteurDUneActiviteSansTransactionAppelante() {
        Activity activite = activiteSansVecteur();
        assertThat(activityRepository.findByEmbeddingIsNull())
            .extracting(Activity::getId)
            .contains(activite.getId());

        assertThatCode(() -> activityRepository.updateEmbedding(activite.getId(), vecteur(0.25f)))
            .doesNotThrowAnyException();

        assertThat(activityRepository.findByEmbeddingIsNull())
            .extracting(Activity::getId)
            .doesNotContain(activite.getId());
    }

    @Test
    void poseLeVecteurDUnProgrammeSansTransactionAppelante() {
        Activity activite = activiteSansVecteur();

        User organisateur = userRepository.save(User.builder()
            .email("vecteur-" + UUID.randomUUID() + "@pair.app")
            .passwordHash("$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ012")
            .displayName("Organisateur vecteur")
            .isActive(true)
            .build());

        UserActivity pratique = userActivityRepository.save(UserActivity.builder()
            .user(organisateur)
            .activity(activite)
            .build());

        Program programme = programRepository.save(Program.builder()
            .userActivity(pratique)
            .title("Programme sans vecteur")
            .description("Créé sans vecteur, pour vérifier qu'on sait lui en poser un.")
            .status(ProgramStatus.ACTIVE)
            .build());

        assertThat(programRepository.findByEmbeddingIsNull())
            .extracting(Program::getId)
            .contains(programme.getId());

        assertThatCode(() -> programRepository.updateEmbedding(programme.getId(), vecteur(0.5f)))
            .doesNotThrowAnyException();

        assertThat(programRepository.findByEmbeddingIsNull())
            .extracting(Program::getId)
            .doesNotContain(programme.getId());
    }
}
