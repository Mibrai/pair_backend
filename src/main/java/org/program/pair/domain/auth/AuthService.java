package org.program.pair.domain.auth;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.dto.AuthResponse;
import org.program.pair.domain.auth.dto.LoginRequest;
import org.program.pair.domain.auth.dto.RegisterRequest;
import org.program.pair.domain.user.User;
import org.program.pair.repository.UserRepository;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.shared.exception.EmailAlreadyExistsException;
import org.program.pair.shared.exception.InvalidCredentialsException;
import org.program.pair.shared.exception.InvalidTokenException;
import org.program.pair.shared.exception.UserNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final EmailVerificationService emailVerificationService;

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new EmailAlreadyExistsException("Cet email est déjà utilisé.");
        }

        User user = new User();
        user.setEmail(request.email().toLowerCase().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setDisplayName(request.displayName().strip());
        user.setVerificationStatus(VerificationStatus.UNVERIFIED);
        user.setIsActive(true);
        user = userRepository.save(user);

        emailVerificationService.sendVerificationEmail(user);

        return buildAuthResponse(user);
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().toLowerCase())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new InvalidCredentialsException("Identifiants invalides."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Identifiants invalides.");
        }

        user.setLastActiveAt(Instant.now());
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    public AuthResponse refreshToken(String refreshToken) {
        if (!tokenProvider.validateToken(refreshToken)) {
            throw new InvalidTokenException("Refresh token invalide ou expiré.");
        }
        UUID userId = tokenProvider.extractUserId(refreshToken);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));
        return buildAuthResponse(user);
    }

    public void verifyEmail(String token) {
        emailVerificationService.verifyToken(token);
    }

    private AuthResponse buildAuthResponse(User user) {
        return new AuthResponse(
            tokenProvider.generateAccessToken(user.getId(), user.getEmail()),
            tokenProvider.generateRefreshToken(user.getId()),
            user.getId(),
            user.getDisplayName(),
            user.getVerificationStatus().name()
        );
    }
}
