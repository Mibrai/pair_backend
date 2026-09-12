package org.program.pair.integration;

import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLog;
import org.program.pair.domain.audit.AuditLogRepository;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RefreshRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.UserService;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.dto.ErrorResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * La suppression de compte demandée depuis l'application, de bout en bout.
 *
 * <p><b>Ce que ces tests couvrent et que rien ne couvrait.</b>
 * {@code DELETE /api/gdpr/delete-account} est la <i>seule</i> route de
 * suppression que l'app appelle, et son corps ne contenait que des commentaires
 * suivis d'un {@code 204} : chaque demande était confirmée à l'écran et perdue.
 * La désactivation n'existait que sur {@code DELETE /users/me}, qu'aucun écran
 * n'atteint — et {@code GdprServiceIntegrationTest}, qui passe par cette
 * seconde route, voyait donc tout fonctionner. C'est le genre de défaut qu'un
 * test ne trouve que s'il appelle la route que l'app appelle.
 *
 * <p><b>Chaque méthode crée son propre compte jetable et ne désactive que
 * celui-là.</b> La suite entière partage une base : désactiver un compte de
 * démonstration, ou deux méthodes partageant un compte, casserait des classes
 * sans rapport, et l'échec se déplacerait avec l'ordre d'exécution. Aucun
 * identifiant n'est écrit en dur ici, et aucune écriture ne sort du compte créé
 * par la méthode courante.
 */
class GdprDeletionIntegrationTest extends AbstractIntegrationTest {

    private static final String MOT_DE_PASSE = "MotDePasse123!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserService userService;

    /**
     * Le constat lui-même : la route de l'app désactive enfin le compte.
     *
     * <p>Le {@code 204} ne prouve rien — il était déjà rendu quand la route ne
     * faisait rien. C'est la lecture en base qui est l'assertion.
     */
    @Test
    void supprimerSonCompte_doitLeDesactiver_parLaRouteRgpd() {
        Compte compte = inscrire("rgpd-suppression");

        supprimerParLaRouteRgpd(compte);

        User enBase = userRepository.findById(compte.id()).orElseThrow();
        assertThat(enBase.getIsActive()).isFalse();
        // La désactivation retire aussi le point de la carte : un compte parti ne
        // doit pas continuer d'y figurer.
        assertThat(enBase.getLocationPublic()).isFalse();
    }

    /** Le compte supprimé ne se reconnecte pas — le refus indifférencié du login. */
    @Test
    void supprimerSonCompte_doitRefuserLaConnexionSuivante() {
        Compte compte = inscrire("rgpd-login-apres");

        supprimerParLaRouteRgpd(compte);

        ErrorResponse erreur = webTestClient.post()
            .uri("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new LoginRequest(compte.email(), MOT_DE_PASSE))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("INVALID_CREDENTIALS");
    }

    /**
     * Et il ne prolonge pas la session qu'il avait.
     *
     * <p>La garde vit dans {@code AuthService.refreshToken} depuis le lot des
     * sessions et n'a pas eu à être ajoutée ici ; ce test la tient <b>depuis la
     * suppression réelle</b>, là où {@code RefreshTokenIntegrationTest} la tient
     * depuis une désactivation écrite à la main. Sans quoi la garde pourrait
     * rester verte tandis que la route de suppression cesserait de désactiver.
     */
    @Test
    void supprimerSonCompte_doitRefuserLeRafraichissement() {
        Compte compte = inscrire("rgpd-refresh-apres");

        supprimerParLaRouteRgpd(compte);

        ErrorResponse erreur = webTestClient.post()
            .uri("/api/auth/refresh")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RefreshRequest(compte.session().refreshToken()))
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody(ErrorResponse.class)
            .returnResult().getResponseBody();

        assertThat(erreur).isNotNull();
        assertThat(erreur.code()).isEqualTo("INVALID_TOKEN");
    }

    /**
     * Rejouer la demande ne la fait pas échouer.
     *
     * <p>C'est le cas de la coupure réseau : l'app envoie la demande, perd la
     * réponse, recommence. Le second appel tombait sur le {@code 404} de
     * {@code findActiveUser} — l'app affichait un échec pour une suppression qui
     * avait réussi. Le service rend maintenant le même succès silencieux, et
     * c'est ce que la seconde moitié de ce test éprouve.
     *
     * <p><b>La reprise est vérifiée sur le service, pas sur la route, et la
     * raison compte.</b> Une fois le compte inactif, son jeton d'accès
     * n'authentifie plus : le filtre de session charge le compte et le rejette
     * sur {@code isActive}. Un second appel HTTP avec le même jeton n'atteint
     * donc jamais le contrôleur, quoi que fasse le service — la reprise que l'app
     * peut réellement tenter est un nouvel appel après une reconnexion, que le
     * login refuse aussi. Le {@code 204} du titre est donc celui du premier
     * appel, tenu ci-dessous ; ce que le second appel démontre, c'est que le
     * refus qui le rendait impossible a disparu. Rendre la route entière
     * rejouable demande de laisser un compte inactif s'authentifier le temps de
     * sa propre suppression : cela touche le filtre, et n'est pas de ce lot.
     */
    @Test
    void supprimerSonCompteDeuxFois_doitRendre204() {
        Compte compte = inscrire("rgpd-idempotence");

        supprimerParLaRouteRgpd(compte);
        assertThat(userRepository.findById(compte.id()).orElseThrow().getIsActive()).isFalse();

        assertThatCode(() -> userService.deactivateAccount(compte.id()))
            .doesNotThrowAnyException();

        assertThat(userRepository.findById(compte.id()).orElseThrow().getIsActive()).isFalse();
    }

