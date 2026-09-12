package org.program.pair.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.notification.NotificationService;
import org.program.pair.domain.notification.NotificationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'exécuteur des {@code @Async} est borné, et il l'est <b>par rapport au pool
 * de connexions</b>.
 *
 * <p>Ce que ces deux tests protègent n'est pas une valeur de configuration mais
 * un rapport entre deux nombres. {@code NotificationService} est
 * {@code @Transactional} de classe et porte quatre des dix méthodes
 * {@code @Async} sans qualificatif de l'application : chaque notification en
 * vol tient une connexion. Tant qu'aucun bean ne s'appelait
 * {@code taskExecutor}, {@code @Async} retombait sur un
 * {@code SimpleAsyncTaskExecutor}, qui crée <b>un fil par tâche, sans
 * limite</b> — donc autant de connexions demandées que de notifications, pour
 * un pool de 20 et une attente de 10 s. Le symptôme ne se voyait pas du côté des
 * notifications : il se voyait sur une requête HTTP ordinaire, qui attendait sa
 * connexion derrière la rafale.
 *
 * <p>D'où la forme du second test : il ne mesure pas la vitesse des
 * notifications, dont personne ne se soucie, mais le fait qu'<b>une requête
 * d'utilisateur passe pendant</b> qu'une rafale est en vol.
 *
 * <p>Ni {@code @TestPropertySource} ni {@code @MockitoBean} ici : la classe doit
 * partager le contexte Spring mis en cache par {@link AbstractIntegrationTest},
 * dont la javadoc explique ce que coûte un contexte de plus.
 */
class AsyncConfigIntegrationTest extends AbstractIntegrationTest {

    /**
     * Ce que la fiche appelle « une rafale » : l'ordre de grandeur d'un passage
     * de rappels d'agenda un soir chargé, largement au-delà du pool de 20.
     */
    private static final int RAFALE = 200;

    /** Le plafond du pool, borné par {@code maxPoolSize} de l'exécuteur. */
    private static final int FILS_MAX = 8;

    /**
     * Le plafond de {@code spring.datasource.hikari.maximum-pool-size}
     * ({@code application.properties} l. 61), recopié ici parce que c'est la
     * borne que l'exécuteur ne doit pas faire franchir.
     */
    private static final int POOL_MAX = 20;

    @Autowired private ApplicationContext context;
    @Autowired private NotificationService notificationService;
    @Autowired private DataSource dataSource;

    /** Un compte de test et son jeton, créés par la méthode qui s'en sert. */
    private record Compte(UUID id, String jeton) {}

    @Test
    void executeurAsynchrone_doitEtreBorneAHuitFilsEtSAppelerTaskExecutor_quandLeContexteEstDemarre() {
        // Le nom fait partie de l'exigence : c'est celui que Spring cherche avant
        // de se rabattre sur un SimpleAsyncTaskExecutor illimité. Une recherche
        // par type seule passerait à côté du défaut.
        assertThat(context.containsBean("taskExecutor")).isTrue();

        Object bean = context.getBean("taskExecutor");
        assertThat(bean).isInstanceOf(ThreadPoolTaskExecutor.class);

        ThreadPoolTaskExecutor executeur = (ThreadPoolTaskExecutor) bean;
        // Huit au plus, pour vingt connexions : douze restent aux requêtes HTTP.
        assertThat(executeur.getMaxPoolSize()).isEqualTo(FILS_MAX);
        assertThat(executeur.getMaxPoolSize()).isLessThan(POOL_MAX);

        // Et c'est bien ce bean-là que la configuration désigne. Deux pools de
        // huit fils — l'un nommé, l'autre rendu par getAsyncExecutor() — ne se
        // verraient nulle part et doubleraient la consommation de connexions.
        Executor designe = context.getBean(AsyncConfig.class).getAsyncExecutor();
        assertThat(designe).isSameAs(executeur);
    }

