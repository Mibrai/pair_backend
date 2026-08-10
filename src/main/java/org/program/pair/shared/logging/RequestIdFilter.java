package org.program.pair.shared.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rend joignables une trace client et une trace serveur.
 *
 * <p>Le client pose un {@code X-Request-Id} sur chaque requête et le consigne
 * avec la durée et le statut. Tant que le serveur l'ignorait, « ça a échoué chez
 * un utilisateur » restait un incident sans contrepartie dans nos journaux. Ce
 * filtre fait trois choses, et les trois sont nécessaires ensemble :
 *
 * <ol>
 *   <li>il <b>relit</b> l'en-tête, ou en <b>génère</b> un si l'appelant n'en a
 *       pas posé — un client tiers ne doit pas priver l'incident de sa clé ;</li>
 *   <li>il le <b>renvoie</b> dans la réponse, y compris en erreur, pour que le
 *       client sache sous quelle clé chercher ;</li>
 *   <li>il le <b>dépose dans le MDC</b>, d'où le motif de journalisation
 *       ({@code logging.pattern.level} dans {@code application.properties}) le
 *       tire sur chaque ligne.</li>
 * </ol>
 *
 * <p><b>Ordre.</b> Le filtre est placé avant celui de Spring Security (ordre
 * {@code -100}) : un 401 ou un 403 doit porter l'en-tête et apparaître dans les
 * journaux sous la même clé qu'un succès. C'est justement le genre d'échec qu'on
 * cherche à corréler.
 *
 * <p><b>Valeur reçue, valeur validée.</b> L'en-tête entrant est du texte fourni
 * par l'appelant, et il est réécrit dans la réponse : le laisser passer tel quel
 * exposerait à une injection d'en-tête (CR/LF) et à des journaux pollués par des
 * mégaoctets d'identifiant. Une valeur qui ne satisfait pas
 * {@link #ACCEPTABLE_ID} est donc remplacée, pas nettoyée — corriger
 * silencieusement une clé produirait une corrélation fausse, ce qui est pire que
 * pas de corrélation.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** Clé MDC, reprise telle quelle par {@code logging.pattern.level}. */
    public static final String MDC_KEY = "requestId";

    /**
     * Le client émet 16 caractères hexadécimaux. On accepte plus large — d'autres
     * appelants ont d'autres conventions, et un UUID avec tirets est légitime —
     * mais rien qui puisse casser un en-tête ou une ligne de journal.
     */
    private static final Pattern ACCEPTABLE_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = resolve(request.getHeader(HEADER));

        // Posé avant la chaîne : la réponse peut être écrite par un handler
        // d'erreur ou par le conteneur, et l'en-tête doit y être dans les deux cas.
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Les threads sont mutualisés : sans ce retrait, la requête suivante
            // hériterait de l'identifiant de la précédente.
            MDC.remove(MDC_KEY);
        }
    }

    private String resolve(String received) {
        if (received != null && ACCEPTABLE_ID.matcher(received).matches()) {
            return received;
        }
        // Même forme que celle du client : 16 caractères hexadécimaux.
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
