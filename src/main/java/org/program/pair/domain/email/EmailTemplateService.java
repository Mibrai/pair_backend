package org.program.pair.domain.email;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    private final ResendEmailService resendEmailService;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    /**
     * Send welcome email to new user
     */
    public boolean sendWelcomeEmail(String to, String userName) {
        String subject = "Bienvenue sur MeetDo ! 🎉";

        String textContent = String.format("""
            Bonjour %s,

            Bienvenue sur MeetDo !

            Nous sommes ravis de vous compter parmi nous. MeetDo est votre plateforme pour trouver des partenaires sportifs et organiser des activités ensemble.

            Pour commencer :
            - Complétez votre profil
            - Ajoutez vos activités préférées
            - Découvrez les programmes autour de vous

            À très bientôt !
            L'équipe MeetDo

            %s
            """, userName, frontendUrl);

        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: linear-gradient(135deg, #667eea 0%%, #764ba2 100%%); color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; padding: 12px 30px; background: #667eea; color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🎉 Bienvenue sur MeetDo !</h1>
                    </div>
                    <div class="content">
                        <h2>Bonjour %s,</h2>
                        <p>Nous sommes ravis de vous compter parmi nous !</p>
                        <p>MeetDo est votre plateforme pour trouver des partenaires sportifs et organiser des activités ensemble.</p>

                        <h3>Pour commencer :</h3>
                        <ul>
                            <li>✅ Complétez votre profil</li>
                            <li>🏃 Ajoutez vos activités préférées</li>
                            <li>🗺️ Découvrez les programmes autour de vous</li>
                        </ul>

                        <a href="%s" class="button">Découvrir MeetDo</a>

                        <p>À très bientôt !</p>
                        <p><strong>L'équipe MeetDo</strong></p>
                    </div>
                    <div class="footer">
                        <p>© 2026 MeetDo. Tous droits réservés.</p>
                    </div>
                </div>
            </body>
            </html>
            """, userName, frontendUrl);

        return resendEmailService.sendEmail(to, subject, textContent, htmlContent);
    }

    /**
     * Send password reset email
     */
    public boolean sendPasswordResetEmail(String to, String userName, String resetToken) {
        String subject = "Réinitialisation de votre mot de passe MeetDo";
        String resetUrl = frontendUrl + "/reset-password?token=" + resetToken;

        String textContent = String.format("""
            Bonjour %s,

            Vous avez demandé à réinitialiser votre mot de passe.

            Cliquez sur le lien ci-dessous pour créer un nouveau mot de passe :
            %s

            Ce lien est valide pendant 1 heure.

            Si vous n'avez pas demandé cette réinitialisation, ignorez cet email.

            L'équipe MeetDo
            """, userName, resetUrl);

        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #667eea; color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; padding: 12px 30px; background: #667eea; color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; }
                    .warning { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 20px 0; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>🔑 Réinitialisation de mot de passe</h1>
                    </div>
                    <div class="content">
                        <h2>Bonjour %s,</h2>
                        <p>Vous avez demandé à réinitialiser votre mot de passe MeetDo.</p>

                        <a href="%s" class="button">Réinitialiser mon mot de passe</a>

                        <p>Ce lien est valide pendant <strong>1 heure</strong>.</p>

                        <div class="warning">
                            <strong>⚠️ Attention :</strong> Si vous n'avez pas demandé cette réinitialisation, ignorez cet email. Votre mot de passe actuel reste inchangé.
                        </div>

                        <p>L'équipe MeetDo</p>
                    </div>
                    <div class="footer">
                        <p>© 2026 MeetDo. Tous droits réservés.</p>
                    </div>
                </div>
            </body>
            </html>
            """, userName, resetUrl);

        return resendEmailService.sendEmail(to, subject, textContent, htmlContent);
    }

    /**
     * Send email verification
     */
    public boolean sendEmailVerification(String to, String userName, String verificationToken) {
        String subject = "Vérifiez votre adresse email MeetDo";
        String verificationUrl = frontendUrl + "/verify-email?token=" + verificationToken;

        String textContent = String.format("""
            Bonjour %s,

            Merci de vous être inscrit sur MeetDo !

            Pour activer votre compte, veuillez vérifier votre adresse email en cliquant sur le lien ci-dessous :
            %s

            Ce lien est valide pendant 24 heures.

            L'équipe MeetDo
            """, userName, verificationUrl);

        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #667eea; color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; padding: 12px 30px; background: #667eea; color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>✉️ Vérification d'email</h1>
                    </div>
                    <div class="content">
                        <h2>Bonjour %s,</h2>
                        <p>Merci de vous être inscrit sur MeetDo !</p>
                        <p>Pour activer votre compte, veuillez vérifier votre adresse email :</p>

                        <a href="%s" class="button">Vérifier mon email</a>

                        <p>Ce lien est valide pendant <strong>24 heures</strong>.</p>

                        <p>L'équipe MeetDo</p>
                    </div>
                    <div class="footer">
                        <p>© 2026 MeetDo. Tous droits réservés.</p>
                    </div>
                </div>
            </body>
            </html>
            """, userName, verificationUrl);

        return resendEmailService.sendEmail(to, subject, textContent, htmlContent);
    }

    /**
     * Send new message notification
     */
    public boolean sendNewMessageNotification(String to, String userName, String senderName) {
        String subject = "Nouveau message de " + senderName;
        String messagesUrl = frontendUrl + "/messages";

        String textContent = String.format("""
            Bonjour %s,

            Vous avez reçu un nouveau message de %s sur MeetDo.

            Connectez-vous pour le lire : %s

            L'équipe MeetDo
            """, userName, senderName, messagesUrl);

        String htmlContent = String.format("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; line-height: 1.6; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; padding: 20px; }
                    .header { background: #667eea; color: white; padding: 30px; text-align: center; border-radius: 10px 10px 0 0; }
                    .content { background: #f9f9f9; padding: 30px; border-radius: 0 0 10px 10px; }
                    .button { display: inline-block; padding: 12px 30px; background: #667eea; color: white; text-decoration: none; border-radius: 5px; margin: 20px 0; }
                    .footer { text-align: center; margin-top: 30px; color: #666; font-size: 12px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="header">
                        <h1>💬 Nouveau message</h1>
                    </div>
                    <div class="content">
                        <h2>Bonjour %s,</h2>
                        <p>Vous avez reçu un nouveau message de <strong>%s</strong> sur MeetDo.</p>

                        <a href="%s" class="button">Lire le message</a>

                        <p>L'équipe MeetDo</p>
                    </div>
                    <div class="footer">
                        <p>© 2026 MeetDo. Tous droits réservés.</p>
                        <p><a href="%s/settings/notifications" style="color: #666;">Gérer mes notifications</a></p>
                    </div>
                </div>
            </body>
            </html>
            """, userName, senderName, messagesUrl, frontendUrl);

        return resendEmailService.sendEmail(to, subject, textContent, htmlContent);
    }
}
