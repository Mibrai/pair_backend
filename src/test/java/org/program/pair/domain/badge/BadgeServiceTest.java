package org.program.pair.domain.badge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.program.pair.domain.trust.Badge;
import org.program.pair.domain.trust.BadgeAward;
import org.program.pair.domain.trust.BadgeConditionType;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BadgeServiceTest {

    @Mock
    BadgeRepository badgeRepository;
    @Mock
    BadgeAwardRepository badgeAwardRepository;
    @Mock
    UserRepository userRepository;
    @Mock
    ProgramRepository programRepository;
    @Mock
    ProgressionRepository progressionRepository;
    @Mock
    UserActivityRepository userActivityRepository;
    @Mock
    PeerRecommendationRepository peerRecommendationRepository;
    @InjectMocks
    BadgeService badgeService;

    @Test
    void evaluateBadges_neDoitPasRedonnerUnBadgeDejaObtenu() {
        UUID userId = UUID.randomUUID();

        Badge verifiedBadge = new Badge();
        verifiedBadge.setId(UUID.randomUUID());
        verifiedBadge.setCode("VERIFIED_EMAIL");
        verifiedBadge.setConditionType(BadgeConditionType.VERIFICATION);

        when(badgeRepository.findAll()).thenReturn(List.of(verifiedBadge));

        BadgeAward existingAward = new BadgeAward();
        existingAward.setBadge(verifiedBadge);
        BadgeAward.BadgeAwardId awardId = new BadgeAward.BadgeAwardId();
        awardId.setBadgeId(verifiedBadge.getId());
        awardId.setUserId(userId);
        existingAward.setId(awardId);

        when(badgeAwardRepository.findByUserIdAndBadgeId(userId, verifiedBadge.getId()))
            .thenReturn(Optional.of(existingAward)); // déjà obtenu

        badgeService.evaluateBadges(userId);

        verify(badgeAwardRepository, never()).save(any());
    }

    @Test
    void evaluateBadges_devraitDecernerVerifiedEmail_siConditionRemplie() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setVerificationStatus(VerificationStatus.EMAIL_VERIFIED);

        Badge verifiedBadge = new Badge();
        verifiedBadge.setId(UUID.randomUUID());
        verifiedBadge.setCode("VERIFIED_EMAIL");
        verifiedBadge.setConditionType(BadgeConditionType.VERIFICATION);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(badgeRepository.findAll()).thenReturn(List.of(verifiedBadge));
        when(badgeAwardRepository.findByUserIdAndBadgeId(userId, verifiedBadge.getId()))
            .thenReturn(Optional.empty()); // pas encore obtenu
        when(badgeAwardRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        badgeService.evaluateBadges(userId);

        verify(badgeAwardRepository).save(any(BadgeAward.class));
    }
}
