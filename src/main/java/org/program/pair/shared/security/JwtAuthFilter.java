package org.program.pair.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.JwtTokenProvider;
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

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            MotifRefusJwt motif = motifDeRefus(token);
            if (motif == null) {
                authentifier(request, token);
            } else {
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
    private MotifRefusJwt motifDeRefus(String token) {
        return switch (tokenProvider.etatDe(token)) {
            case EXPIRE -> MotifRefusJwt.EXPIRE;
            case INVALIDE -> MotifRefusJwt.INVALIDE;
            case VALIDE -> tokenProvider.estJetonDeRafraichissement(token)
                ? MotifRefusJwt.JETON_DE_RAFRAICHISSEMENT
                : null;
        };
    }

    private void authentifier(HttpServletRequest request, String token) {
        UUID userId = tokenProvider.extractUserId(token);
        UserDetails userDetails = userDetailsService.loadUserById(userId);
        UsernamePasswordAuthenticationToken auth =
            new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
