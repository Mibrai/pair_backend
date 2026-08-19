package org.program.pair.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserPrivateDto(
    UUID id,
    String email,
    String phone,
    String displayName,
    String bio,
    String avatarUrl,
    Double lat,
    Double lng,
    Integer blurRadiusM,
    Boolean locationPublic,
    Boolean onlineStatusVisible,
    Boolean receiveMessages,
    String verificationStatus,
    Instant createdAt,
    List<UserActivityDto> activities,

    @Schema(description = "Nombre d'abonnés de type AUTHOR — le chiffre qu'un auteur veut "
        + "voir sur son propre profil. Pas de `subscribed` ici : la contrainte "
        + "chk_subscription_not_self interdit de s'abonner à soi-même, et un booléen "
        + "toujours faux inviterait à rendre un bouton « S'abonner » sur son propre profil.")
    long subscriberCount,

    @Schema(description = "Date de sortie du parcours d'accueil, nulle tant qu'il est en "
        + "cours. Présente ici, et pas seulement sur /users/me/onboarding, parce que le "
        + "client doit décider où atterrir au démarrage : un second appel réseau au "
        + "lancement se voit à l'œil nu.")
    Instant onboardingCompletedAt,

    @Schema(description = "Dernière étape franchie, nulle pour un compte qui n'a rien "
        + "commencé. Peut valoir une étape que ce client ne connaît pas encore : la "
        + "traiter comme « en cours » plutôt que d'échouer.")
    String onboardingStep
) {}