    /**
     * La demande est datée quelque part.
     *
     * <p>En l'absence de {@code users.deactivated_at}, cette ligne est la seule
     * chose qui dit qu'une demande a été reçue et quand — c'est-à-dire ce qui
     * fait courir les trente jours, et ce qui permettrait de répondre à
     * quelqu'un qui demande où en est sa suppression. La route n'écrivait rien :
     * les demandes déjà reçues sont introuvables en base.
     */
    @Test
    void supprimerSonCompte_doitEcrireUneTraceDaudit() {
        Compte compte = inscrire("rgpd-audit");

        supprimerParLaRouteRgpd(compte);

        List<AuditLog> traces = attendreLesTraces(compte.id());
        assertThat(traces).hasSize(1);
        AuditLog trace = traces.get(0);
        assertThat(trace.getUserId()).isEqualTo(compte.id());
        assertThat(trace.getEntityType()).isEqualTo("USER");
        assertThat(trace.getEntityId()).isEqualTo(compte.id());
        assertThat(trace.getCreatedAt()).isNotNull();
    }

    /**
     * Les deux routes sont indiscernables — état en base <b>et</b> trace.
     *
     * <p>Tant qu'elles divergent, la conformité dépend de la porte empruntée :
     * {@code /users/me} devient un alias documenté de la route RGPD, et un alias
     * qui ne daterait pas la demande laisserait un trou dans le registre.
     */
    @Test
    void lesDeuxRoutesDeSuppression_doiventProduireLeMemeEtat() {
        Compte parRgpd = inscrire("rgpd-deux-routes-a");
        Compte parUsersMe = inscrire("rgpd-deux-routes-b");

        supprimerParLaRouteRgpd(parRgpd);

        webTestClient.delete()
            .uri("/api/users/me")
            .headers(headers -> headers.setBearerAuth(parUsersMe.session().accessToken()))
            .exchange()
            .expectStatus().isNoContent();

        User apresRgpd = userRepository.findById(parRgpd.id()).orElseThrow();
        User apresUsersMe = userRepository.findById(parUsersMe.id()).orElseThrow();

        assertThat(apresRgpd.getIsActive()).isFalse();
        assertThat(apresUsersMe.getIsActive()).isFalse();
        assertThat(apresUsersMe.getLocationPublic()).isEqualTo(apresRgpd.getLocationPublic());

        assertThat(attendreLesTraces(parRgpd.id())).hasSize(1);
        assertThat(attendreLesTraces(parUsersMe.id())).hasSize(1);
    }

    // ------------------------------------------------------------------
    // Fabrique et outils
    // ------------------------------------------------------------------

    /** Un compte jetable, connu de la seule méthode qui l'a créé. */
    private record Compte(String email, UUID id, AuthResponse session) {}

    private Compte inscrire(String prefixe) {
        String email = uniqueEmail(prefixe);
        AuthResponse session = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(email, MOT_DE_PASSE, "Compte jetable RGPD"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(session).isNotNull();
        assertThat(session.userId()).isNotNull();
        return new Compte(email, session.userId(), session);
    }

    /** La route que l'application appelle, et elle seule. */
    private void supprimerParLaRouteRgpd(Compte compte) {
        webTestClient.delete()
            .uri("/api/gdpr/delete-account")
            .headers(headers -> headers.setBearerAuth(compte.session().accessToken()))
            .exchange()
            .expectStatus().isNoContent();
    }

    /**
     * Les lignes {@code GDPR_DELETE_REQUEST} de ce compte, <b>attendues</b>.
     *
     * <p>{@code AuditLogService.log} est {@code @Async} et {@code @EnableAsync}
     * est actif en test : l'écriture a lieu sur un autre fil que celui qui a
     * servi la requête. Lire le dépôt juste après le {@code 204} passerait
     * presque toujours et tomberait sur une machine chargée — un test qui
     * apprend à relancer la suite au lieu de la lire. On attend donc, avec une
     * borne, et le filtrage se fait sur l'identifiant du compte créé par la
     * méthode courante : les lignes des autres classes de la suite partagent
     * cette table.
     */
    private List<AuditLog> attendreLesTraces(UUID userId) {
        long limite = System.currentTimeMillis() + 5_000;
        List<AuditLog> vues = tracesDe(userId);
        while (vues.isEmpty() && System.currentTimeMillis() < limite) {
            dormir(50);
            vues = tracesDe(userId);
        }
        return vues;
    }

    private List<AuditLog> tracesDe(UUID userId) {
        return auditLogRepository
            .findByEntityTypeAndEntityIdOrderByCreatedAtDesc("USER", userId)
            .stream()
            .filter(ligne -> ligne.getActionType() == AuditActionType.GDPR_DELETE_REQUEST)
            .toList();
    }

    private static void dormir(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Attente interrompue", e);
        }
    }
}
