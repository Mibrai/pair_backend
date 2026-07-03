package org.program.pair.domain.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.ForgotPasswordRequest;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RefreshRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.auth.dto.ResetPasswordRequest;
import org.program.pair.shared.security.RateLimiter;
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

    @GetMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@RequestParam String token) {
        authService.verifyEmail(token);
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
