package org.program.pair.shared.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.program.pair.shared.i18n.Messages;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.UnsupportedEncodingException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Le gestionnaire global tel qu'il répond réellement, et non tel qu'on l'appelle
 * (P-BA-10).
 *
 * <p><b>Pourquoi passer par MockMvc plutôt qu'appeler les méthodes.</b> Ce qui
 * est éprouvé ici n'est pas le corps d'une méthode mais la <b>résolution</b> :
 * quel gestionnaire Spring choisit pour une exception donnée. C'est exactement
 * ce qui a changé — {@code handleIllegalState} a été retiré, donc une
 * {@code IllegalStateException} doit maintenant tomber sur
 * {@code @ExceptionHandler(Exception.class)}. Un appel direct à
 * {@code handleGeneric} passerait même si quelqu'un remettait un gestionnaire
 * {@code IllegalStateException} demain ; ce montage-ci ne passerait pas.
 *
 * <p><b>Aucun contexte Spring.</b> {@code standaloneSetup} monte un
 * {@code DispatcherServlet} nu autour d'un contrôleur d'éprouvette et de l'avis
 * sous test : le cache de contextes de la suite (plafonné à quatre) n'est pas
 * sollicité, et {@link Messages} est une doublure, ce qui rend chaque assertion
 * sur le message indépendante des bundles — leur contenu est vérifié par
 * {@link ErrorCodeTraductionTest}.
 *
 * <p>Le fil rouge des quatre premiers tests : <b>ce qui ne doit pas sortir</b>.
 * Un message d'exception de framework, le nom d'une contrainte de base, une
 * valeur reçue. Chacun est une ligne que quelqu'un peut réintroduire sans
 * intention mauvaise, en cherchant à « rendre l'erreur plus utile ».
 */
class GlobalExceptionHandlerTest {

    private Messages messages;
    private MockMvc mockMvc;

    /**
     * Doublure créée à la main, sans {@code MockitoExtension} : les stubs sont
     * posés test par test, et la vérification de stub inutilisée du mode strict
     * ferait échouer ceux qui s'appuient volontairement sur le comportement par
     * défaut — {@code getOrNull} rendant {@code null}, donc le repli littéral.
     */
    @BeforeEach
    void setUp() {
        messages = Mockito.mock(Messages.class);
        mockMvc = MockMvcBuilders
            .standaloneSetup(new ControleurEprouvette())
            .setControllerAdvice(new GlobalExceptionHandler(messages))
            .build();
    }

    /**
     * Le cœur de la fiche : ce cas rendait {@code 409 CONFLICT} en affichant le
     * message de l'exception. Une panne se déguisait en conflit métier, et le
     * client lisait notre texte interne.
     */
    @Test
    void uneIllegalStateExceptionImprevue_doitRendre500SansSonMessage() throws Exception {
        MvcResult resultat = mockMvc.perform(get("/eprouvette/panne"))
            .andReturn();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(500);
        assertThat(corps(resultat)).contains("INTERNAL_ERROR");
        assertThat(corps(resultat))
            .as("le message de l'exception ne doit pas partir au client")
            .doesNotContain("EntityManager")
            .doesNotContain("jdbc:postgresql");
    }

    /**
     * Le texte du {@code 500} vient du bundle, et non plus d'un littéral
     * français gravé dans le gestionnaire : un client allemand recevait « Une
     * erreur est survenue. » quelle que soit sa langue.
     */
    @Test
    void leMessageDu500_doitVenirDeLaCleTraduite() throws Exception {
        doReturn("Es ist ein Fehler aufgetreten.").when(messages).getOrNull("error.INTERNAL_ERROR");

        MvcResult resultat = mockMvc.perform(get("/eprouvette/panne")).andReturn();

        assertThat(corps(resultat)).contains("Es ist ein Fehler aufgetreten.");
    }

