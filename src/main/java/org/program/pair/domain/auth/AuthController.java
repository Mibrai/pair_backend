package org.program.pair.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.dto.ResendVerificationRequest;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.ForgotPasswordRequest;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RefreshRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.auth.dto.ResetPasswordRequest;
import org.program.pair.shared.security.RateLimiter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;
    private final RateLimiter rateLimiter;
    private final ReponseVerificationEmail reponseVerification;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request,
                                  HttpServletRequest httpRequest) {
        rateLimiter.checkRegister(httpRequest.getRemoteAddr());
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request,
                               HttpServletRequest httpRequest) {
        rateLimiter.checkLogin(httpRequest.getRemoteAddr());
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refreshToken(request.refreshToken());
    }

    /**
     * Cible historique du lien envoyé par e-mail, et route d'API de
     * l'application mobile.
     *
     * <p>Les deux appelants ne veulent pas la même chose. L'app attend un
     * contrat JSON binaire, inchangé ici. Un navigateur, lui, affichait
     * jusqu'ici {@code {"message":...}} en pleine page — un testeur qui voit ça
     * conclut que la vérification a échoué, alors qu'elle vient de réussir.
     * L'arbitrage vit dans {@link ReponseVerificationEmail}, partagé avec le
     * chemin court {@code /v/{token}}.
     *
     * <p><b>Cette route reste servie</b> bien que les e-mails partent désormais
     * sur {@code /v/{token}} : les liens déjà en circulation la portent, et ils
     * valent 24 heures.
     */
    @GetMapping("/verify-email")
    public ResponseEntity<?> verifyEmail(
            @RequestParam String token,
            @RequestHeader(value = HttpHeaders.ACCEPT, required = false) String accept) {
        return reponseVerification.repondre(token, accept);
    }

    /**
     * Renvoi d'un lien de vérification.
     *
     * <p>La page « lien expiré » demande à l'utilisateur d'en redemander un ;
     * encore faut-il que ce soit possible. Répond toujours 200, y compris pour
     * une adresse inconnue ou déjà vérifiée, comme {@code /forgot-password} :
     * un code distinct dirait à qui essaie des adresses lesquelles sont
     * inscrites.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkResendVerification(httpRequest.getRemoteAddr());
        authService.resendVerificationEmail(request.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkPasswordReset(httpRequest.getRemoteAddr());
        authService.sendPasswordResetEmail(request.email());
        // Toujours 200 même si l'email n'existe pas (éviter l'énumération)
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        // For JWT-based authentication, logout is primarily handled client-side
        // by removing the token. This endpoint can be used for:
        // - Logging logout events
        // - Token blacklisting (if implemented)
        // - Session cleanup (if needed)
        authService.logout(httpRequest);
        return ResponseEntity.ok().build();
    }
}
