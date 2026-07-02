package org.program.pair.domain.email;

import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
@Slf4j
public class SendGridEmailService {

    private final SendGrid sendGrid;
    private final String fromEmail;
    private final String fromName;
    private final boolean enabled;

    public SendGridEmailService(
            @Value("${sendgrid.api-key:}") String apiKey,
            @Value("${sendgrid.from-email:infos@meetdo.fun}") String fromEmail,
            @Value("${sendgrid.from-name:MeetDo}") String fromName,
            @Value("${sendgrid.enabled:false}") boolean enabled) {
        this.sendGrid = new SendGrid(apiKey);
        this.fromEmail = fromEmail;
        this.fromName = fromName;
        this.enabled = enabled;

        if (enabled && (apiKey == null || apiKey.isEmpty())) {
            log.warn("SendGrid is enabled but no API key is configured");
        }
    }

    /**
     * Send a simple text email
     */
    public boolean sendTextEmail(String to, String subject, String textContent) {
        if (!enabled) {
            log.debug("SendGrid is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Email from = new Email(fromEmail, fromName);
        Email toEmail = new Email(to);
        Content content = new Content("text/plain", textContent);
        Mail mail = new Mail(from, subject, toEmail, content);

        return sendMail(mail);
    }

    /**
     * Send an HTML email
     */
    public boolean sendHtmlEmail(String to, String subject, String htmlContent) {
        if (!enabled) {
            log.debug("SendGrid is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Email from = new Email(fromEmail, fromName);
        Email toEmail = new Email(to);
        Content content = new Content("text/html", htmlContent);
        Mail mail = new Mail(from, subject, toEmail, content);

        return sendMail(mail);
    }

    /**
     * Send an email with both text and HTML content
     */
    public boolean sendEmail(String to, String subject, String textContent, String htmlContent) {
        if (!enabled) {
            log.debug("SendGrid is disabled. Email would be sent to: {} with subject: {}", to, subject);
            return false;
        }

        Email from = new Email(fromEmail, fromName);
        Email toEmail = new Email(to);

        Mail mail = new Mail(from, subject, toEmail, new Content("text/plain", textContent));
        mail.addContent(new Content("text/html", htmlContent));

        return sendMail(mail);
    }

    /**
     * Internal method to send the mail via SendGrid API
     */
    private boolean sendMail(Mail mail) {
        try {
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());

            Response response = sendGrid.api(request);

            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                log.info("Email sent successfully to: {}", mail.getPersonalization().get(0).getTos().get(0).getEmail());
                return true;
            } else {
                log.error("Failed to send email. Status: {}, Body: {}",
                    response.getStatusCode(), response.getBody());
                return false;
            }
        } catch (IOException e) {
            log.error("Error sending email via SendGrid", e);
            return false;
        }
    }

    /**
     * Check if SendGrid is enabled and configured
     */
    public boolean isEnabled() {
        return enabled;
    }
}
