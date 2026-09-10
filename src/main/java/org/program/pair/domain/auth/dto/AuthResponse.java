package org.program.pair.domain.auth.dto;

import java.util.UUID;

/**
 * Ce qu'une session ouverte rend au client : les deux jetons, de quoi afficher
 * le compte, et — depuis ce lot — la durée de vie de chacun des deux jetons.
 *
 * <p><b>Pourquoi ces deux durées, et pourquoi en secondes relatives.</b> Le
 * contrat n'en publiait aucune : le client mobile portait donc « 15 minutes » et
 * « 30 jours » en constantes recopiées à la main d'un document d'août. Le jour
 * où nous aurions changé {@code jwt.access-token-expiry-ms}, rien ne le lui
 * aurait dit — son rafraîchissement anticipé serait parti trop tard, donc des
 * 401 en rafale, ou beaucoup trop tôt, donc un rafraîchissement par requête,
 * droit dans le limiteur.
 *
 * <p>Relatives, et non des dates : l'horloge d'un téléphone peut être fausse de
 * plusieurs heures, et c'est précisément en comparant une échéance absolue à
 * l'heure de l'appareil que le client a fermé des sessions parfaitement valides.
 * Une durée relative ne demande à lire aucune horloge commune.
 *
 * <p>L'ajout est <b>additif</b> : aucun champ existant ne bouge, aucun ne change
 * de sens, et un client qui ignore les deux nouveaux continue de fonctionner
 * exactement comme avant.
 *
 * @param expiresIn durée de vie du jeton d'accès, en secondes à compter de cette
 *     réponse
 * @param refreshExpiresIn durée de vie du jeton de rafraîchissement, même unité.
 *     Elle repart de zéro à chaque {@code /auth/refresh} : la réémission est
 *     glissante, une session utilisée ne vieillit pas
 */
public record AuthResponse(
    String accessToken,
    String refreshToken,
    UUID userId,
    String displayName,
    String verificationStatus,
    long expiresIn,
    long refreshExpiresIn
) {}
