package org.program.pair.domain.user;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.auth.EmailVerificationService;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.EmailAlreadyExistsException;
import org.program.pair.shared.exception.UserNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Changer l'adresse d'un compte.
 *
 * <p><b>Pourquoi cette route existe (V105).</b> Le lot du 07/09 rend visible
 * qu'une adresse a rebondi. Sans moyen d'en changer, cette visibilité serait un
 * progrès pour nous et une impasse pour la personne : l'adresse n'était
 * modifiable nulle part — ni dans {@code UpdateProfileRequest}, ni par une route
 * dédiée alors que {@code change-password} en avait une — et une faute de frappe
 * à l'inscription était donc définitive, sur un compte qui ne pouvait plus rien
 * recevoir ni jamais être vérifié. C'est le premier cas de rebond dur, avant
 * même le fournisseur qui refuse.
 *
 * <p><b>Un service à part, et non une méthode de {@code UserService}.</b> Ce
 * dernier est monté dans ses tests unitaires par {@code @InjectMocks} avec la
 * liste exacte de ses dépendances ; lui en ajouter une le ferait démarrer avec
 * un champ nul dans une classe de test étrangère au sujet. Son propre cartouche
 * le dit, et le respecter coûte moins qu'un test cassé pour rien.
 *
 * <p><b>Rien ne bascule à la demande.</b> L'adresse est posée en attente, et le
 * lien part vers elle. C'est le clic qui échange les deux — voir
 * {@code EmailVerificationService.appliquerChangement}.
 */
@Service
@RequiredArgsConstructor
public class EmailChangeService {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;

    /**
     * Demande le passage à {@code nouvelleAdresse}.
     *
     * <p><b>Le conflit est annoncé (409), et c'est un choix.</b> Répondre 200 à
     * une adresse déjà prise protégerait de l'énumération, comme le fait
     * {@code resend-verification} — mais ici l'appelant est authentifié, et le
     * silence lui ferait attendre indéfiniment un e-mail qui ne partira jamais.
     * C'est le même arbitrage que {@code register}, qui rend déjà 409 sur ce cas
     * exact : le taire ici n'ajouterait aucune protection, et coûterait la seule
     * chose que cette route doit donner — savoir si ça a marché.
     *
     * <p>Demander sa propre adresse est refusé plutôt qu'ignoré : sans cela, un
     * appui distrait sur « confirmer » enverrait un e-mail vers l'adresse dont on
     * cherche précisément à sortir, et ferait croire à une panne de plus.
     */
    @Transactional
    public void demanderChangement(UUID userId, String nouvelleAdresse) {
        User user = userRepository.findById(userId)
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
            .orElseThrow(() -> new UserNotFoundException("Utilisateur introuvable."));

        String adresse = nouvelleAdresse == null ? "" : nouvelleAdresse.strip().toLowerCase();
        if (adresse.isBlank()) {
            throw new ValidationException("L'adresse e-mail est obligatoire.");
        }
        if (adresse.equals(user.getEmail())) {
            throw new ValidationException("Cette adresse est déjà celle de votre compte.");
        }
        if (userRepository.existsByEmail(adresse)) {
            throw new EmailAlreadyExistsException("Cet email est déjà utilisé.");
        }
        // Une adresse déjà convoitée par un AUTRE compte est indisponible au même
        // titre qu'une adresse prise : c'est le premier des deux clics qui
        // l'emporterait, et laisser partir un second e-mail promettrait quelque
        // chose qu'on ne tiendra peut-être pas. L'index unique de V105 le refuse
        // de toute façon — mais par une violation de contrainte, donc un 500 là
        // où il s'agit d'un refus ordinaire.
        //
        // La sienne, en revanche, se redemande : c'est le geste de quelqu'un qui
        // n'a pas reçu le premier lien, et le lui refuser fermerait la seule
        // porte qui lui reste.
        if (!adresse.equals(user.getPendingEmail())
                && userRepository.existsByPendingEmail(adresse)) {
            throw new EmailAlreadyExistsException("Cet email est déjà utilisé.");
        }

        emailVerificationService.demanderChangementEmail(user, adresse);
    }
}