    /**
     * Une contrainte de base violée : {@code 409}, et pas un mot du schéma. Le
     * nom de la contrainte dit la table et la colonne — de quoi cartographier la
     * base depuis l'extérieur.
     */
    @Test
    void uneContrainteDeBaseViolee_doitRendre409SansLeNomDeLaContrainte() throws Exception {
        doReturn("Cette action n'est pas possible dans l'état actuel.")
            .when(messages).getOrNull("error.CONFLICT.generic");

        MvcResult resultat = mockMvc.perform(get("/eprouvette/doublon")).andReturn();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(409);
        assertThat(corps(resultat))
            .contains("CONFLICT")
            .contains("Cette action n'est pas possible dans l'état actuel.")
            .doesNotContain("device_tokens_token_key")
            .doesNotContain("duplicate key");
    }

    /**
     * Un paramètre mal typé : le nom oui, la valeur non. La valeur vient de la
     * requête, donc éventuellement d'un lien fabriqué par un tiers ; le nom
     * vient de notre signature de méthode.
     */
    @Test
    void unParametreMalType_doitNommerLeParametreSansRendreLaValeurRecue() throws Exception {
        doReturn("Paramètre 'id' invalide.")
            .when(messages).getOrNull("error.INVALID_PARAMETER.named", "id");

        MvcResult resultat = mockMvc.perform(get("/eprouvette/utilisateurs/abc")).andReturn();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(400);
        assertThat(corps(resultat))
            .contains("INVALID_PARAMETER")
            .contains("Paramètre 'id' invalide.")
            .as("la valeur reçue reste au journal, jamais dans la réponse")
            .doesNotContain("abc");
    }

    /**
     * L'autre moitié de la fiche, et celle qu'un retrait trop large aurait
     * emportée : les refus qui <b>doivent</b> rester des {@code 409} en restent,
     * désormais avec un code nommé et un message traduit. C'est le chemin des
     * trois doublons d'{@code ActivityService}.
     */
    @Test
    void unRefusNomme_doitGarderSon409EtSonMessageTraduit() throws Exception {
        doReturn("Diese Aktivität gibt es in dieser Kategorie schon.")
            .when(messages).getOrNull("error.ACTIVITY_ALREADY_EXISTS");

        MvcResult resultat = mockMvc.perform(get("/eprouvette/doublonMetier")).andReturn();

        assertThat(resultat.getResponse().getStatus()).isEqualTo(409);
        assertThat(corps(resultat))
            .contains("ACTIVITY_ALREADY_EXISTS")
            .contains("Diese Aktivität gibt es in dieser Kategorie schon.")
            .as("le message technique de l'exception ne sert que de repli sans traduction")
            .doesNotContain("levé par le service");
    }

    private static String corps(MvcResult resultat) throws UnsupportedEncodingException {
        return resultat.getResponse().getContentAsString();
    }

    /**
     * Quatre routes qui ne font rien d'autre que lever. Elles n'existent que
     * dans ce montage : {@code standaloneSetup} ne lit aucune configuration de
     * sécurité ni aucun scan de paquets, donc ce contrôleur n'est jamais exposé.
     */
    @RestController
    static class ControleurEprouvette {

        @GetMapping("/eprouvette/panne")
        String panne() {
            // Le genre de message qu'Hibernate et Spring produisent, et qui
            // partait en 409 au client : un état interne, avec des noms de
            // classes et parfois une URL de connexion.
            throw new IllegalStateException(
                "EntityManager is closed for jdbc:postgresql://interne:5432/pair");
        }

        @GetMapping("/eprouvette/doublon")
        String doublon() {
            throw new DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique "
                    + "constraint \"device_tokens_token_key\"]");
        }

        @GetMapping("/eprouvette/doublonMetier")
        String doublonMetier() {
            throw new ConflictException(ErrorCode.ACTIVITY_ALREADY_EXISTS,
                "Cette activité existe déjà dans cette catégorie. (levé par le service)");
        }

        /**
         * Le nom du paramètre est écrit en clair dans {@code @PathVariable} : il
         * ne dépend donc pas de la présence de {@code -parameters} à la
         * compilation, et {@code ex.getName()} vaut « id » à coup sûr.
         */
        @GetMapping(value = "/eprouvette/utilisateurs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
        String utilisateur(@PathVariable("id") UUID id) {
            return id.toString();
        }
    }
}
