package org.program.pair.config;

import lombok.RequiredArgsConstructor;
import org.program.pair.shared.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(authenticationEntryPoint()))
            .authorizeHttpRequests(auth -> auth
                // Routes publiques
                .requestMatchers("/").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/refresh").permitAll()
                .requestMatchers(HttpMethod.GET,  "/api/auth/verify-email").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/forgot-password").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/reset-password").permitAll()
                // WebSocket
                .requestMatchers("/ws/**").permitAll()
                // Swagger / OpenAPI
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Actuator
                .requestMatchers("/actuator/health").permitAll()
                // Public endpoints for categories and activities (read-only)
                .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/activities").permitAll()
                // /api/map/activities a quitté cette liste le 2026-08-19 : aucun
                // écran hors session ne l'appelait, et sans identité d'appelant
                // elle rendait les organisateurs bloqués comme les autres.
                // Public Phase 3 endpoints
                .requestMatchers(HttpMethod.GET, "/api/badges").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/badges/users/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/recommendations/users/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/recommendations/stats/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reviews/programs/**").permitAll()
                // Pages publiques, lisibles sans compte. Le lien de sécurité en
                // est la première : son destinataire est un proche qui n'a pas
                // de compte meetDo, et lui en demander un viderait la
                // fonctionnalité de son sens. La confidentialité repose
                // entièrement sur le jeton, opaque et périssable.
                .requestMatchers(HttpMethod.GET, "/public/safety/**").permitAll()
                // Page publique de créneau, son JSON, son image, et l'adresse
                // courte qu'on partage réellement.
                .requestMatchers(HttpMethod.GET, "/public/slots/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/s/**").permitAll()
                // Fichiers d'association des liens universels. Ouverts sans
                // condition : Apple et Google les lisent sans identité, et une
                // redirection suffirait à faire échouer la validation.
                .requestMatchers(HttpMethod.GET, "/.well-known/**").permitAll()
                // Tout le reste : authentifié
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }

    /**
     * Ensures unauthenticated/invalid-token requests get 401, not Spring
     * Security's default 403 (which is indistinguishable from a real
     * permission denial and misleads clients into thinking a route is
     * blocked rather than that the token is missing/expired).
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                {"code":"UNAUTHORIZED","message":"Authentification requise ou token invalide.","timestamp":"%s"}"""
                .formatted(Instant.now()));
        };
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Origines autorisées (frontend)
        configuration.setAllowedOrigins(Arrays.asList(
            // Production Vercel
            "https://pair-frontend-omega.vercel.app",
            // Développement local
            "http://localhost:5173",
            "http://localhost:3000",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:3000"
        ));

        // Méthodes HTTP autorisées
        configuration.setAllowedMethods(Arrays.asList(
            "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // Headers autorisés
        configuration.setAllowedHeaders(Arrays.asList(
            "Authorization",
            "Content-Type",
            "Accept",
            "Origin",
            "X-Requested-With",
            // Corrélation client <-> serveur (RequestIdFilter) : sans cette entrée,
            // le preflight refuse l'en-tête et le client web perd sa clé de trace.
            "X-Request-Id",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        ));

        // Headers exposés au client
        configuration.setExposedHeaders(Arrays.asList(
            "Authorization",
            "Content-Disposition",
            // L'écho de l'identifiant de requête doit être lisible par le client,
            // qui le consigne dans son journal ; exposé, sinon fetch() le masque.
            "X-Request-Id"
        ));

        // Autoriser les credentials (cookies, Authorization header)
        configuration.setAllowCredentials(true);

        // Durée de cache de la config CORS (1 heure)
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }
}
