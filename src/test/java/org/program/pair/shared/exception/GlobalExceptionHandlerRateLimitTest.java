package org.program.pair.shared.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.shared.dto.ErrorResponse;
import org.program.pair.shared.i18n.Messages;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Le 429 tel qu'il part sur le réseau : un en-tête en plus, et un corps qui n'a
 * pas bougé.
 *
 * <p>Les deux moitiés comptent autant l'une que l'autre. Le {@code Retry-After}
 * est la demande 4 du chantier mobile du 10/09 — sans lui, un client à qui l'on
 * dit « réessayez dans quelques minutes » devant une fenêtre glissante de quinze
 * minutes se fait refuser autant de fois. Et le corps passe par la traduction
 * depuis le 07/09, correctif qu'un passage à {@code ResponseEntity} pourrait
 * défaire sans que rien ne le signale : le code {@code RATE_LIMITED} resterait
 * juste, seul le message repasserait au français en toutes langues.
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerRateLimitTest {

    @Mock
    private Messages messages;

    @InjectMocks
    private GlobalExceptionHandler handler;

    @Test
    void unRefusDeQuota_porteSonRetryAfterEnSecondes() {
        doReturn(null).when(messages).getOrNull(anyString());

        ResponseEntity<ErrorResponse> reponse = handler.handleRateLimit(
            new TooManyRequestsException("Trop de demandes.", Duration.ofMinutes(42)));

        assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(reponse.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2520");
    }

    @Test
    void leCorps_gardeSonCodeEtSaTraduction() {
        // Le message traduit l'emporte sur le littéral français du limiteur :
        // c'est le correctif du 07/09, et il doit survivre à l'en-tête.
        doReturn("Too many requests recently.").when(messages).getOrNull("error.RATE_LIMITED");

        ResponseEntity<ErrorResponse> reponse = handler.handleRateLimit(
            new TooManyRequestsException("Trop de demandes.", Duration.ofSeconds(30)));

        assertThat(reponse.getBody()).isNotNull();
        assertThat(reponse.getBody().code()).isEqualTo(ErrorCode.RATE_LIMITED.name());
        assertThat(reponse.getBody().message()).isEqualTo("Too many requests recently.");
    }

    @Test
    void unDelaiInferieurALaSeconde_neSAnnonceJamaisParUnZero() {
        // « Retry-After: 0 » dit d'y retourner tout de suite, ce qui est le
        // contraire du propos ; et tronquer 90,5 s à 90 renverrait le client un
        // instant trop tôt, donc sur un refus de plus.
        doReturn(null).when(messages).getOrNull(anyString());

        assertThat(handler.handleRateLimit(
                new TooManyRequestsException("Trop de demandes.", Duration.ofMillis(120)))
            .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

        assertThat(handler.handleRateLimit(
                new TooManyRequestsException("Trop de demandes.", Duration.ofMillis(90_500)))
            .getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("91");
    }
}
