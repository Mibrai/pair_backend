package org.program.pair.shared.security;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.JwtTokenProvider;
import org.program.pair.domain.auth.JwtTokenProvider.JetonLu;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final UserDetailsServiceImpl userDetailsService;
    private final MeterRegistry registre;

    /**
     * {@code Server-Timing: auth;dur=…} sur chaque réponse authentifiée
     * (P-BA-05, étape 1) : de quoi mesurer le coût de l'authentification depuis
     * l'app, sans accès aux métriques. Éteint par défaut — un en-tête de plus
     * sur chaque réponse ne se publie pas sans raison.
     */
    @Value("${pair.observabilite.server-timing:false}")
    private boolean serverTiming;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            // auth.filter (P-BA-05) : lecture du jeton et chargement du compte.
            // La chaîne qui suit n'est pas comptée — c'est le coût propre de
            // l'authentification que le plancher de ~750 ms met en cause.
            long debut = System.nanoTime();
            MotifRefusJwt motif = motifDeRefus(token, request);
            long duree = System.nanoTime() - debut;
            Timer.builder("auth.filter")
                .description("Lecture du jeton et chargement du compte, par requête")
                .tag("issue", motif == null ? "authentifie" : "refuse")
                .register(registre)
                .record(duree, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (serverTiming) {
                response.addHeader("Server-Timing",
                    String.format(java.util.Locale.ROOT, "auth;dur=%.1f", duree / 1_000_000.0));
            }
            if (motif != null) {
                // Le filtre ne répond jamais lui-même : la chaîne se poursuit sans
                // authentification et c'est le point d'entrée de SecurityConfig qui
                // rend le 401 — un seul endroit qui écrit le corps d'erreur. Le
                // motif est ce qui lui manquait pour le nommer correctement.
                request.setAttribute(MotifRefusJwt.ATTRIBUT, motif);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Ce qui interdit d'authentifier avec ce jeton, ou {@code null} s'il vaut
     * une authentification.
     *
     * <p><b>Ce que la condition disait avant.</b> Elle tenait en un
     * {@code tokenProvider.validateToken(token)}, c'est-à-dire : signature de
     * notre clé, échéance non atteinte. Rien d'autre. Or nous signons deux
     * sortes de jetons avec la même clé, et le second vaut trente jours : un
     * jeton de rafraîchissement présenté en {@code Bearer} passait donc ce test
     * et ouvrait <b>toutes</b> les routes protégées. Le claim {@code type} que
     * {@code generateRefreshToken} prenait la peine d'écrire n'était lu nulle
     * part. Toute la logique des quinze minutes était par là même facultative :
     * le trousseau du client contient, à côté du jeton court, un jeton long qui
     * ouvrait exactement les mêmes portes.
     *
     * <p>Un jeton d'accès, lui, ne porte aucun {@code type} — ni aujourd'hui, ni
     * demain : ceux qui sont en circulation ont été émis sans, et exiger le
     * claim les refuserait tous d'un coup. L'absence vaut donc « jeton
     * d'accès », et c'est {@code estJetonDeRafraichissement} qui porte cette
     * asymétrie.
     */
    private MotifRefusJwt motifDeRefus(String token, HttpServletRequest request) {
        // Une seule lecture du jeton (P-BA-05) : l'état, le type et le sujet
        // sortaient de trois vérifications de signature successives.
        // instanceof et non un switch à motifs : le projet compile encore en cible
        // Java 17, où le switch sur une interface scellée n'existe pas.
        JetonLu lu = tokenProvider.lire(token);
        if (lu instanceof JetonLu.Expire) {
            return MotifRefusJwt.EXPIRE;
        }
        if (!(lu instanceof JetonLu.Valide valide)) {
            return MotifRefusJwt.INVALIDE;
        }
        if (valide.rafraichissement()) {
            return MotifRefusJwt.JETON_DE_RAFRAICHISSEMENT;
        }
        return authentifier(request, valide);
    }

    /**
     * Charge le compte et vérifie la version du jeton (P-BS-03) : un mot de passe
     * changé ou réinitialisé incrémente la version du compte, et un jeton d'accès
     * émis avant cesse de valoir. Refusé en {@code TOKEN_EXPIRED} — c'est le
     * rafraîchissement qui tranche : il répare si la session vit encore, et rend
     * {@code INVALID_TOKEN} sinon.
     */
    private MotifRefusJwt authentifier(HttpServletRequest request, JetonLu.Valide jeton) {
        UserDetails charge = userDetailsService.loadUserById(jeton.sujet());
        UserDetails userDetails = charge;
        if (charge instanceof UserPrincipal principal) {
            Integer version = principal.getUser().getTokenVersion();
            if ((version == null ? 0 : version) != jeton.version()) {
                return MotifRefusJwt.EXPIRE;
            }
            userDetails = new UserPrincipal(principal.getUser(), jeton.session());
        }
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
        return null;
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
