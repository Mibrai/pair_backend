package org.program.pair.domain.email;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class ResendEmailService {

    private final WebClient webClient;
    private final String fromEmail;
    private final String fromName;
    private final boolean enabled;

    public ResendEmailService(
            @Value("${resend.api-key:}") String apiKey,
            @Value("${resend.from-email:infos@meetdo.fun}") String fromEmail,
            @Value("${resend.from-name:MeetDo}") String fromName,
            @Value("${resend.enabled:false}") boolean enabled) {

        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.enabled = enabled;

        // Initialize WebClient with Resend API
        this.webClient = WebClient.builder()
                .baseUrl("https://api.resend.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();

        if (enabled && (apiKey == null || apiKey.isEmpty())) {
            log.warn("Resend is enabled but no API key is configured");
        } else if (enabled) {
            log.info("Resend email service initialized with from: {} <{}>", fromName, fromEmail);
        }
    }

    /**
     * Send an HTML email via Resend API
     */
    public boolean sendHtmlEmail(String to, String subject, String htmlContent) {
        if (!enabled) {
            log.debug("Resend is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Map<String, Object> emailRequest = new HashMap<>();
        emailRequest.put("from", fromName + " <" + fromEmail + ">");
        emailRequest.put("to", new String[]{to});
        emailRequest.put("subject", subject);
        emailRequest.put("html", htmlContent);

        return sendEmail(emailRequest, to, subject);
    }

    /**
     * Send a text email via Resend API
     */
    public boolean sendTextEmail(String to, String subject, String textContent) {
        if (!enabled) {
            log.debug("Resend is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Map<String, Object> emailRequest = new HashMap<>();
        emailRequest.put("from", fromName + " <" + fromEmail + ">");
        emailRequest.put("to", new String[]{to});
        emailRequest.put("subject", subject);
        emailRequest.put("text", textContent);

        return sendEmail(emailRequest, to, subject);
    }

    /**
     * Send an email with both text and HTML content
     */
    public boolean sendEmail(String to, String subject, String textContent, String htmlContent) {
        if (!enabled) {
            log.debug("Resend is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Map<String, Object> emailRequest = new HashMap<>();
        emailRequest.put("from", fromName + " <" + fromEmail + ">");
        emailRequest.put("to", new String[]{to});
        emailRequest.put("subject", subject);
        emailRequest.put("text", textContent);
        emailRequest.put("html", htmlContent);

        return sendEmail(emailRequest, to, subject);
    }

    /**
     * Internal method to send email via Resend API
     */
    private boolean sendEmail(Map<String, Object> emailRequest, String to, String subject) {
        try {
            Map<String, Object> response = webClient.post()
                    .uri("/emails")
                    .bodyValue(emailRequest)
                    .retrieve()
                    .onStatus(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        clientResponse -> clientResponse.bodyToMono(String.class)
                            .flatMap(errorBody -> {
                                log.error("Resend API error: {} - {}", clientResponse.statusCode(), errorBody);
                                return Mono.error(new RuntimeException("Resend API error: " + errorBody));
                            })
                    )
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("id")) {
                log.info("Email sent successfully via Resend to: {} (ID: {})", to, response.get("id"));
                return true;
            } else {
                log.error("Unexpected response from Resend API: {}", response);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to send email to: {} with subject: {}", to, subject, e);
            return false;
        }
    }

    /**
     * Check if Resend is enabled and configured
     */
    public boolean isEnabled() {
        return enabled;
    }
}
