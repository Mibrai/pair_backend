package org.program.pair.domain.publicslot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotAddressVisibility;
import org.program.pair.domain.program.SlotAudience;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.program.SlotTiming;
import org.program.pair.domain.publicslot.dto.PublicShareLinkDto;
import org.program.pair.domain.user.GivenName;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * La page qu'on colle dans une conversation.
 *
 * <p>C'est le seul canal d'acquisition gratuit du produit, et il ne vaut que par
 * l'aperçu que les messageries fabriquent à partir de ses métadonnées. D'où une
 * page rendue par le serveur : un robot d'aperçu n'exécute pas de JavaScript et
 * ne verrait rien d'une page composée côté client.
 *
 * <p><b>Une seule définition de « visible publiquement ».</b> Les conditions de
 * refus sont réunies dans {@link #publiclyVisible}, sur le modèle de
 * {@code SlotAudience}, {@code SlotTiming} et {@code SlotAddressVisibility}. Une
 * sixième définition dispersée de « qui a le droit de voir » serait le vrai
 * risque de ce lot : elle ne produirait aucune erreur, seulement une page
 * accessible qui n'aurait pas dû l'être.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PublicSlotService {

    /**
     * Au-delà, la page disparaît. Un créneau passé reste lisible quelques heures
     * — le lien circule encore, et « c'était hier » vaut mieux qu'une page
     * morte — mais pas au-delà d'une journée, sinon l'aperçu partagé annonce
     * indéfiniment un rendez-vous qui n'aura plus lieu.
     */
    private static final Duration VISIBLE_AFTER_END = Duration.ofHours(24);

    private final ScheduleRepository scheduleRepository;
    private final SlotAudience slotAudience;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    /**
     * L'adresse publique du créneau, créée si elle n'existe pas encore.
     *
     * <p>Réservée aux personnes du créneau : l'adresse elle-même est publique,
     * mais la <i>fabriquer</i> est un geste d'organisateur ou de participant, et
     * l'ouvrir à tous laisserait n'importe qui rendre partageable un créneau qui
     * ne l'avait jamais été.
     */
    @Transactional
    public PublicShareLinkDto shareLink(UUID userId, UUID scheduleId) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!slotAudience.participantIds(slot).contains(userId)) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        if (slot.getPublicShareToken() == null) {
            slot.setPublicShareToken(
                ShareToken.nextUnique(scheduleRepository::existsByPublicShareToken));
            slot = scheduleRepository.save(slot);
        }

        String token = slot.getPublicShareToken();
        return new PublicShareLinkDto(
            token,
            publicBaseUrl + "/s/" + token,
            publicBaseUrl + "/public/slots/" + token + "/page",
            Boolean.TRUE.equals(slot.getIsPubliclyShareable()));
    }

    /**
     * Ouvre ou ferme le partage public d'un créneau.
     *
     * <p><b>Réservé à l'organisateur</b>, là où {@code shareLink} s'ouvre à tous
     * les participants. La distinction n'est pas un détail : fabriquer une
     * adresse publique pour un créneau qu'on a rejoint est un geste de partage
     * ordinaire, tandis que retirer cette adresse retire à tous les autres un
     * lien qu'ils ont peut-être déjà collé quelque part. Ce geste-là appartient à
     * qui organise.
     *
     * <p>Le jeton n'est <b>jamais</b> effacé ni régénéré. Le refermer suffit à ce
     * que le lien ne mène plus nulle part, et le rouvrir doit rendre valides les
     * liens déjà partagés — un jeton neuf transformerait une pause en rupture
     * définitive, sans que personne l'ait demandé.
     */
    @Transactional
    public PublicShareLinkDto setShareable(UUID userId, UUID scheduleId, boolean shareable) {
        Schedule slot = scheduleRepository.findById(scheduleId)
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        if (!userId.equals(hostIdOf(slot))) {
            throw new ResourceNotFoundException("Créneau introuvable.");
        }

        slot.setIsPubliclyShareable(shareable);
        slot = scheduleRepository.save(slot);

        String token = slot.getPublicShareToken();
        return new PublicShareLinkDto(
            token,
            token == null ? null : publicBaseUrl + "/s/" + token,
            token == null ? null : publicBaseUrl + "/public/slots/" + token + "/page",
            shareable);
    }

    /** L'organisateur du créneau, ou null si la chaîne est incomplète. */
    private UUID hostIdOf(Schedule slot) {
        Program program = slot.getProgram();
        if (program == null || program.getUserActivity() == null
            || program.getUserActivity().getUser() == null) {
            return null;
        }
        return program.getUserActivity().getUser().getId();
    }

    /**
     * Le contenu de la page, ou rien.
     *
     * <p><b>Ne compte pas l'ouverture</b> — voir {@link #countView}. Elle
     * l'incrémentait, ce qui produisait deux défauts : le JSON destiné aux
     * clients programmatiques comptait comme une ouverture de page, et un robot
     * d'aperçu comptait autant qu'un lecteur.
     */
    @Transactional(readOnly = true)
    public PublicSlotView view(String token, Instant now) {
        Schedule slot = scheduleRepository.findByPublicShareToken(token)
            .filter(s -> publiclyVisible(s, now))
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));

        return toView(slot);
    }

    /**
     * Compte une ouverture de la page partagée, sauf robot d'aperçu.
     *
     * <p>Hors du rendu, et pour deux raisons distinctes. La première est
     * évidente : la page ne doit pas attendre une écriture pour partir, un robot
     * d'aperçu abandonnant au bout de quelques secondes. La seconde l'est moins —
     * l'incrément passe par un {@code UPDATE} atomique et non par une lecture
     * suivie d'une écriture. Deux ouvertures simultanées du même lien, ce que le
     * partage dans un groupe produit précisément, n'en comptaient qu'une : les
     * deux transactions lisaient la même valeur avant que l'une n'écrive.
     *
     * <p>Une exception ici ne doit rien casser : le compteur est indicatif, la
     * page ne l'est pas. {@code @Async} l'isole déjà du rendu, mais l'appelant
     * n'en saurait rien de toute façon.
     */
    @Async
    @Transactional
    public void countView(String token, String userAgent) {
        if (PreviewBots.isPreviewBot(userAgent)) {
            return;
        }
        try {
            scheduleRepository.incrementPublicViewCount(token);
        } catch (RuntimeException e) {
            log.warn("Comptage d'ouverture perdu pour le jeton {} : {}", token, e.getMessage());
        }
    }

    /** Le même créneau, sans compter l'ouverture — pour l'image et les tests. */
    @Transactional(readOnly = true)
    public Schedule resolve(String token, Instant now) {
        return scheduleRepository.findByPublicShareToken(token)
            .filter(s -> publiclyVisible(s, now))
            .orElseThrow(() -> new ResourceNotFoundException("Créneau introuvable."));
    }

    /**
     * Les conditions de refus, réunies en un seul endroit.
     *
     * <p>« Programme non public » est ambigu dans le modèle : {@code Program}
     * porte à la fois {@code isPublic} et une énumération {@code privacy}, dont
     * seule la première est appliquée quelque part. C'est donc elle qui décide,
     * et il manquait à la liste {@code status = ACTIVE} — sans quoi la page
     * publique serait plus permissive que le fil, où un programme en brouillon
     * n'apparaît jamais.
     *
     * <p><b>Le créneau annulé a été ajouté après coup, et c'est instructif.</b>
     * Cette méthode a été écrite au lot B1, avant que le lot C3 n'introduise
     * l'annulation d'un créneau ({@code SlotStatus.CANCELLED}, {@code
     * cancelledAt}) — et personne n'est revenu ici. Le lien partagé continuait
     * donc d'inviter du monde à une séance annulée, sans qu'aucune erreur ne se
     * produise nulle part : c'est exactement la panne que le paragraphe
     * ci-dessus annonçait, une page accessible qui n'aurait pas dû l'être.
     * Chaque nouvel état d'un créneau doit repasser par cette liste.
     */
    private boolean publiclyVisible(Schedule slot, Instant now) {
        if (!Boolean.TRUE.equals(slot.getIsPubliclyShareable())) {
            return false;
        }

        if (slot.getStatus() == SlotStatus.CANCELLED) {
            return false;
        }

        Program program = slot.getProgram();
        if (program == null
            || !Boolean.TRUE.equals(program.getIsPublic())
            || program.getStatus() != ProgramStatus.ACTIVE) {
            return false;
        }

        UserActivity userActivity = program.getUserActivity();
        if (userActivity == null || !Boolean.TRUE.equals(userActivity.getVisibleOnMap())) {
            return false;
        }

        User organizer = userActivity.getUser();
        if (organizer == null || !Boolean.TRUE.equals(organizer.getIsActive())) {
            return false;
        }

        // Passé de plus de 24 h. On compare la fin et non le début : une séance
        // de deux heures commencée il y a vingt-cinq heures est finie depuis
        // vingt-trois, et son lien a encore un sens.
        return SlotTiming.endOf(slot).plus(VISIBLE_AFTER_END).isAfter(now);
    }

    private PublicSlotView toView(Schedule slot) {
        Program program = slot.getProgram();
        UserActivity userActivity = program.getUserActivity();
        Activity activity = userActivity.getActivity();
        User organizer = userActivity.getUser();

        return new PublicSlotView(
            program.getTitle(),
            activity.getName(),
            activity.getCategory() != null ? activity.getCategory().getName() : null,
            activity.getCategory() != null ? activity.getCategory().getColorRamp() : null,
            slot.getPrimaryLanguage(),
            slot.getStartsAt(),
            SlotTiming.endOf(slot),
            slot.getPlaceName(),
            slot.getCity(),
            // Jamais resolve(slot, requesterId, ...) : il n'y a pas de demandeur
            // identifié sur une page ouverte, et cette variante-là ne peut
            // qu'élargir ce qui est visible.
            SlotAddressVisibility.broadcastableAddress(slot),
            slot.getParticipantCount(),
            slot.getMaxParticipants(),
            slot.getWelcomeNote(),
            GivenName.from(organizer.getDisplayName()),
            // « Badge vérifié » au sens du produit : l'adresse a été confirmée
            // au minimum. UNVERIFIED est le seul état qui ne l'accorde pas.
            organizer.getVerificationStatus() != null
                && organizer.getVerificationStatus() != VerificationStatus.UNVERIFIED,
            imageOf(slot) != null);
    }

    /** L'image à montrer : celle du programme, à défaut celle de l'activité. */
    public String imageOf(Schedule slot) {
        Program program = slot.getProgram();
        if (program.getImageUrl() != null && !program.getImageUrl().isBlank()) {
            return program.getImageUrl();
        }
        Activity activity = program.getUserActivity().getActivity();
        return activity.getImageUrl() != null && !activity.getImageUrl().isBlank()
            ? activity.getImageUrl()
            : null;
    }
}
