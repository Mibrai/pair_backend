package org.program.pair.domain.guardian;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.guardian.dto.CreateGuardianRequest;
import org.program.pair.domain.guardian.dto.GuardianDto;
import org.program.pair.domain.notification.NotificationType;
import org.program.pair.domain.user.User;
import org.program.pair.repository.GuardianRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.email.EmailService;
import org.program.pair.shared.exception.BusinessException;
import org.program.pair.shared.exception.ConflictException;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.exception.ValidationException;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Les contacts d'urgence : les désigner, les inviter à accepter, recueillir leur
 * réponse.
 *
 * <p>C'est la priorité 1 du lot traçabilité, et la seule qui ne dépende de rien :
 * sans contact accepté, aucune veille n'est armable. Trois règles gouvernent le
 * domaine, et chacune se justifie par ce qu'elle empêche.
 *
 * <p><b>Un seul message, jamais de relance.</b> {@link #invite} refuse la seconde
 * invitation. Un contact qui reçoit des rappels répétés depuis une marque à
 * laquelle il n'a rien demandé est un signalement pour spam en préparation.
 *
 * <p><b>Un refus est définitif et global au numéro.</b> {@link #refuseConsent}
 * inscrit le numéro dans la liste de blocage, sous le poivre, et {@link #create}
 * refuse tout numéro qui y figure — quel que soit le compte qui redésigne. Sans
 * cette portée globale, un second compte suffirait à recontacter un numéro qui a
 * dit non, et le module deviendrait un canal de harcèlement avec une étape de
 * contournement triviale.
 *
 * <p><b>Le canal SMS n'est pas encore là.</b> Un contact hors meetDo qui n'a
 * laissé qu'un téléphone ne peut pas être invité avant la priorité 4 (Twilio) :
 * {@link #invite} le dit franchement plutôt que de faire semblant d'envoyer. Un
 * contact avec un e-mail, ou un membre meetDo, s'invite dès maintenant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class GuardianService {

    private final GuardianRepository guardianRepository;
    private final RefusedContactService refusedContacts;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final org.program.pair.domain.notification.NotificationService notificationService;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    // ------------------------------------------------------------------ lecture

    @Transactional(readOnly = true)
    public List<GuardianDto> list(UUID ownerId) {
        return guardianRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream()
            .map(this::toDto)
            .toList();
    }

    // ---------------------------------------------------------------- désigner

    public GuardianDto create(UUID ownerId, CreateGuardianRequest req) {
        Guardian.GuardianBuilder builder = Guardian.builder()
            .ownerId(ownerId)
            .consentState(ConsentState.PENDING)
            .consentToken(ShareToken.nextUnique(guardianRepository::existsByConsentToken));

        boolean aUnCanalExterne = notBlank(req.phone()) || notBlank(req.email());

        if (req.memberId() != null) {
            // Un membre, et rien d'autre : mêler des coordonnées en clair à un
            // contact qui a déjà un compte n'aurait pas de sens, et la base le
            // refuse (contrainte membre XOR externe).
            if (aUnCanalExterne) {
                throw new BusinessException(ErrorCode.GUARDIAN_INVALID_CONTACT,
                    "Un contact est soit un membre meetDo, soit un contact externe — pas les deux.");
            }
            if (req.memberId().equals(ownerId)) {
                throw new BusinessException(ErrorCode.GUARDIAN_SELF,
                    "Vous ne pouvez pas vous désigner vous-même comme contact d'urgence.");
            }
            if (!userRepository.existsById(req.memberId())) {
                throw new ResourceNotFoundException("Ce membre est introuvable.");
            }
            if (guardianRepository.existsByOwnerIdAndMemberId(ownerId, req.memberId())) {
                throw new ConflictException(ErrorCode.GUARDIAN_ALREADY_DESIGNATED,
                    "Ce membre est déjà l'un de vos contacts d'urgence.");
            }
            builder.memberId(req.memberId());
        } else {
            // Un contact externe : au moins un canal pour le joindre.
            if (!aUnCanalExterne) {
                throw new BusinessException(ErrorCode.GUARDIAN_INVALID_CONTACT,
                    "Un contact externe doit avoir au moins un téléphone ou un e-mail.");
            }
            builder.name(blankToNull(req.name()));
            builder.email(blankToNull(req.email()));
            builder.phone(normaliserEtVerifierLeNumero(req.phone()));
        }

        Guardian saved = guardianRepository.save(builder.build());
        return toDto(saved);
    }

    /**
     * Normalise le numéro et refuse celui qui a déjà dit non. Rend {@code null}
     * quand aucun numéro n'a été fourni — le contact est alors joint par e-mail.
     */
    private String normaliserEtVerifierLeNumero(String phoneBrut) {
        if (!notBlank(phoneBrut)) {
            return null;
        }
        String e164 = PhoneNumber.toE164(phoneBrut).orElseThrow(() ->
            new ValidationException("Ce numéro de téléphone n'est pas reconnu comme un mobile valide."));
        if (refusedContacts.estRefuse(e164)) {
            // Le message ne dit pas « cette personne a refusé » : le savoir
            // reviendrait à confirmer, à qui essaie des numéros, l'existence d'un
            // refus — donc d'une personne derrière. Il dit seulement qu'on ne peut
            // pas désigner ce numéro.
            throw new BusinessException(ErrorCode.GUARDIAN_CONTACT_REFUSED,
                "Ce numéro ne peut pas être désigné comme contact.");
        }
        return e164;
    }

    // --------------------------------------------------------------- supprimer

    public void delete(UUID ownerId, UUID guardianId) {
        Guardian guardian = guardianRepository.findByIdAndOwnerId(guardianId, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Contact introuvable."));
        guardianRepository.delete(guardian);
    }

    // ----------------------------------------------------------------- inviter

    /**
     * Envoie le message ①, une seule fois. Refuse la relance et l'invitation d'un
     * contact qui a déjà répondu.
     */
    public GuardianDto invite(UUID ownerId, UUID guardianId) {
        Guardian guardian = guardianRepository.findByIdAndOwnerId(guardianId, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Contact introuvable."));

        if (guardian.getRespondedAt() != null) {
            throw new BusinessException(ErrorCode.GUARDIAN_ALREADY_RESPONDED,
                "Ce contact a déjà répondu à votre demande.");
        }
        if (guardian.getInvitedAt() != null) {
            throw new ConflictException(ErrorCode.GUARDIAN_ALREADY_INVITED,
                "Une demande a déjà été envoyée à ce contact. Un seul message lui est adressé, sans relance.");
        }

        envoyerLeMessageDaccord(ownerId, guardian);

        guardian.setInvitedAt(Instant.now());
        return toDto(guardian);
    }

    private void envoyerLeMessageDaccord(UUID ownerId, Guardian guardian) {
        String parrain = displayName(ownerId);

        if (guardian.isMember()) {
            // Canal in-app : le membre reçoit une notification qui ouvre l'écran
            // accepter / refuser. Pas d'e-mail ni de SMS — il a un compte.
            notificationService.notify(guardian.getMemberId(), ownerId,
                NotificationType.GUARDIAN_CONSENT_REQUEST,
                Map.of("consentToken", guardian.getConsentToken(), "ownerName", parrain));
            return;
        }

        if (notBlank(guardian.getEmail())) {
            emailService.sendGuardianConsentEmail(guardian.getEmail(), parrain, pageConsentement(guardian));
            return;
        }

        // Contact externe sans e-mail : il faudrait un SMS, qui arrive avec la
        // priorité 4. On le dit plutôt que de marquer « invité » un contact que
        // rien n'a joint.
        throw new BusinessException(ErrorCode.GUARDIAN_SMS_NOT_AVAILABLE,
            "Ce contact n'a qu'un téléphone. L'envoi par SMS n'est pas encore disponible ; "
                + "ajoutez-lui un e-mail pour l'inviter dès maintenant.");
    }

    // ------------------------------------------------------- flux public (jeton)

    /** Ce que la page publique affiche : qui a désigné, et où en est la réponse. */
    @Transactional(readOnly = true)
    public ConsentView consentView(String token) {
        Guardian guardian = parJeton(token);
        return new ConsentView(displayName(guardian.getOwnerId()), guardian.getConsentState());
    }

    /** Le contact accepte d'être prévenu. Idempotent si déjà accepté. */
    public void acceptConsent(String token) {
        Guardian guardian = parJeton(token);
        if (guardian.getConsentState() == ConsentState.ACCEPTED) {
            return;
        }
        if (guardian.getConsentState() == ConsentState.REFUSED) {
            throw new BusinessException(ErrorCode.GUARDIAN_ALREADY_RESPONDED,
                "Vous aviez refusé cette demande ; elle ne peut plus être acceptée.");
        }
        guardian.setConsentState(ConsentState.ACCEPTED);
        guardian.setRespondedAt(Instant.now());
    }

    /**
     * Le contact refuse. Définitif, et global au numéro s'il en a un : le numéro
     * entre dans la liste de blocage, sous le poivre, et ne pourra plus être
     * désigné par aucun compte.
     */
    public void refuseConsent(String token) {
        Guardian guardian = parJeton(token);
        if (guardian.getConsentState() != ConsentState.REFUSED) {
            guardian.setConsentState(ConsentState.REFUSED);
            guardian.setRespondedAt(Instant.now());
            // Le rôle ne survit pas au refus. Un principal qui a dit non est un
            // réglage qui pointe dans le vide : la feuille d'armement le proposerait
            // en premier et l'armement le refuserait. Mieux vaut n'avoir plus de
            // principal — un choix absent se voit, un choix mort ne se voit pas.
            guardian.setRole(null);
        }
        if (notBlank(guardian.getPhone())) {
            refusedContacts.refuser(guardian.getPhone());
        }
    }

    /**
     * Pose un rôle sur un contact : principal, secours, ou aucun.
     *
     * <p><b>Poser un rôle le retire à celui qui le portait.</b> C'est la forme la
     * plus sûre pour un réglage à cardinalité un : sans elle, l'appelant devrait
     * faire deux appels — libérer puis poser — et la fenêtre entre les deux laisse
     * un compte sans principal si le second échoue. Ici l'échange est atomique.
     *
     * <p><b>Un contact ne peut pas être les deux à la fois</b> : la colonne n'en
     * porte qu'un, donc poser {@code BACKUP} sur le principal actuel le fait cesser
     * d'être principal. C'est ce que la personne demande en le déplaçant, et le
     * refuser l'obligerait à un aller-retour pour le même résultat.
     *
     * <p><b>Un contact qui a refusé ne prend pas de rôle.</b> Le poser créerait
     * exactement le réglage mort que {@link #refuseConsent} efface. On accepte en
     * revanche un contact {@code PENDING} : on désigne d'abord, on invite ensuite, et
     * l'ordre inverse obligerait à revenir sur l'écran après la réponse du contact.
     */
    public GuardianDto setRole(UUID ownerId, UUID guardianId, GuardianRole demande) {
        Guardian guardian = guardianRepository.findByIdAndOwnerId(guardianId, ownerId)
            .orElseThrow(() -> new ResourceNotFoundException("Contact introuvable."));

        GuardianRole cible = demande == null ? GuardianRole.NONE : demande;

        if (cible != GuardianRole.NONE && guardian.getConsentState() == ConsentState.REFUSED) {
            throw new BusinessException(ErrorCode.GUARDIAN_CONTACT_REFUSED,
                "Ce contact a refusé d'être contact d'urgence : il ne peut pas être désigné.");
        }

        if (cible != GuardianRole.NONE) {
            // Le tenant actuel est libéré d'abord, dans la même transaction, et l'on
            // vide au passage pour que l'index partiel unique ne voie jamais deux
            // porteurs — l'ordre des écritures compte, la contrainte est immédiate.
            guardianRepository.findByOwnerIdAndRole(ownerId, cible)
                .filter(autre -> !autre.getId().equals(guardianId))
                .ifPresent(autre -> autre.setRole(null));
            guardianRepository.flush();
        }

        guardian.setRole(cible.toStored());
        guardianRepository.flush();
        return toDto(guardian);
    }

    private Guardian parJeton(String token) {
        return guardianRepository.findByConsentToken(token)
            .orElseThrow(() -> new ResourceNotFoundException("Demande introuvable ou expirée."));
    }

    // ------------------------------------------------------------------ outils

    private GuardianDto toDto(Guardian guardian) {
        String display = guardian.isMember() ? displayName(guardian.getMemberId()) : null;
        return GuardianDto.from(guardian, display);
    }

    private String displayName(UUID userId) {
        return userRepository.findById(userId)
            .map(User::getDisplayName)
            .orElse("Un membre meetDo");
    }

    private String pageConsentement(Guardian guardian) {
        return publicBaseUrl + "/public/guardian-consent/" + guardian.getConsentToken();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s.strip() : null;
    }

    /** Ce que la page publique montre au contact. */
    public record ConsentView(String ownerName, ConsentState state) {}
}
