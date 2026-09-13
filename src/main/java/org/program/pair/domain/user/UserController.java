package org.program.pair.domain.user;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Parameter;
import org.program.pair.shared.web.Pages;
import org.program.pair.shared.media.ProcessedMultipartFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.PracticeStatsService;
import org.program.pair.domain.audit.AuditActionType;
import org.program.pair.domain.audit.AuditLogService;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.attendance.dto.PracticeStatsDto;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.domain.media.ImageProcessor;
import org.program.pair.domain.media.MediaFileService;
import org.program.pair.domain.media.MediaValidator;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.ProgramService;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.user.dto.*;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.security.RateLimiter;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;
    private final StorageService storageService;
    private final MediaFileService mediaFileService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;
    private final ProgramService programService;
    private final PracticeStatsService practiceStatsService;
    private final BlockFilterService blockFilterService;
    private final EmailChangeService emailChangeService;
    private final RateLimiter rateLimiter;
    private final AuditLogService auditLogService;

    @GetMapping
    public Page<UserPublicDto> searchUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") @Parameter(schema = @Schema(maximum = "50", defaultValue = "20")) int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        // If no query provided, return empty result
        if (query == null || query.isBlank()) {
            return Page.empty();
        }

        // Borné comme toute route paginée (P-BA-14) : le service fait son OFFSET
        // lui-même, et recevait la taille demandée telle quelle.
        org.springframework.data.domain.Pageable borne = Pages.borne(page, size);
        return userService.searchUsers(
            query.trim(),
            latitude,
            longitude,
            borne.getPageNumber(),
            borne.getPageSize(),
            principal.getId()
        );
    }

    @GetMapping("/me")
    public UserPrivateDto getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.getMyProfile(principal.getId());
    }

    // Miroir personnel de régularité et de diversité des partenaires — jamais
    // un classement. Voir PracticeStatsService.
    @GetMapping("/me/practice-stats")
    public PracticeStatsDto getMyPracticeStats(@AuthenticationPrincipal UserPrincipal principal) {
        return practiceStatsService.getStats(principal.getId());
    }

    /**
     * Statistiques de pratique : les siennes seulement.
     *
     * <p><b>Pour toute autre personne, 404</b> (P-BL-17, décision du 13/09) : les
     * séances, semaines et partenaires d'une personne ne regardent qu'elle. L'app
     * masque déjà la carte quand la route échoue. Ses propres statistiques se
     * lisent aussi sur {@code /me/practice-stats}.
     *
     * <p><b>Cette route n'avait aucun contrôle</b> : ni appelant identifié, ni
     * vérification de blocage. N'importe qui pouvait lire les compteurs bruts de
     * n'importe qui. C'était sans grande conséquence tant qu'ils décrivaient une
     * pratique ; ça en aurait avec le signal de fiabilité, dont le dénominateur
     * ne doit jamais rejoindre ce DTO — deux nombres et une division suffiraient
     * à reconstituer le pourcentage que le produit refuse d'afficher.
     *
     * <p>{@code joinedSlotsCount} n'y figure donc pas, et ne doit pas y être
     * ajouté « par symétrie ». Le blocage est appliqué comme sur le profil : un
     * compte masqué est introuvable, pas interdit.
     */
    @GetMapping("/{userId}/practice-stats")
    public PracticeStatsDto getPracticeStats(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (!principal.getId().equals(userId)) {
            throw new UserNotFoundException("Utilisateur introuvable.");
        }
        return practiceStatsService.getStats(userId);
    }

    @PutMapping("/me")
    public UserPrivateDto updateProfile(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateProfileRequest request) {
        return userService.updateProfile(principal.getId(), request);
    }

    @PutMapping("/me/location")
    public ResponseEntity<Void> updateLocation(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdateLocationRequest request) {
        userService.updateLocation(principal.getId(), request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/me/avatar")
    public UserPrivateDto uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) throws IOException {

        // Validate and process image using MediaService components
        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);

        // Store the avatar
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(),
            processedImage
        );
        String filename = storageService.store(
            processedFile,
            principal.getId(),
            org.program.pair.domain.media.MediaType.USER_AVATAR
        );

        // Update user profile with new avatar URL
        String avatarUrl = "/api/media/files/" + filename;
        userService.updateAvatar(principal.getId(), avatarUrl);

        // Return updated profile
        return userService.getMyProfile(principal.getId());
    }

    /**
     * Retire son avatar, et efface le fichier <b>si l'appelant l'a déposé</b>.
     *
     * <p>La suppression ne passe plus par {@code storageService.delete}, qui ne
     * vérifie rien : c'est {@link org.program.pair.domain.media.MediaFileService}
     * qui consulte la ligne {@code media_files} (V109) avant de toucher au
     * disque. En pratique le déposant est toujours le propriétaire du profil —
     * l'avatar n'a qu'un chemin de dépôt, {@code POST /api/users/me/avatar}.
     * Deux cas font pourtant échouer la comparaison, et doivent échouer
     * <b>silencieusement</b> : un avatar déposé avant V109 (aucune ligne, donc
     * personne ne peut l'effacer) et une URL externe de seed. Dans les deux cas
     * le profil oublie bien son avatar ; seuls les octets restent.
     */
    @DeleteMapping("/me/avatar")
    public UserPrivateDto deleteAvatar(
            @AuthenticationPrincipal UserPrincipal principal) {
        String previousAvatarUrl = userService.removeAvatar(principal.getId());
        mediaFileService.supprimerUrlSiAuteur(previousAvatarUrl, principal.getId());
        return userService.getMyProfile(principal.getId());
    }

    /**
     * Profil public.
     *
     * <p>Un profil bloqué est <b>introuvable</b>, dans les deux sens et avec le
     * message d'un compte qui n'existe pas. Un 403 dirait « il existe, mais » —
     * exactement ce qu'un blocage ne doit pas laisser déduire.
     *
     * <p>La garde est ici et non dans {@code getPublicProfile} : cette méthode
     * est aussi la fabrique du DTO public pour cinq appelants internes — cartes-
     * souvenirs, participants d'un créneau, hôte d'un créneau, présence. Y faire
     * lever une exception transformerait un masquage en erreur serveur chez des
     * appelants qui n'ont rien demandé.
     */
    @GetMapping("/me/preview")
    @io.swagger.v3.oas.annotations.Operation(
        summary = "Mon profil tel qu'un inconnu le reçoit.",
        description = "Exactement le DTO que rend GET /api/users/{id} à quelqu'un qui "
            + "n'a aucun lien avec moi — même code, pas un code équivalent. Un aperçu "
            + "qui divergerait du profil réel serait pire que pas d'aperçu : il donnerait "
            + "confiance dans une réponse fausse.\n\n"
            + "Déclaré avant /{id} : sans cela « me » serait interprété comme un "
            + "identifiant et la route ne serait jamais atteinte.")
    public UserPublicDto getMyProfilePreview(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.getMyProfilePreview(principal.getId());
    }

    @GetMapping("/{id}")
    public UserPublicDto getPublicProfile(
            @PathVariable UUID id,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (blockFilterService.blocked(principal.getId(), id)) {
            throw new UserNotFoundException("Utilisateur introuvable.");
        }
        return userService.getPublicProfile(id, principal.getId());
    }

    /**
     * L'autre porte de la suppression de compte, et la plus ancienne.
     *
     * <p>Elle a toujours désactivé le compte ; c'est
     * {@code DELETE /api/gdpr/delete-account} — celle que l'application appelle
     * réellement — qui ne faisait rien. Les deux mènent maintenant au même
     * endroit, <b>trace comprise</b> : la ligne {@code GDPR_DELETE_REQUEST} est
     * écrite ici aussi, faute de quoi le registre RGPD dépendrait de la route
     * empruntée et l'on ne pourrait pas dater une demande arrivée par celle-ci.
     * La date vit dans cette ligne tant que {@code users.deactivated_at}
     * n'existe pas.
     *
     * <p>Le geste est le même pour qui appelle : {@code 204}, sans corps, y
     * compris sur un compte déjà inactif (voir
     * {@link UserService#deactivateAccount}).
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateAccount(@AuthenticationPrincipal UserPrincipal principal) {
        userService.deactivateAccount(principal.getId());
        auditLogService.log(
            principal.getId(), AuditActionType.GDPR_DELETE_REQUEST, "USER", principal.getId());
    }

    @PostMapping("/me/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), principal.getSessionId(), request);
        return ResponseEntity.ok().build();
    }

    /**
     * Demander le passage à une nouvelle adresse (V105).
     *
     * <p>Le pendant de {@code change-password}, qui manquait — et dont l'absence
     * rendait définitive une faute de frappe à l'inscription, sur un compte qui
     * ne pouvait alors plus rien recevoir ni jamais être vérifié. C'est la suite
     * qui manquait à l'état {@code BOUNCED} de {@code verificationEmailDelivery} :
     * sans elle, ce champ nomme un problème et laisse devant une porte fermée.
     *
     * <p><b>{@code 200} ne veut pas dire que l'adresse a changé</b>, seulement
     * qu'un lien est parti vers elle. Le compte ne bascule qu'au clic ; jusque-là
     * {@code GET /users/me} rend toujours l'ancienne adresse, et c'est
     * volontaire — elle reste l'identifiant de connexion tant que la nouvelle
     * n'a pas prouvé qu'elle reçoit.
     *
     * <p>Le limiteur est celui du renvoi de vérification : la route déclenche un
     * e-mail vers une adresse choisie par l'appelant, ce qui est exactement ce
     * que ce budget borne.
     */
    @PostMapping("/me/change-email")
    public ResponseEntity<Void> changeEmail(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangeEmailRequest request,
            HttpServletRequest httpRequest) {
        rateLimiter.checkResendVerification(httpRequest.getRemoteAddr(), request.email());
        emailChangeService.demanderChangement(principal.getId(), request.email());
        return ResponseEntity.ok().build();
    }

    /**
     * Les programmes publics d'un profil.
     *
     * <p>Le refus est celui de la fiche de profil juste au-dessus, et pour la
     * même raison : ces deux routes servent le même écran, et laisser la seconde
     * ouverte quand la première refuse rendrait le blocage sans effet — la liste
     * des programmes nomme son auteur, ses lieux et ses horaires. Un profil
     * bloqué qui garde ses programmes visibles est un profil qui n'est pas
     * bloqué.
     *
     * <p>{@code 404} et non {@code 403}, dans les deux sens : un code nommé
     * apprendrait le blocage à celui qui l'a subi.
     */
    @GetMapping("/{userId}/programs")
    public List<ProgramDto> getPublicProgramsByUser(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (blockFilterService.blocked(principal.getId(), userId)) {
            throw new UserNotFoundException("Utilisateur introuvable.");
        }
        return programService.getPublicProgramsByUser(userId);
    }

    @GetMapping("/me/privacy")
    public PrivacySettingsDto getPrivacySettings(@AuthenticationPrincipal UserPrincipal principal) {
        return userService.getPrivacySettings(principal.getId());
    }

    @PutMapping("/me/privacy")
    public PrivacySettingsDto updatePrivacySettings(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody UpdatePrivacySettingsRequest request) {
        return userService.updatePrivacySettings(principal.getId(), request);
    }

}
