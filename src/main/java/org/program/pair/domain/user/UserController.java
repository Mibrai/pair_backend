package org.program.pair.domain.user;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.program.pair.domain.attendance.PracticeStatsService;
import org.program.pair.domain.block.BlockFilterService;
import org.program.pair.domain.attendance.dto.PracticeStatsDto;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.domain.media.ImageProcessor;
import org.program.pair.domain.media.MediaValidator;
import org.program.pair.domain.media.StorageService;
import org.program.pair.domain.program.ProgramService;
import org.program.pair.domain.program.dto.ProgramDto;
import org.program.pair.domain.user.dto.*;
import org.program.pair.shared.exception.UserNotFoundException;
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
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;
    private final ProgramService programService;
    private final PracticeStatsService practiceStatsService;
    private final BlockFilterService blockFilterService;

    @GetMapping
    public Page<UserPublicDto> searchUsers(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal principal) {

        // If no query provided, return empty result
        if (query == null || query.isBlank()) {
            return Page.empty();
        }

        return userService.searchUsers(
            query.trim(),
            latitude,
            longitude,
            page,
            size,
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
     * Statistiques de pratique d'une personne.
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
        if (blockFilterService.blocked(principal.getId(), userId)) {
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

    @DeleteMapping("/me/avatar")
    public UserPrivateDto deleteAvatar(
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        String previousAvatarUrl = userService.removeAvatar(principal.getId());
        String prefix = "/api/media/files/";
        if (previousAvatarUrl != null && previousAvatarUrl.startsWith(prefix)) {
            storageService.delete(previousAvatarUrl.substring(prefix.length()));
        }
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

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateAccount(@AuthenticationPrincipal UserPrincipal principal) {
        userService.deactivateAccount(principal.getId());
    }

    @PostMapping("/me/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), request);
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

    // Helper class for processed files
    private static class ProcessedMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final InputStream inputStream;

        ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return 0;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return inputStream.readAllBytes();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException();
        }
    }
}
