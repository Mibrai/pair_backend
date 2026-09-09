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
    String onboardingStep,

    @Schema(description = "Version des règles de communauté que cette personne a "
        + "acceptée, nulle si elle n'a jamais accepté.")
    String guidelinesVersion,

    @Schema(description = "Vrai s'il faut lui présenter les règles avant de la laisser "
        + "continuer. Porté ici pour la même raison que l'état d'onboarding : le client "
        + "en a besoin au démarrage, et un second appel réseau au lancement se voit. "
        + "Calculé par le serveur — comparer des versions des deux côtés finit par "
        + "diverger. Le détail est sur /api/users/me/guidelines.")
    boolean guidelinesAcceptanceRequired,

    @Schema(description = "Ce qu'est devenu le DERNIER e-mail de vérification envoyé à "
        + "ce compte — un état, pas un journal : un renvoi le ramène à PENDING. "
        + "`NONE` aucun envoi tenté · `PENDING` déposé, pas encore remis au fournisseur · "
        + "`SENT` le fournisseur l'a pris, ce qui ne dit pas qu'il est arrivé · "
        + "`DELIVERED` arrivé, l'accusé de remise le dit · "
        + "`BOUNCED` l'adresse a refusé le message — c'est le cas qui appelle "
        + "POST /api/users/me/change-email · "
        + "`FAILED` nous n'avons pas pu le remettre au fournisseur ; ce n'est pas un "
        + "défaut de l'adresse. "
        + "Traiter une valeur inconnue comme SENT plutôt qu'échouer : COMPLAINED "
        + "pourra s'ajouter.",
        allowableValues = {"NONE", "PENDING", "SENT", "DELIVERED", "BOUNCED", "FAILED"})
    String verificationEmailDelivery,

    @Schema(description = "Vrai dès que cette personne a publié au moins une affiche, "
        + "quelle que soit son audience — NOBODY compris. La question est « ai-je déjà "
        + "fait ce geste ? », et non « quelqu'un peut-il la voir ? » : une affiche "
        + "publiée pour soi seul est un geste posé, et la pastille d'amorce du fil n'a "
        + "plus lieu d'être. Porté ici pour la même raison que l'état d'onboarding — le "
        + "client en a besoin au démarrage, et cette réponse est déjà chargée : lire "
        + "GET /users/{id}/affiches pour n'en tirer qu'un oui ou non paie une liste "
        + "entière sur l'écran d'entrée du produit. Ne remplace PAS cette route là où "
        + "l'affiche mise en avant doit être dessinée : là, la question n'est plus un "
        + "booléen. Ce drapeau vit sur le DTO privé et sur aucun autre.")
    boolean hasPublishedAffiche
) {}
