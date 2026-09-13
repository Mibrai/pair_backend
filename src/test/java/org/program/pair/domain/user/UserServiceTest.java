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

import java.time.Instant;
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

    /**
     * Même raison que la doublure ci-dessus, et même mode de panne : le profil
     * privé porte désormais {@code hasPublishedAffiche}, que {@code toPrivateDto}
     * va chercher ici. Sans elle, {@code @InjectMocks} injecte {@code null} et
     * toute méthode rendant un profil casse — y compris celles qui ne parlent que
     * de bio ou de rayon de flou, et qui n'ont rien à voir avec les affiches.
     *
     * <p>Le défaut par défaut de Mockito est {@code false}, ce qui est le bon
     * repli : un compte de test n'a rien publié.
     */
    @Mock
    org.program.pair.repository.AfficheRepository afficheRepository;

    /**
     * {@code deactivateAccount} détache désormais les appareils du compte
     * (P-BL-12, étape 5). Sans cette doublure, {@code @InjectMocks} injecte
     * {@code null} et c'est la désactivation — la route de suppression de compte —
     * qui casse, pas les tests des appareils.
     */
    @Mock
    org.program.pair.domain.notification.DeviceTokenService deviceTokenService;

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

    /**
     * La date de la demande est la seule depuis laquelle la purge compte ses
     * trente jours ({@code UserRepository.findDeactivatedBefore}). Si elle n'est
     * pas posée, le compte n'est jamais purgé et l'article 17 n'est pas tenu — un
     * défaut entièrement silencieux, puisque la désactivation, elle, a marché.
     */
    @Test
    void deactivateAccount_devraitPoserLaDateDeLaDemande_pourFaireCourirLeDelai() {
        User user = buildUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.deactivateAccount(user.getId());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getDeactivatedAt()).isNotNull();
    }

    /**
     * Le second appel est un no-op, et il doit l'être <b>aussi</b> pour la date :
     * l'application rejoue la suppression après une coupure réseau, et repousser
     * l'échéance à chaque rejeu rendrait le délai de trente jours illimité.
     */
    @Test
    void deactivateAccount_neDoitPasRepousserLEcheance_quandLeCompteEstDejaDesactive() {
        User user = buildUser();
        Instant demandeInitiale = Instant.now().minusSeconds(20 * 86_400L);
        user.setIsActive(false);
        user.setDeactivatedAt(demandeInitiale);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        userService.deactivateAccount(user.getId());

        assertThat(user.getDeactivatedAt()).isEqualTo(demandeInitiale);
        verify(userRepository, never()).save(any());
    }

    /**
     * Un compte fermé ne doit plus recevoir de notification : c'est la seule chose
     * que la fermeture promet immédiatement (P-BL-12, étape 5).
     */
    @Test
    void deactivateAccount_devraitDetacherTousLesAppareils_duCompteFerme() {
        User user = buildUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        userService.deactivateAccount(user.getId());

        verify(deviceTokenService).unregisterAllUserTokens(user.getId());
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
