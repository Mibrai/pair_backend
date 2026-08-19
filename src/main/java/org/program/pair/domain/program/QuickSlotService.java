package org.program.pair.domain.program;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.ActivityFormat;
import org.program.pair.domain.activity.ActivityLevel;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.dto.CreateScheduleRequest;
import org.program.pair.domain.program.dto.QuickSlotRequest;
import org.program.pair.domain.program.dto.ScheduleDto;
import org.program.pair.domain.program.dto.SlotFeedItemDto;
import org.program.pair.domain.user.User;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.UserActivityRepository;
import org.program.pair.repository.UserRepository;
import org.program.pair.shared.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * « Je cherche quelqu'un pour… » — un créneau publié en un seul appel.
 *
 * <p>Le chemin complet demande quatre allers-retours : déclarer l'activité,
 * créer le programme, le passer d'un brouillon à un programme actif, poser le
 * créneau. Quatre écrans pour dire « je vais courir samedi ». Ce service fait les
 * trois premiers pour l'appelant et lui rend le créneau.
 *
 * <p><b>Aucune entité nouvelle.</b> Ce qui est créé ici est exactement ce que
 * crée le chemin complet — une {@code UserActivity}, un {@code Program}, un
 * {@code Schedule}. Seul le nombre de requêtes change, jamais le modèle.
 *
 * <p><b>Le créneau est posé par {@link ProgramService#addSchedule}</b>, et non
 * écrit ici. Cette méthode ne fait pas que persister une ligne : elle rafraîchit
 * la prochaine séance du programme, annonce le créneau aux abonnés lorsqu'il est
 * le premier, et déclenche les alertes d'activité. Un raccourci qui écrirait le
 * {@code Schedule} directement perdrait ces trois effets sans qu'aucun test ne
 * s'en aperçoive — le créneau existerait, simplement personne n'en serait averti.
 *
 * <p>Tout se joue dans une seule transaction : l'activité déclarée sans le
 * programme, ou le programme sans son créneau, laisserait derrière lui un objet
 * que rien ne rattache à rien.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class QuickSlotService {

    private final UserRepository userRepository;
    private final ActivityRepository activityRepository;
    private final UserActivityRepository userActivityRepository;
    private final ProgramService programService;
    private final SlotService slotService;

    /**
     * Fuseau des titres auto-générés. Le même que celui du développement des
     * récurrences : deux fuseaux différents afficheraient « samedi » ici et
     * « vendredi » là pour la même séance.
     */
    @Value("${pair.recurrence.zone:Europe/Paris}")
    private String zoneId;

    private static final DateTimeFormatter TITLE_DATE =
        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    public SlotFeedItemDto create(UUID userId, QuickSlotRequest request) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("Utilisateur introuvable."));

        Activity activity = activityRepository.findById(request.activityId())
            .orElseThrow(() -> new ResourceNotFoundException("Activité introuvable."));

        UserActivity userActivity = findOrDeclare(user, activity, request);
        Program program = programService.createQuickProgram(userActivity, titleFor(activity, request));

        // Le créneau du chemin court est ouvert aux partenaires par définition :
        // c'est la seule raison pour laquelle on le publie.
        ScheduleDto schedule = programService.addSchedule(userId, program.getId(),
            new CreateScheduleRequest(
                request.placeName(),
                request.placeType(),
                request.lat(),
                request.lng(),
                request.addressPublic(),
                request.showExactAddress(),
                request.city(),
                request.startsAt(),
                request.endsAt(),
                null,                       // pas de récurrence : on cherche quelqu'un pour une fois
                request.maxParticipants(),
                true,
                request.welcomeNote(),
                // Le chemin court ne demande pas de langue : un écran de
                // publication en une fois n'a pas la place de tout demander.
                null));

        // Rendu par le même chemin que /api/slots/{id} : le client n'a qu'un seul
        // modèle de créneau à maintenir, et il est identique par construction
        // plutôt que par recopie.
        return slotService.getSlot(schedule.id(), userId);
    }

    /**
     * L'activité au profil, créée si elle n'y est pas.
     *
     * <p>Passer par {@code ActivityService.addActivityToProfile} serait tentant,
     * mais cette méthode lève un {@code 409} quand l'activité est déjà déclarée —
     * ce qui ferait échouer le deuxième créneau rapide sur une même activité,
     * c'est-à-dire le cas normal de quelqu'un qui court toutes les semaines.
     *
     * <p><b>Le niveau et le format ne sont appliqués qu'à la création.</b> Ils
     * décrivent la personne, pas la séance : les réécrire à chaque créneau
     * laisserait un formulaire de publication modifier silencieusement un profil.
     */
    private UserActivity findOrDeclare(User user, Activity activity, QuickSlotRequest request) {
        return userActivityRepository
            .findByUserIdAndActivityId(user.getId(), activity.getId())
            .orElseGet(() -> userActivityRepository.save(UserActivity.builder()
                .user(user)
                .activity(activity)
                .level(request.level() != null ? request.level() : ActivityLevel.ANY)
                .format(request.format() != null ? request.format() : ActivityFormat.ANY)
                .build()));
    }

    /**
     * Titre auto-généré : l'activité et le jour.
     *
     * <p>Il sert d'étiquette, pas de promesse — personne ne l'a écrit. Il reste
     * donc factuel, et court : c'est le nom de l'activité que les gens lisent
     * dans le fil, pas la phrase qui l'entoure.
     */
    private String titleFor(Activity activity, QuickSlotRequest request) {
        String day = TITLE_DATE.format(request.startsAt().atZone(ZoneId.of(zoneId)));
        return activity.getName() + " — " + day;
    }
}
