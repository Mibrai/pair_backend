package org.program.pair.domain.search.dto;

/**
 * Le lieu auquel un résultat de recherche de type {@code program} est situé :
 * la séance localisée la plus proche du point interrogé.
 *
 * <p>Interne au calcul des résultats, jamais sérialisé — {@link SearchResultDto}
 * en porte les trois valeurs à plat, pour ne pas changer la forme de la réponse.
 *
 * <p>Son absence est une information : un programme sans séance localisée, ou
 * qui se tient à distance, n'en a pas, et ses coordonnées valent alors
 * {@code null}. C'est ce que le client a demandé, et il a eu raison de le
 * demander : le repli silencieux sur la position de l'organisateur affichait un
 * chiffre faux là où rien n'aurait dû s'afficher, et rendait le défaut
 * indétectable depuis les journaux.
 */
public record ProgramVenue(double lat, double lng, double distanceMeters) {}
