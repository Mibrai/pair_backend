package org.program.pair.shared.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.shared.dto.ErrorResponse;
import org.program.pair.shared.dto.ScheduleConflictResponse;
import org.program.pair.shared.i18n.Messages;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Messages messages;

    /**
     * Code stable du refus : celui porté par l'exception s'il existe, sinon le
     * code générique historique du type d'exception. Les exceptions levées sans
     * code explicite gardent donc exactement le corps d'erreur qu'elles
     * produisaient avant l'introduction de {@link ErrorCode}.
     */
    private static String codeOf(Throwable ex, ErrorCode fallback) {
        if (ex instanceof HasErrorCode holder && holder.getErrorCode() != null) {
            return holder.getErrorCode().name();
        }
        return fallback.name();
    }

    /**
     * Message destiné à l'utilisateur, dans la langue demandée quand le refus
     * est nommé et traduit ({@code error.<CODE>}), sinon celui de l'exception.
     *
     * <p>La traduction ne couvre donc que les refus explicitement nommés : tout
     * le reste garde mot pour mot le message qu'il produisait. C'est ce qui rend
     * ce changement additif — un client sans {@code Accept-Language} reçoit la
     * locale par défaut, donc le français, donc les mêmes chaînes qu'avant.
     *
     * <p>Réserve connue : les refus d'inscription à un programme étaient rédigés
     * en anglais dans une API par défaut francophone. Leur version française est
     * désormais servie sans en-tête. C'est un changement visible, assumé, et
     * signalé au client.
     */
    private String messageOf(Throwable ex, String code) {
        String translated = messages.getOrNull("error." + code);
        return translated != null ? translated : ex.getMessage();
    }

    private ErrorResponse errorFor(Throwable ex, ErrorCode fallback) {
        String code = codeOf(ex, fallback);
        return new ErrorResponse(code, messageOf(ex, code), Instant.now());
    }

    /**
     * Le texte des refus qui n'ont rien à dire au client : le {@code 500}
     * générique et le filet {@code 409} d'une contrainte violée.
     *
     * <p>Ces deux-là ne peuvent pas passer par {@code messageOf} : l'un n'a pas
     * d'exception dont le message soit montrable, l'autre porte le code
     * générique {@code CONFLICT}, qui n'a volontairement aucune clé
     * {@code error.CONFLICT} — lui en donner une écraserait le message propre de
     * la soixantaine de refus qui s'appuient encore sur ce repli (P-BA-11 étape
     * 4). D'où la lecture directe d'une clé, et le littéral de repli : un
     * gestionnaire d'exceptions qui lève sur une clé absente ferait perdre la
     * réponse entière, et avec elle le 500 qu'il était en train de composer.
     */
    private String interne() {
        String translated = messages.getOrNull("error.INTERNAL_ERROR");
        return translated != null ? translated : "Une erreur est survenue.";
    }

    private String conflitGenerique() {
        String translated = messages.getOrNull("error.CONFLICT.generic");
        return translated != null ? translated : "Cette action n'est pas possible dans l'état actuel.";
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> f.getField() + " : " + f.getDefaultMessage())
            .collect(Collectors.joining(", "));
        return new ErrorResponse(ErrorCode.VALIDATION_ERROR.name(), message, Instant.now());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(UserNotFoundException ex) {
        return new ErrorResponse(ErrorCode.NOT_FOUND.name(), ex.getMessage(), Instant.now());
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenException ex) {
        return errorFor(ex, ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauth(InvalidCredentialsException ex) {
        return new ErrorResponse(ErrorCode.INVALID_CREDENTIALS.name(), "Identifiants invalides.", Instant.now());
    }

    @ExceptionHandler(InvalidTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidToken(InvalidTokenException ex) {
        return new ErrorResponse(ErrorCode.INVALID_TOKEN.name(), ex.getMessage(), Instant.now());
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleEmailExists(EmailAlreadyExistsException ex) {
        return new ErrorResponse(ErrorCode.EMAIL_EXISTS.name(), ex.getMessage(), Instant.now());
    }

    /**
     * Le refus d'un quota.
     *
     * <p><b>Passe par {@code errorFor} depuis le 07/09</b>, et donc par la
     * traduction. Il construisait sa réponse à la main, ce qui court-circuitait
     * {@code messageOf} : le code {@code RATE_LIMITED} était bien porté — le
     * client s'y fie — mais le message restait le littéral français du limiteur,
     * quelle que soit la langue demandée. Le même défaut que le corps de l'e-mail
     * de vérification, au même moment du parcours : quelqu'un qui découvre
     * l'application, et à qui on refuse son premier geste dans une langue qu'il
     * ne lit pas.
     *
     * <p><b>Sert un {@code Retry-After} depuis le 10/09</b>, et c'est pourquoi
     * cette méthode rend une {@code ResponseEntity} là où ses voisines se
     * contentent d'un {@code @ResponseStatus} : le corps ne suffisait pas à dire
     * quand revenir. Nos fenêtres sont longues et glissantes — un quart d'heure
     * sur la connexion, une heure sur l'inscription et les envois d'e-mail —,
     * donc l'instant de réouverture ne se déduit pas de l'heure du refus, et le
     * message qui disait « dans quelques minutes » envoyait le client se faire
     * refuser à nouveau. Le chiffre vient du limiteur, qui seul tient les
     * horodatages de la fenêtre ; il n'est pas une constante.
     *
     * <p>Le corps, lui, ne bouge pas d'une virgule : même {@code errorFor}, donc
     * même code {@code RATE_LIMITED} et même passage par la traduction.
     */
    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(TooManyRequestsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSecondes()))
            .body(errorFor(ex, ErrorCode.RATE_LIMITED));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleResourceNotFound(ResourceNotFoundException ex) {
        return errorFor(ex, ErrorCode.NOT_FOUND);
    }

    @ExceptionHandler(ValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(ValidationException ex) {
        return errorFor(ex, ErrorCode.VALIDATION_ERROR);
    }

    /**
     * Chevauchement d'agenda : un {@code 409} qui dit aussi <b>contre quoi</b>.
     *
     * <p>Le corps est un {@link org.program.pair.shared.dto.ScheduleConflictResponse}
     * et non un {@link ErrorResponse} : le client construit une feuille listant les
     * conflits, avec un bouton « quitter » par ligne, ce qu'un message de refus ne
     * permet pas. Les trois champs communs gardent la même sémantique qu'ailleurs.
     */
    @ExceptionHandler(ScheduleConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ScheduleConflictResponse handleScheduleConflict(ScheduleConflictException ex) {
        String code = ErrorCode.SCHEDULE_CONFLICT.name();
        return new ScheduleConflictResponse(
            code, messageOf(ex, code), ex.getConflicts(), Instant.now());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleBusiness(BusinessException ex) {
        return errorFor(ex, ErrorCode.BUSINESS_RULE_VIOLATION);
    }

    /**
     * Refus lié à l'état de la ressource, avec un code nommé — ce que
     * {@code IllegalStateException} ci-dessus rendait déjà en {@code 409},
     * mais sans jamais pouvoir dire lequel.
     */
    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(ConflictException ex) {
        return errorFor(ex, ErrorCode.CONFLICT);
    }

    /**
     * Le filet d'une contrainte de base violée : {@code 409}, sans un mot de la
     * base (P-BL-12 étape 2).
     *
     * <p>Ces exceptions tombaient sur {@code @ExceptionHandler(Exception.class)},
     * donc en {@code 500}. Le cas qui a motivé ce gestionnaire est
     * l'enregistrement d'un jeton d'appareil déjà attaché à un autre compte :
     * {@code device_tokens.token} est {@code UNIQUE}, l'insertion cassait, et
     * l'appelant recevait {@code 500} là où il y avait un conflit d'état
     * parfaitement nommable. Ce chemin-là est désormais corrigé à la source — le
     * jeton est réattribué —, mais la course de deux enregistrements simultanés
     * reste possible, et toute autre contrainte d'unicité de la base peut sortir
     * ici demain.
     *
     * <p><b>Le message ne vient jamais de l'exception.</b> Celui de Spring cite
     * le nom de la contrainte, celui de la table et parfois la valeur refusée :
     * de quoi cartographier le schéma depuis l'extérieur. Il part au journal, où
     * il est indispensable, et nulle part ailleurs.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                      HttpServletRequest request) {
        log.warn("Contrainte de base violée sur {} : {}", request.getRequestURI(),
            ex.getMostSpecificCause().getMessage());
        return new ErrorResponse(ErrorCode.CONFLICT.name(), conflitGenerique(), Instant.now());
    }

    /**
     * Un paramètre du bon nom et du mauvais type : {@code 400}, le nom du
     * paramètre, <b>et rien de la valeur reçue</b>.
     *
     * <p>Elle y était : « valeur 'abc' n'est pas du type attendu ». Renvoyer à
     * l'appelant ce qu'il a envoyé est sans danger quand c'est lui qui l'a écrit,
     * et ne l'est plus dès qu'un tiers a fabriqué le lien sur lequel il a cliqué
     * — une valeur reflétée telle quelle dans un corps d'erreur est le matériau
     * d'une injection chez qui l'affiche (P-BA-10 étape 5). La valeur reste au
     * journal, du côté où elle sert à diagnostiquer.
     *
     * <p>Le nom, lui, est utile et sans risque : il vient de notre signature de
     * méthode, pas de la requête. D'où la clé dédiée
     * {@code error.INVALID_PARAMETER.named} — {@code messageOf} ne peut pas la
     * servir, puisqu'il appelle la variante sans argument et rendrait
     * {@code ''{0}''} en clair.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Type mismatch sur le paramètre '{}' : valeur reçue '{}'", ex.getName(), ex.getValue());
        String message = messages.getOrNull("error.INVALID_PARAMETER.named", ex.getName());
        if (message == null) {
            message = "Paramètre '" + ex.getName() + "' invalide.";
        }
        return new ErrorResponse(ErrorCode.INVALID_PARAMETER.name(), message, Instant.now());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleJsonParseError(HttpMessageNotReadableException ex, HttpServletRequest request) {
        String message = "Invalid JSON format. Ensure you're using double quotes (\") for strings, not single quotes (') or backticks (`).";
        log.warn("JSON parse error on {}: {}", request.getRequestURI(), ex.getMessage());
        return new ErrorResponse(ErrorCode.INVALID_JSON.name(), message, Instant.now());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        String supported = ex.getSupportedHttpMethods() != null
            ? ex.getSupportedHttpMethods().toString()
            : "unknown";

        log.warn("Method not supported: {} {} (supported: {})", method, uri, supported);

        return new ErrorResponse(
            ErrorCode.METHOD_NOT_ALLOWED.name(),
            String.format("HTTP %s not supported for %s. Supported methods: %s", method, uri, supported),
            Instant.now()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNoResourceFound(NoResourceFoundException ex, HttpServletRequest request) {
        // Don't log WebSocket connection attempts as errors
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/ws")) {
            log.debug("WebSocket connection attempt to {}", uri);
        } else {
            log.warn("Resource not found: {}", uri);
        }
        return new ErrorResponse(ErrorCode.NOT_FOUND.name(), "Resource not found: " + uri, Instant.now());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        // Sans ce handler dédié, ResponseStatusException correspond aussi à @ExceptionHandler(Exception.class)
        // ci-dessous, qui la remplace systématiquement par un 500 générique en ignorant le status voulu
        // (ex: ResponseStatusException(NOT_FOUND) devenait un 500 INTERNAL_ERROR).
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        if (status.is5xxServerError()) {
            log.error("ResponseStatusException: {} - {}", status, message, ex);
        } else {
            log.warn("ResponseStatusException: {} - {}", status, message);
        }
        return ResponseEntity.status(status).body(new ErrorResponse(status.name(), message, Instant.now()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, HttpServletRequest request) {
        // Don't log WebSocket connection attempts as errors
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/ws")) {
            log.debug("WebSocket error on {}: {}", uri, ex.getMessage());
        } else {
            log.error("Erreur non gérée", ex);
        }
        return new ErrorResponse(ErrorCode.INTERNAL_ERROR.name(), interne(), Instant.now());
    }
}
