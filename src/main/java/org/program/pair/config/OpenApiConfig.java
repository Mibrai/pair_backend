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

                    ## Rate Limiting
                    - Recherche: 20 req/min
                    - Upload: 10 req/min
                    - Auth: 5-10 req/min

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
