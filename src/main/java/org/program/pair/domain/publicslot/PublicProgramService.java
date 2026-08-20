package org.program.pair.domain.publicslot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.ProgramStatus;
import org.program.pair.domain.program.Schedule;
import org.program.pair.domain.program.SlotStatus;
import org.program.pair.domain.publicslot.dto.PublicShareLinkDto;
import org.program.pair.domain.user.GivenName;
import org.program.pair.domain.user.User;
import org.program.pair.domain.user.VerificationStatus;
import org.program.pair.repository.ProgramRepository;
import org.program.pair.repository.ScheduleRepository;
import org.program.pair.repository.UserProgramRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.program.pair.shared.security.ShareToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * La page publique d'un programme.
 *
 * <p>Décalquée de {@link PublicSlotService}, volontairement : le contrat des
 * créneaux fonctionne, et deux mécaniques de partage divergentes pour un même
 * produit finiraient par ne plus se ressembler. Mêmes jetons, mêmes DTO de lien,
 * même refus en {@code 404}, même page rendue par le serveur.
 *
 * <p><b>Ce qui diffère, et pourquoi.</b> Les conditions de visibilité ne sont pas
 * les mêmes : un créneau est une occurrence, donc « passé depuis plus de 24 h »
 * et « annulé » ont un sens pour lui. Un programme n'en est pas une — il vit tant
 * qu'il n'est pas archivé, même sans séance à venir. Ses conditions sont celles
 * que le fil applique déjà, pour que la page publique ne soit jamais plus
 * permissive que l'application.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PublicProgramService {

    /** Bornage de la liste de séances annoncée : au-delà, personne ne lit. */
    private static final int MAX_SESSIONS_COUNTED = 50;

    private final ProgramRepository programRepository;
    private final ScheduleRepository scheduleRepository;
    private final UserProgramRepository userProgramRepository;

    @Value("${pair.public.base-url:https://lien.meetdo.fun}")
    private String publicBaseUrl;

    /**
     * L'adresse publique du programme, créée si elle n'existe pas encore.
     *
     * <p>Réservée à l'organisateur. Pour un créneau, la fabrication est ouverte
     * aux participants — partager une séance qu'on a rejointe est un geste
     * ordinaire. Un programme n'a pas d'équivalent : il n'appartient qu'à son
     * auteur, et c'est son auteur qui décide s'il existe sur le web ouvert.
     */
    @Transactional
    public PublicShareLinkDto shareLink(UUID userId, UUID programId) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));

        if (!userId.equals(hostIdOf(program))) {
            throw new ResourceNotFoundException("Programme introuvable.");
        }

        if (program.getPublicShareToken() == null) {
            program.setPublicShareToken(
                ShareToken.nextUnique(programRepository::existsByPublicShareToken));
            program = programRepository.save(program);
        }

        return toLinkDto(program);
    }

    /**
     * Ouvre ou ferme le partage public.
     *
     * <p>Le jeton n'est <b>jamais</b> effacé ni régénéré : refermer suffit à ce
     * que le lien ne mène plus nulle part, et rouvrir doit rendre valides les
     * liens déjà collés ailleurs. Un jeton neuf transformerait une pause en
     * rupture définitive, sans que personne l'ait demandé.
     */
    @Transactional
    public PublicShareLinkDto setShareable(UUID userId, UUID programId, boolean shareable) {
        Program program = programRepository.findById(programId)
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));

        if (!userId.equals(hostIdOf(program))) {
            throw new ResourceNotFoundException("Programme introuvable.");
        }

        program.setIsPubliclyShareable(shareable);
        return toLinkDto(programRepository.save(program));
    }

    /** Le contenu de la page, ou rien. Ne compte pas l'ouverture — voir {@link #countView}. */
    @Transactional(readOnly = true)
    public PublicProgramView view(String token, Instant now) {
        return toView(resolve(token, now), now);
    }

    /** Le même programme, sans le DTO — pour l'image et les tests. */
    @Transactional(readOnly = true)
    public Program resolve(String token, Instant now) {
        return programRepository.findByPublicShareToken(token)
            .filter(this::publiclyVisible)
            .orElseThrow(() -> new ResourceNotFoundException("Programme introuvable."));
    }

    /**
     * Compte une ouverture, sauf robot d'aperçu.
     *
     * <p>Même règle que pour les créneaux, et pour la même raison : les robots
     * sont la raison d'être de cette page — leur visite fabrique la vignette
     * collée dans une conversation — et un seul lien partagé dans un groupe en
     * déclenche plusieurs avant que quiconque n'ait cliqué.
     */
    @Async
    @Transactional
    public void countView(String token, String userAgent) {
        if (PreviewBots.isPreviewBot(userAgent)) {
            return;
        }
        try {
            programRepository.incrementPublicViewCount(token);
        } catch (RuntimeException e) {
            log.warn("Comptage d'ouverture perdu pour le programme {} : {}", token, e.getMessage());
        }
    }

    /**
     * Les conditions de refus, réunies en un seul endroit.
     *
     * <p>Il n'y a <b>pas</b> de condition de temps ici, à la différence des
     * créneaux. Un programme sans séance à venir n'est pas périmé : son auteur
     * peut en reprogrammer une, et la page dit alors honnêtement « aucune séance
     * annoncée » plutôt que de disparaître. Ce qui le retire du web ouvert, c'est
     * l'archivage ou la dépublication — les mêmes gestes qui le retirent du fil.
     *
     * <p>Comme pour les créneaux, chaque nouvel état d'un programme doit repasser
     * par cette liste : la panne qu'on y risque ne produit aucune erreur, juste
     * une page accessible qui n'aurait pas dû l'être.
     */
    private boolean publiclyVisible(Program program) {
        if (!Boolean.TRUE.equals(program.getIsPubliclyShareable())) {
            return false;
        }
        if (!Boolean.TRUE.equals(program.getIsPublic())
            || program.getStatus() != ProgramStatus.ACTIVE
            || program.getArchivedAt() != null) {
            return false;
        }

        UserActivity userActivity = program.getUserActivity();
        if (userActivity == null || !Boolean.TRUE.equals(userActivity.getVisibleOnMap())) {
            return false;
        }

        User organizer = userActivity.getUser();
        return organizer != null && Boolean.TRUE.equals(organizer.getIsActive());
    }

    private PublicProgramView toView(Program program, Instant now) {
        UserActivity userActivity = program.getUserActivity();
        Activity activity = userActivity.getActivity();
        User organizer = userActivity.getUser();

        List<Schedule> upcoming = scheduleRepository.findByProgramId(program.getId()).stream()
            .filter(s -> s.getStatus() != SlotStatus.CANCELLED)
            .filter(s -> s.getStartsAt() != null && s.getStartsAt().isAfter(now))
            .sorted(Comparator.comparing(Schedule::getStartsAt))
            .limit(MAX_SESSIONS_COUNTED)
            .toList();

        Schedule next = upcoming.isEmpty() ? null : upcoming.get(0);

        return new PublicProgramView(
            program.getId(),
            program.getTitle(),
            program.getDescription(),
            activity != null ? activity.getName() : null,
            activity != null && activity.getCategory() != null
                ? activity.getCategory().getName() : null,
            activity != null && activity.getCategory() != null
                ? activity.getCategory().getColorRamp() : null,
            program.getLocationType() != null ? program.getLocationType().name() : null,
            next != null ? next.getCity() : null,
            // Le NOM du lieu, jamais l'adresse : un programme se partage sans
            // demandeur identifié, et la règle des créneaux vaut a fortiori ici.
            next != null ? next.getPlaceName() : null,
            next != null ? next.getStartsAt() : null,
            upcoming.size(),
            (int) userProgramRepository.countActiveParticipantsByProgramId(program.getId()),
            program.getMaxParticipants(),
            GivenName.from(organizer.getDisplayName()),
            organizer.getVerificationStatus() != null
                && organizer.getVerificationStatus() != VerificationStatus.UNVERIFIED,
            program.getImageUrl() != null && !program.getImageUrl().isBlank()
        );
    }

    private PublicShareLinkDto toLinkDto(Program program) {
        String token = program.getPublicShareToken();
        return new PublicShareLinkDto(
            token,
            token == null ? null : publicBaseUrl + "/p/" + token,
            // La PAGE, pas la route JSON — défaut relevé par l'équipe mobile le
            // 2026-08-20, et livré une heure chez eux avant qu'ils ne s'en
            // aperçoivent : un champ nommé « URL de page » qui rendait
            // /public/programs/{jeton}, c'est-à-dire les données brutes. Collé
            // dans un message, il ouvrait un navigateur sur du JSON.
            token == null ? null : publicBaseUrl + "/public/programs/" + token + "/page",
            Boolean.TRUE.equals(program.getIsPubliclyShareable()));
    }

    /** L'organisateur du programme, ou null si la chaîne est incomplète. */
    private UUID hostIdOf(Program program) {
        UserActivity userActivity = program.getUserActivity();
        if (userActivity == null || userActivity.getUser() == null) {
            return null;
        }
        return userActivity.getUser().getId();
    }
}
