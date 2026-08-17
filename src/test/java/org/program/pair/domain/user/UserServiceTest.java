package org.program.pair.domain.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.user.dto.UpdateProfileRequest;
import org.program.pair.repository.BadgeAwardRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.sanitizer.HtmlSanitizer;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    UserRepository userRepository;

    @Mock
    BadgeAwardRepository badgeAwardRepository;

    @Mock
    HtmlSanitizer sanitizer;

    /**
     * Le profil porte désormais son compteur d'abonnés, que {@code toPrivateDto}
     * et {@code toPublicDto} vont chercher ici. Sans cette doublure, toute
     * méthode qui rend un profil casse sur un {@code NullPointerException} — y
     * compris celles qui ne parlent que de bio ou de rayon de flou.
     */
    @Mock
    org.program.pair.domain.subscription.SubscriptionService subscriptionService;

    @InjectMocks
    UserService userService;

    @Test
    void updateProfile_devraitSanitizerLaBio_pourEviterXSS() {
        User user = buildUser();
        when(userRepository.findById(user.getId()))
            .thenReturn(Optional.of(user).filter(u -> u.getIsActive()));
        String malicious = "<script>alert('xss')</script>Salut";
        String cleaned = "Salut";
        when(sanitizer.sanitize(malicious)).thenReturn(cleaned);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.updateProfile(user.getId(),
            new UpdateProfileRequest(null, malicious, null, null, null, null));

        verify(sanitizer).sanitize(malicious);
    }

    @Test
    void updateProfile_blurRadius_neDoitJamaisEtreInferieurA100m() {
        User user = buildUser();
        when(userRepository.findById(user.getId()))
            .thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.updateProfile(user.getId(),
            new UpdateProfileRequest(null, null, null, null, null, 10)); // trop petit

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getBlurRadiusM()).isGreaterThanOrEqualTo(100);
    }

    @Test
    void deactivateAccount_devraitMasquerImmediatement_delaCarte() {
        User user = buildUser();
        user.setLocationPublic(true);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.deactivateAccount(user.getId());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getIsActive()).isFalse();
        assertThat(captor.getValue().getLocationPublic()).isFalse();
    }

    private User buildUser() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setIsActive(true);
        u.setBlurRadiusM(500);
        u.setVerificationStatus(VerificationStatus.UNVERIFIED);
        return u;
    }
}
