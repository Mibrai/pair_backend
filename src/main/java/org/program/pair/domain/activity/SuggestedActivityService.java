package org.program.pair.domain.activity;

import lombok.RequiredArgsConstructor;
import org.program.pair.domain.activity.dto.SuggestedActivityDto;
import org.program.pair.repository.ActivityRepository;
import org.program.pair.repository.ActivityRepository.SuggestedActivityRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ce qu'on propose à quelqu'un qui vient d'autoriser sa position et n'a encore
 * rien déclaré.
 *
 * <p><b>Cet écran ne doit jamais être vide.</b> C'est la première chose que voit
 * une personne qui vient d'installer l'application, et une liste vide y raconte
 * que le service est mort — alors qu'elle dit seulement que personne n'habite à
 * proximité. D'où le repli national, et d'où le fait que ce service ne rende une
 * liste vide que dans un seul cas : une base sans aucune activité.
 *
 * <p><b>Et il ne doit pas être monotone.</b> Les activités les plus déclarées
 * d'une ville appartiennent souvent à deux ou trois catégories ; s'en tenir au
 * classement brut proposerait douze variantes de sport. Le tri par
 * représentation est donc corrigé pour qu'au moins quatre catégories soient
 * présentes quand la base en offre autant — un plancher de diversité, pas un
 * objectif : au-delà, le classement reprend ses droits.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SuggestedActivityService {

    /**
     * Rayon de recherche. Le même que le défaut de l'Explorer : une personne qui
     * découvre l'application et une personne qui parcourt le catalogue se posent
     * la même question, et deux rayons différents donneraient deux réponses
     * différentes au même endroit.
     */
    private static final int RADIUS_METERS = 25_000;

    /** Plancher de diversité exigé par la spécification du lot A1. */
    private static final int MIN_CATEGORIES = 4;

    /**
     * De quoi corriger la diversité sans relancer de requête. On demande plus de
     * lignes que nécessaire pour disposer de candidats dans les catégories que le
     * classement brut n'aurait pas retenues.
     */
    private static final int OVERFETCH = 5;

    private final ActivityRepository activityRepository;

    public List<SuggestedActivityDto> suggest(UUID requesterId, double lat, double lng, int limit) {
        List<SuggestedActivityRow> nearby = activityRepository.findMostPractisedInRadius(
            lat, lng, RADIUS_METERS, requesterId, limit * OVERFETCH);

        if (!nearby.isEmpty()) {
            return toDtos(diversify(nearby, limit), false);
        }

        // Zone vide : personne d'inscrit dans le rayon, ou personne qui accepte
        // d'y être vu. Le repli n'est pas un cas d'erreur, c'est le cas nominal
        // d'une ville où l'application arrive.
        List<SuggestedActivityRow> global = activityRepository.findMostPractisedGlobally(
            requesterId, limit * OVERFETCH);

        return toDtos(diversify(global, limit), true);
    }

    /**
     * Retient {@code limit} lignes en garantissant, si la matière le permet, au
     * moins {@link #MIN_CATEGORIES} catégories distinctes.
     *
     * <p>On part du classement brut, puis on échange les lignes de queue
     * appartenant à des catégories déjà bien servies contre la meilleure ligne de
     * chaque catégorie absente. Échanger par la queue plutôt que rebattre toute
     * la liste préserve le haut du classement : les activités réellement les plus
     * pratiquées restent en tête, ce sont les places suivantes qui s'ouvrent.
     */
    private List<SuggestedActivityRow> diversify(List<SuggestedActivityRow> ranked, int limit) {
        if (ranked.size() <= limit) {
            return ranked;
        }

        List<SuggestedActivityRow> selected = new ArrayList<>(ranked.subList(0, limit));

        // Meilleure ligne de chaque catégorie absente de la sélection, dans
        // l'ordre du classement.
        Map<UUID, SuggestedActivityRow> missingByCategory = new LinkedHashMap<>();
        for (SuggestedActivityRow row : ranked.subList(limit, ranked.size())) {
            if (!containsCategory(selected, row.getCategoryId())) {
                missingByCategory.putIfAbsent(row.getCategoryId(), row);
            }
        }

        for (SuggestedActivityRow candidate : missingByCategory.values()) {
            if (countCategories(selected) >= MIN_CATEGORIES) {
                break;
            }
            int victim = lastIndexOfOverRepresentedCategory(selected);
            if (victim < 0) {
                break;
            }
            selected.set(victim, candidate);
        }

        return selected;
    }

    private boolean containsCategory(List<SuggestedActivityRow> rows, UUID categoryId) {
        return rows.stream().anyMatch(r -> r.getCategoryId().equals(categoryId));
    }

    private long countCategories(List<SuggestedActivityRow> rows) {
        return rows.stream().map(SuggestedActivityRow::getCategoryId).distinct().count();
    }

    /**
     * Dernière ligne dont la catégorie apparaît plus d'une fois — la moins chère
     * à sacrifier, puisque sa catégorie reste représentée après son départ. Rend
     * {@code -1} si toutes les catégories sont uniques : il n'y a alors plus rien
     * à gagner en diversité.
     */
    private int lastIndexOfOverRepresentedCategory(List<SuggestedActivityRow> rows) {
        for (int i = rows.size() - 1; i >= 0; i--) {
            UUID categoryId = rows.get(i).getCategoryId();
            long occurrences = rows.stream()
                .filter(r -> r.getCategoryId().equals(categoryId))
                .count();
            if (occurrences > 1) {
                return i;
            }
        }
        return -1;
    }

    private List<SuggestedActivityDto> toDtos(List<SuggestedActivityRow> rows, boolean fallback) {
        return rows.stream()
            .map(row -> new SuggestedActivityDto(
                row.getId(),
                row.getName(),
                row.getSlug(),
                row.getIcon(),
                row.getImageUrl(),
                row.getCategoryId(),
                row.getCategoryName(),
                fallback ? 0L : row.getPractitioners(),
                fallback))
            .toList();
    }
}
