package org.program.pair.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.port:8090}")
    private String serverPort;

    @Bean
    public OpenAPI pairOpenAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("Pair API")
                .description("""
                    # API Pair - Réseau Social pour Activités Sportives et Culturelles

                    ## Vue d'ensemble
                    Pair est une plateforme permettant de trouver des partenaires pour des activités
                    sportives, culturelles et de loisirs basée sur la géolocalisation et les centres d'intérêt.

                    ## Fonctionnalités
                    - **Authentification**: JWT avec refresh tokens
                    - **Profils**: Gestion profils utilisateurs avec géolocalisation
                    - **Activités**: Catégories et activités prédéfinies
                    - **Programmes**: Création et gestion de programmes d'activités
                    - **Carte Interactive**: Recherche géographique avec filtres
                    - **Chat**: Messagerie temps réel (REST + WebSocket)
                    - **Recherche Intelligente**: NLP avec LLM pour extraction d'intent
                    - **Progressions**: Suivi d'avancement avec métriques et streaks
                    - **Médias**: Upload et gestion d'images

                    ## Authentification
                    La plupart des endpoints nécessitent un JWT Bearer token.

                    1. Créer un compte: `POST /api/auth/register`
                    2. Se connecter: `POST /api/auth/login`
                    3. Utiliser le token: `Authorization: Bearer <accessToken>`
                    4. Le renouveler: `POST /api/auth/refresh`, avec le `refreshToken`

                    ### Durée des sessions
                    `AuthResponse` porte `expiresIn` et `refreshExpiresIn`, en **secondes
                    relatives** — jamais des dates absolues. L'horloge d'un téléphone peut être
                    fausse de plusieurs heures, et un client qui comparerait une échéance
                    absolue à l'heure de son appareil fermerait des sessions valides. Ces deux
                    champs sont la seule source à laquelle se fier : les durées ont changé par
                    le passé sans que rien ne le dise.

                    **La rotation est glissante.** Le `refreshToken` rendu par
                    `POST /api/auth/refresh` vaut `refreshExpiresIn` **à compter de son
                    émission** ; il n'hérite pas de l'échéance de son prédécesseur. Une session
                    rafraîchie au moins une fois par fenêtre ne se termine donc jamais, et aucun
                    plafond d'ancienneté ne vient la fermer par-dessus.

                    Les deux jetons ne sont pas interchangeables : un `refreshToken` présenté en
                    `Authorization: Bearer` est refusé, et un `accessToken` présenté à
                    `/api/auth/refresh` l'est aussi.

                    ### Ce qui ferme une session, et ce qui ne la ferme pas
                    Seule une réponse **authentifiée** de refus la ferme : `401` ou `403` sur
                    `/api/auth/refresh`. Une absence de réponse, un `429`, un `5xx` ou un corps
                    illisible ne disent rien de la validité du jeton — les traiter comme un refus
                    détruit une session de trente jours sur un aller-retour raté, au réveil d'un
                    téléphone dont la radio n'a pas encore réassocié le réseau.

                    Sur les routes protégées, le corps du `401` porte un `code` stable qui dit
                    quoi faire : `TOKEN_EXPIRED` appelle un rafraîchissement silencieux,
                    `UNAUTHORIZED` signale un jeton absent ou illisible. Un refus de **droit**
                    n'est pas un `401` mais un `403` : il ne vaut jamais un rafraîchissement.

                    ## Rate Limiting
                    Ces trois lignes annonçaient « Recherche 20/min, Upload 10/min,
                    Auth 5-10/min » jusqu'au 10/09. Aucune des trois ne décrivait le code :
                    la recherche et l'upload ne sont pas plafonnés du tout, et le chiffre
                    « auth » ne correspondait à rien. Signalé par le chantier mobile, qui en
                    avait déduit — raisonnablement, c'est ce qu'un contrat est censé être —
                    que `POST /api/auth/refresh` pouvait rendre un 429 et couper les sessions.

                    **Quatre routes sont plafonnées, et elles seules :** `POST /api/auth/login`,
                    `/api/auth/register`, `/api/auth/resend-verification` et
                    `/api/auth/forgot-password` — ainsi que le changement d'adresse, qui
                    déclenche le même envoi d'e-mail. **`/api/auth/refresh` n'est pas limité :**
                    qui l'appelle présente déjà un secret valide, et l'y soumettre casserait la
                    seule mécanique qui maintient les gens connectés.

                    Chaque route porte **deux** budgets sur une fenêtre glissante : un budget
                    serré sur la cible — le compte visé, ou l'adresse destinataire de l'e-mail —
                    et un budget large sur l'adresse IP. Une adresse IP ne désigne pas une
                    personne : derrière un NAT ou un partage de connexion elle en désigne des
                    dizaines, et la borner seule ferait qu'un compte en bloque un autre.

                    | Route | Par compte / adresse visée | Par IP | Fenêtre |
                    |---|---|---|---|
                    | `/auth/login` | 10 **échecs** | 50 **échecs** | 15 min |
                    | `/auth/register` | 5 | 30 | 1 h |
                    | `/auth/resend-verification` | 3 | 20 | 1 h |
                    | `/auth/forgot-password` | 3 | 20 | 1 h |

                    Sur la connexion, **seuls les échecs comptent**, et une connexion réussie
                    remet le compteur du compte à zéro : se connecter cent fois avec le bon mot
                    de passe ne consomme rien. Un refus ne consomme rien non plus — réessayer
                    pour voir si l'attente a suffi ne rallonge pas l'attente.

                    Tout `429` porte un en-tête `Retry-After`, en secondes, qui dit quand la
                    fenêtre rouvre réellement. Fiez-vous à lui plutôt qu'au message : les
                    fenêtres vont jusqu'à une heure, et « réessayez dans quelques minutes » ne
                    suffit pas à savoir quand.

                    ## Support
                    - Documentation: https://github.com/pair/docs
                    - Issues: https://github.com/pair/issues
                    """)
                .version("1.0.0")
                .contact(new Contact()
                    .name("Pair Support")
                    .email("support@pair.app")
                    .url("https://pair.app"))
                .license(new License()
                    .name("MIT License")
                    .url("https://opensource.org/licenses/MIT")))
            .servers(List.of(
                new Server()
                    .url("http://localhost:" + serverPort)
                    .description("Development server"),
                new Server()
                    .url("https://api.pair.app")
                    .description("Production server (TBD)")
            ))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT token obtenu via /api/auth/login")
                )
            );
    }
}