    @Test
    void rafaleDeNotifications_doitLaisserPasserUneRequeteHttp_quandDeuxCentsNotificationsSontEnVol() {
        Compte destinataire = inscrire();

        // Requête à blanc, hors chronomètre : la première requête d'une méthode
        // paie l'initialisation paresseuse de la chaîne de filtres. La mesurer
        // ferait dire « lent » à un test là où le pool n'est pas en cause.
        compteurNonLues(destinataire);

        for (int i = 0; i < RAFALE; i++) {
            notificationService.notify(
                destinataire.id(),
                NotificationType.SYSTEM,
                Map.of("source", "AsyncConfigIntegrationTest", "index", i));
        }

        // Le cœur du correctif : 200 tâches soumises n'ont pas créé 200 fils.
        // Un SimpleAsyncTaskExecutor ne pourrait pas satisfaire cette assertion —
        // il n'a pas de pool du tout.
        ThreadPoolTaskExecutor executeur = (ThreadPoolTaskExecutor) context.getBean("taskExecutor");
        assertThat(executeur.getThreadPoolExecutor().getPoolSize()).isLessThanOrEqualTo(FILS_MAX);

        long debut = System.nanoTime();
        compteurNonLues(destinataire);
        Duration duree = Duration.ofNanos(System.nanoTime() - debut);

        assertThat(duree).isLessThan(Duration.ofSeconds(2));

        // La borne du pool n'a pas été franchie. Hikari ne peut de toute façon pas
        // ouvrir davantage ; ce que l'assertion attrape, c'est le jour où
        // quelqu'un relèvera maximum-pool-size sans relever le pool de fils — ou
        // l'inverse, qui est le défaut d'origine.
        HikariPoolMXBean pool = poolHikari();
        assertThat(pool.getActiveConnections()).isLessThanOrEqualTo(POOL_MAX);

        // La rafale se vide avant de rendre la main : des tâches encore en file
        // tiendraient des connexions pendant la classe de test suivante, qui
        // partage le même contexte et la même base. On vérifie du même coup
        // qu'aucune tâche n'a été perdue — CallerRunsPolicy ralentit, il ne jette
        // rien. Le compte peut dépasser la rafale si l'inscription a elle-même
        // laissé une notification, d'où la comparaison large.
        long nonLues = attendreLeCompteur(destinataire, RAFALE, Duration.ofSeconds(60));
        assertThat(nonLues).isGreaterThanOrEqualTo(RAFALE);
    }

    /**
     * Son propre compte, créé dans le test : toute la suite partage une base, et
     * une adresse codée en dur ferait échouer la deuxième exécution sur un 409
     * (voir {@link AbstractIntegrationTest#uniqueEmail}).
     */
    private Compte inscrire() {
        AuthResponse reponse = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(uniqueEmail("rafale-async"), "Password123!", "Rafale"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult()
            .getResponseBody();

        assertThat(reponse).isNotNull();
        assertThat(reponse.userId()).isNotNull();
        return new Compte(reponse.userId(), reponse.accessToken());
    }

    /**
     * {@code GET /api/notifications/unread-count}, dont on exige un 200.
     *
     * @return le compte de notifications non lues
     */
    private long compteurNonLues(Compte compte) {
        byte[] brut = webTestClient.get()
            .uri("/api/notifications/unread-count")
            .headers(h -> h.setBearerAuth(compte.jeton()))
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .returnResult()
            .getResponseBody();

        assertThat(brut).isNotNull();
        try {
            JsonNode corps = objectMapper.readTree(new String(brut, StandardCharsets.UTF_8));
            assertThat(corps.has("unreadCount")).isTrue();
            return corps.get("unreadCount").asLong();
        } catch (Exception e) {
            throw new IllegalStateException("Réponse illisible de /api/notifications/unread-count", e);
        }
    }

    /**
     * Attente active bornée. Awaitility n'est pas une dépendance déclarée de ce
     * dépôt — aucun test ne l'importe et le {@code pom.xml} ne le mentionne pas :
     * une boucle explicite plutôt qu'un import qui ne compilerait pas.
     */
    private long attendreLeCompteur(Compte compte, long cible, Duration plafond) {
        long echeance = System.nanoTime() + plafond.toNanos();
        long vu = 0;
        while (System.nanoTime() < echeance) {
            vu = compteurNonLues(compte);
            if (vu >= cible) {
                return vu;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return vu;
    }

    private HikariPoolMXBean poolHikari() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariPoolMXBean pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        assertThat(pool).isNotNull();
        return pool;
    }
}
