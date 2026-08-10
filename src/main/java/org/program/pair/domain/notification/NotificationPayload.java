package org.program.pair.domain.notification;

import org.program.pair.domain.activity.Activity;
import org.program.pair.domain.activity.Category;
import org.program.pair.domain.activity.UserActivity;
import org.program.pair.domain.program.Program;
import org.program.pair.domain.program.Schedule;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Construit le {@code payload} d'une notification.
 *
 * <p>Le payload a deux usages, et un seul producteur doit garantir les deux :
 *
 * <ul>
 *   <li><b>naviguer</b> — les identifiants métier du deep-link
 *       ({@code programId}, {@code scheduleId}, {@code userActivityId}) ;</li>
 *   <li><b>écrire la phrase</b> — les libellés dont le client a besoin pour
 *       composer le texte affiché ({@code programTitle}, {@code activityName}),
 *       puisque l'API n'envoie délibérément ni {@code title} ni {@code message}
 *       (voir {@link org.program.pair.domain.notification.dto.NotificationDto}).</li>
 * </ul>
 *
 * <p>S'y ajoute {@code categoryColorRamp} : la carte teinte ses marqueurs depuis
 * ce champ, et une notification qui parle du même programme doit pouvoir porter
 * la même teinte. Sans lui, le client sème sa couleur sur un identifiant
 * différent de celui de la carte, et les deux surfaces divergent alors que les
 * deux codes sont justes.
 *
 * <p><b>Normalisation.</b> Les valeurs sont écrites en JSON scalaire — un
 * {@link UUID} et un {@link Instant} deviennent des chaînes, une énumération son
 * nom. Le payload traverse une colonne {@code jsonb} puis une désérialisation en
 * {@code Map<String, Object>} : sans normalisation à l'écriture, un client verrait
 * le type varier selon le chemin d'écriture. Une valeur nulle n'est pas écrite du
 * tout, plutôt qu'écrite à {@code null} — un client qui teste la présence d'une
 * clé n'a pas à distinguer les deux.
 */
public final class NotificationPayload {

    private final Map<String, Object> values = new LinkedHashMap<>();

    private NotificationPayload() {
    }

    /** Payload sans contexte métier, à remplir clé par clé. */
    public static NotificationPayload empty() {
        return new NotificationPayload();
    }

    /**
     * Contexte d'une séance : de quoi ouvrir la fiche du créneau, celle du
     * programme, et teinter les deux comme la carte les teinte.
     *
     * <p>{@code sessionAt} est la clé canonique de l'instant de la séance —
     * c'est celle que {@code NotificationDto} relit pour exposer
     * {@code scheduledAt}. {@code startsAt} porte la même valeur et n'est
     * conservée que parce que {@code ACTIVITY_ALERT_MATCH} la publiait déjà :
     * la retirer casserait un client qui la lit.
     */
    public static NotificationPayload ofSchedule(Schedule slot) {
        Program program = slot.getProgram();
        NotificationPayload payload = new NotificationPayload()
            .with("scheduleId", slot.getId())
            .with("placeName", slot.getPlaceName())
            .with("sessionAt", slot.getStartsAt())
            .with("startsAt", slot.getStartsAt());

        return program == null ? payload : payload.merge(ofProgram(program));
    }

    /** Contexte d'un programme, activité et catégorie comprises. */
    public static NotificationPayload ofProgram(Program program) {
        NotificationPayload payload = new NotificationPayload()
            .with("programId", program.getId())
            .with("programTitle", program.getTitle());

        UserActivity userActivity = program.getUserActivity();
        return userActivity == null ? payload : payload.merge(ofUserActivity(userActivity));
    }

    /** Contexte d'une activité déclarée par un utilisateur. */
    public static NotificationPayload ofUserActivity(UserActivity userActivity) {
        NotificationPayload payload = new NotificationPayload()
            .with("userActivityId", userActivity.getId());

        Activity activity = userActivity.getActivity();
        if (activity == null) {
            return payload;
        }
        payload.with("activityId", activity.getId())
            .with("activityName", activity.getName());

        Category category = activity.getCategory();
        if (category != null) {
            payload.with("categoryId", category.getId())
                .with("categoryColorRamp", category.getColorRamp());
        }
        return payload;
    }

    /** Ajoute une clé, sauf si sa valeur est nulle. */
    public NotificationPayload with(String key, Object value) {
        Object normalized = normalize(value);
        if (normalized != null) {
            values.put(key, normalized);
        }
        return this;
    }

    /** Les clés déjà posées ici gagnent : un appelant peut spécialiser le contexte. */
    private NotificationPayload merge(NotificationPayload other) {
        other.values.forEach(values::putIfAbsent);
        return this;
    }

    public Map<String, Object> build() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof UUID || value instanceof Instant) {
            return value.toString();
        }
        if (value instanceof Enum<?> constant) {
            return constant.name();
        }
        return value;
    }
}
