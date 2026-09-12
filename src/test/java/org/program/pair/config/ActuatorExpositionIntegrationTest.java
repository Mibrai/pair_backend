package org.program.pair.config;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.program.pair.AbstractIntegrationTest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce qu'actuator laisse lire, et à qui.
 *
 * <p><b>Le défaut fermé ici.</b> {@code /actuator/metrics} ne figurait dans
 * aucune règle de {@link SecurityConfig} : il retombait sur
 * {@code anyRequest().authenticated()}, et « authentifié » n'est pas
 * « autorisé ». N'importe quel titulaire de compte — y compris un compte créé
 * à l'instant, sans vérification d'adresse — lisait les noms de routes, les
 * tailles de pool, les compteurs d'erreurs. Le détail de santé était ouvert de
 * la même façon : {@code show-details=when-authorized} ne signifie rien d'autre
 * que « tout compte connecté » en l'absence de
 * {@code management.endpoint.health.roles} (fiche d'audit P-BS-16).
 *
 * <p><b>Le compte de test n'est pas décoratif.</b> Le premier test s'inscrit
 * vraiment et présente un vrai jeton : c'est exactement le profil de
 * l'attaquant, et un test anonyme ne l'aurait pas attrapé — c'est même la
 * raison pour laquelle le défaut a vécu si longtemps, {@code curl} sans jeton
 * rendant 401 et laissant croire que la route était fermée.
 *
 * <p>Complément indispensable de {@link ObservabiliteConfigurationTest}, qui
 * éprouve la même règle du côté des fichiers de configuration : ici le
 * comportement, là-bas la ligne. Les deux sont nécessaires — la ligne peut
 * revenir sans que le comportement change, c'est tout l'objet du
 * {@code denyAll}, et le comportement peut se rouvrir sans que la ligne bouge.
 */
class ActuatorExpositionIntegrationTest extends AbstractIntegrationTest {

    /**
     * Le cas de la fiche : un compte connecté, un vrai jeton, et un refus.
     *
     * <p><b>Pourquoi trois codes et non « 403 ou 404 » comme l'écrit la
     * fiche.</b> Relevé le 12/09 : cette requête rend {@code 401}. Ce n'est pas
     * le jeton — le test suivant prouve qu'il ouvre une route ordinaire — c'est
     * que le code exact n'est pas décidé par la règle mais par
     * {@code ExceptionTranslationFilter}, qui choisit entre le point d'entrée
     * (401) et le gestionnaire de refus (403) selon l'état du contexte de
     * sécurité au moment où le refus remonte. Or {@code denyAll()} refuse
     * <b>sans résoudre l'identité de l'appelant</b> : la décision est prise sans
     * que le contexte soit consulté, et aucune écriture de la règle ne peut
     * forcer l'un des deux codes — {@code hasRole("…")} donnerait le même
     * partage, 403 pour un compte connecté et 401 pour un anonyme.
     *
     * <p>L'invariant de sécurité n'est donc pas le code, c'est <b>le refus</b> :
     * jamais 200, et aucun nom de métrique dans le corps. C'est ce que ce test
     * vérifie, et c'est la définition de « fini » de la fiche — « aucun compte
     * applicatif ne lit les métriques ». Le contrôle du code exact appartient au
     * module de session, dont le contrat pose que l'ensemble des 401 de cette
     * API est clos ; voir le rapport de livraison, qui signale ce 401-là comme
     * hors de cet ensemble et rappelle qu'aucun client n'appelle
     * {@code /actuator/**}, donc qu'il ne déclenche aucun rafraîchissement.
     */
    @Test
    void lesMetriques_doiventEtreRefusees_quandUnCompteConnecteLesDemande() {
        EntityExchangeResult<byte[]> reponse =
            resultat("/actuator/metrics", inscrire().accessToken());

        assertThat(reponse.getStatus().value())
            .as("un compte connecté ne lit pas les métriques")
            .isIn(401, 403, 404);
        assertThat(corpsTexte(reponse))
            .as("et le corps du refus ne laisse filtrer aucune mesure")
            .doesNotContain("jvm.", "hikaricp", "http.server.requests", "names");
    }

    /**
     * Le contrôle du test précédent : le même jeton ouvre bien une route
     * ordinaire. Sans lui, le refus ci-dessus pourrait n'être qu'un jeton
     * invalide, et ne prouverait rien.
     */
    @Test
    void leMemeJeton_doitOuvrirUneRouteOrdinaire() {
        webTestClient.get()
            .uri("/api/users/me")
            .headers(entetes -> entetes.setBearerAuth(inscrire().accessToken()))
            .exchange()
            .expectStatus().isOk();
    }

    /**
     * Le second verrou, celui qui survivra à l'erreur suivante : même si
     * quelqu'un rajoute une exposition dans
     * {@code management.endpoints.web.exposure.include}, la route reste fermée.
     * Aucun de ces points n'est exposé aujourd'hui — c'est justement le cas
     * qu'on couvre, puisque c'est ainsi que {@code metrics} est arrivé.
     */
    @Test
    void toutActuator_doitResterFerme_quandUneExpositionEstRajoutee() {
        String jeton = inscrire().accessToken();

        for (String point : List.of("/actuator/env", "/actuator/loggers",
                "/actuator/beans", "/actuator/heapdump", "/actuator/configprops")) {
            assertThat(statut(point, jeton))
                .as("%s ne doit jamais rendre 200", point)
                .isNotEqualTo(200);
        }
    }

    /**
     * Le point de collecte n'est pas joignable sur le port de l'application.
     *
     * <p>Sous le profil railway il vit sur le port de gestion privé
     * ({@code management.server.port}) ; ici, sous le profil test, il n'est même
     * pas exposé. Dans les deux cas, une requête sur le port public doit être
     * refusée — et c'est le {@code denyAll} qui en répond, pas l'absence
     * d'exposition, laquelle peut changer.
     */
    @Test
    void lePointPrometheus_neDoitPasEtreJoignableSurLePortPublic() {
        assertThat(statut("/actuator/prometheus", null)).isNotEqualTo(200);
        assertThat(statut("/actuator/prometheus", inscrire().accessToken())).isNotEqualTo(200);
    }

    /**
     * La santé ne dit que {@code status}. Elle nommait la base, Redis, le disque
     * et l'état des migrations : de quoi dresser la carte de l'infrastructure
     * depuis un compte gratuit.
     *
     * <p>Le code HTTP n'est pas contraint ici, délibérément : il suffit qu'un
     * indicateur soit en échec sur la machine qui exécute la suite — un disque
     * presque plein, par exemple — pour que l'agrégat passe de 200 à 503. Ce
     * qui est éprouvé est le <b>contenu</b>, et il doit être aussi maigre dans
     * un cas que dans l'autre : un 503 détaillé en dirait même plus long qu'un
     * 200.
     *
     * <p><b>{@code groups} est admis, et ce n'est pas une concession.</b> Le
     * corps porte {@code {"groups":["liveness","readiness"],"status":…}} depuis
     * que les sondes sont activées dans la configuration commune. Cette clé ne
     * nomme que les deux groupes de sonde, qui sont de toute façon des adresses
     * publiques et documentées ({@code /actuator/health/readiness} est ce que la
     * plateforme interroge) : elle ne dit rien de la base, de Redis, du disque
     * ni des migrations. Ce qui doit rester absent, et que ce test interdit
     * nommément, ce sont {@code components} et {@code details} — les deux seules
     * clés par lesquelles le détail d'infrastructure sort, et ce que
     * {@code show-details=when-authorized} publiait à tout compte connecté.
     */
    @Test
    void laSante_neDoitDetaillerNiLaBaseNiRedis() throws Exception {
        JsonNode corps = corpsJson("/actuator/health");

        assertThat(champs(corps))
            .as("le détail d'infrastructure sort par « components » et « details », "
                + "et par elles seules : %s", corps)
            .doesNotContain("components", "details")
            .contains("status");
        assertThat(corps.path("status").asText()).isNotEmpty();
    }

    /**
     * La sonde de disponibilité, celle que la plateforme interroge. Elle répond
     * une fois le contexte prêt — donc après Flyway, puisque les migrations
     * tournent avant que le connecteur HTTP n'accepte quoi que ce soit : le seul
     * fait que cet appel aboutisse en {@code UP} le démontre.
     *
     * <p>Elle ne détaille rien non plus : {@code show-details=never} vaut pour
     * les groupes comme pour l'agrégat.
     */
    @Test
    void laSondeDeDisponibilite_doitRepondreUp_quandLesMigrationsSontPassees() throws Exception {
        JsonNode corps = corpsJson("/actuator/health/readiness");

        assertThat(corps.path("status").asText()).isEqualTo("UP");
        assertThat(champs(corps))
            .as("un groupe ne rend que son statut : %s", corps)
            .doesNotContain("components", "details")
            .contains("status");
    }

    /**
     * Ce qui reste ouvert, et pourquoi on l'écrit dans un test plutôt que dans
     * un commentaire seul.
     *
     * <p>{@code /actuator/info} rend l'identité du build : version, heure de
     * construction, commit déployé. C'est ce qui permet de dire si la production
     * exécute le code qu'on est en train de lire, et il sert précisément quand
     * plus rien d'autre ne répond comme attendu — le faire dépendre d'une
     * session le rendrait inutile le jour où on en a besoin.
     *
     * <p>{@code /v3/api-docs} est le contrat que l'outil de l'équipe mobile
     * relève sur la production. Le fermer rendrait cette vérification
     * impossible depuis l'extérieur, donc facultative, donc oubliée.
     *
     * <p>Les deux sont ouverts <b>par choix</b>, et ce test est là pour qu'un
     * durcissement mécanique de {@code /actuator/**} ne les emporte pas sans
     * qu'on s'en aperçoive.
     */
    @Test
    void infoEtApiDocs_doiventResterLisiblesSansSession() {
        webTestClient.get().uri("/actuator/info").exchange().expectStatus().isOk();
        webTestClient.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
    }

    // ------------------------------------------------------------------ outils

    /** Le code HTTP rendu, sans rien présumer de sa valeur. */
    private int statut(String chemin, String jeton) {
        return resultat(chemin, jeton).getStatus().value();
    }

    /** Le corps tel quel, vide compris : un refus n'en rend pas toujours un. */
    private static String corpsTexte(EntityExchangeResult<byte[]> reponse) {
        byte[] corps = reponse.getResponseBody();
        return corps == null ? "" : new String(corps, java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode corpsJson(String chemin) throws Exception {
        byte[] corps = resultat(chemin, null).getResponseBody();

        assertThat(corps).as("%s doit rendre un corps", chemin).isNotNull();
        return objectMapper.readTree(corps);
    }

    private EntityExchangeResult<byte[]> resultat(String chemin, String jeton) {
        WebTestClient.RequestHeadersSpec<?> requete = webTestClient.get().uri(chemin);
        if (jeton != null) {
            // headers() modifie la requête en place et rend this : la valeur de
            // retour porte le type capturé du joker et ne se réaffecte pas.
            requete.headers(entetes -> entetes.setBearerAuth(jeton));
        }
        return requete.exchange().expectBody().returnResult();
    }

    private static List<String> champs(JsonNode noeud) {
        List<String> noms = new ArrayList<>();
        for (Iterator<String> it = noeud.fieldNames(); it.hasNext(); ) {
            noms.add(it.next());
        }
        return noms;
    }

    private AuthResponse inscrire() {
        AuthResponse session = webTestClient.post()
            .uri("/api/auth/register")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(new RegisterRequest(
                uniqueEmail("actuator-exposition"), "MotDePasse123!", "Compte de test"))
            .exchange()
            .expectStatus().isCreated()
            .expectBody(AuthResponse.class)
            .returnResult().getResponseBody();

        assertThat(session).isNotNull();
        return session;
    }
}
