package org.program.pair.domain.map;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La maille d'agrégation, vérifiée comme une <b>pente</b> et non comme une table
 * de valeurs.
 *
 * <p>Le défaut corrigé n'était pas un réglage trop grossier en un point : la
 * table divisait la maille par deux tous les <i>deux</i> paliers, quand la
 * résolution de la carte l'est à <i>chaque</i> palier. La cellule doublait donc
 * de taille apparente à chaque niveau gagné, et se trompait dans les deux sens à
 * la fois — ~11 332 px au palier 20, où plus rien ne se défaisait, et 11 px au
 * palier 1, où presque rien ne s'agrégeait.
 *
 * <p>Piquer les nouvelles valeurs une à une reconstruirait le même piège, en
 * décalé : la table redeviendrait juste en un point et fausse ailleurs, sans
 * qu'aucun test ne s'en aperçoive. Ces tests portent donc sur l'invariant —
 * <b>taille écran constante</b> — qui est la propriété dont dépend le produit.
 * Une seule valeur est ancrée, celle du palier 12, parce qu'elle fixe l'échelle
 * absolue que la pente seule ne détermine pas.
 */
class MapServiceGridSizeTest {

    // calculateGridSize ne touche aucune dépendance : le service est construit à
    // vide, comme MapServiceBlurTest le fait pour applyBlur.
    private final MapService mapService = new MapService(null, null, null, null, null, null, null);

    private static final int MIN_ZOOM = 1;
    private static final int MAX_ZOOM = 20;
    private static final int ANCHOR_ZOOM = 12;

    /**
     * L'invariant, directement, et désormais sur <b>toute</b> la plage : une
     * cellule occupe toujours la même fraction de l'écran.
     *
     * <p>C'est le test qui aurait échoué sur l'ancienne table, et le seul qui
     * échouera si quelqu'un réintroduit un palier plat.
     */
    @Test
    void laCelluleDoitGarderLaMemeTailleEcran_aTousLesPaliers() {
        double reference = cellSizeInPixels(ANCHOR_ZOOM);

        for (int zoom = MIN_ZOOM; zoom <= MAX_ZOOM; zoom++) {
            assertThat(cellSizeInPixels(zoom))
                .as("palier %d : la cellule doit rester à l'échelle de l'écran", zoom)
                .isCloseTo(reference, Offset.offset(1e-6));
        }
    }

    /** La même chose exprimée sur la maille elle-même : un palier, une moitié. */
    @Test
    void laMailleDoitEtreDiviseeParDeuxAChaquePalier() {
        for (int zoom = MIN_ZOOM + 1; zoom <= MAX_ZOOM; zoom++) {
            assertThat(mapService.calculateGridSize(zoom))
                .as("palier %d", zoom)
                .isEqualTo(mapService.calculateGridSize(zoom - 1) / 2.0);
        }
    }

    /**
     * L'échelle absolue, ancrée en un point. Sans elle, une pente juste pourrait
     * partir de n'importe où.
     */
    @Test
    void lAncreDoitResterALaValeurHistorique() {
        assertThat(mapService.calculateGridSize(ANCHOR_ZOOM)).isEqualTo(0.1);
    }

    /**
     * Le haut de la plage, tel que le client l'a demandé : au palier maximal, la
     * maille est de l'ordre de la cinquantaine de mètres, pas du kilomètre.
     * Assertion large à dessein — c'est l'ordre de grandeur qui est contractuel,
     * pas la valeur.
     */
    @Test
    void auPalierMaximal_laMailleDoitEtreDeLOrdreDeLaCinquantaineDeMetres() {
        double meters = mapService.calculateGridSize(MAX_ZOOM) * 111_320.0;

        assertThat(meters).isBetween(20.0, 80.0);
    }

    /**
     * Le bas de la plage, symétrique du précédent : sur une carte monde, une
     * cellule couvre au moins toute l'étendue des latitudes, qui cessent donc de
     * fragmenter l'agrégation. L'ancienne table y mettait 5° — de quoi laisser
     * des dizaines de pastilles se superposer là où l'agrégation est le plus
     * nécessaire.
     *
     * <p>Le seuil est bien 180° et non 360° : la grille reste un pavage, et les
     * frontières restantes en longitude passent par le méridien de Greenwich.
     * Une zone à cheval sur lui rend deux groupes au lieu d'un, ce que
     * {@link #uneZoneAChevalSurGreenwich_resteCoupeeEnDeux} constate plutôt que
     * de le taire.
     */
    @Test
    void auPalierMinimal_uneCelluleDoitCouvrirToutesLesLatitudes() {
        assertThat(mapService.calculateGridSize(MIN_ZOOM))
            .as("les 180° de latitude doivent tenir dans une cellule")
            .isGreaterThan(180.0);
    }

    /**
     * La limite de l'approche, écrite noir sur blanc plutôt que découverte en
     * production. Une grille fixe a des frontières ; élargir la maille ne les
     * crée pas, mais rend chacune plus lourde de conséquences.
     *
     * <p>La France est le cas concret : elle s'étend de -5° à +8° de longitude,
     * donc à cheval sur Greenwich. Au palier 1 elle rend deux groupes, quel que
     * soit le rapprochement de ses membres. Ce n'est pas une régression — la
     * frontière à 0° existait déjà à tous les paliers de l'ancienne table, qui y
     * découpait la France en neuf cellules plutôt qu'en deux — mais c'est
     * désormais l'artefact dominant, et le client doit pouvoir le reconnaître.
     */
    @Test
    void uneZoneAChevalSurGreenwich_resteCoupeeEnDeux() {
        double grid = mapService.calculateGridSize(MIN_ZOOM);

        assertThat(cellIndex(-5.0, grid))
            .as("Brest et Strasbourg tombent de part et d'autre du méridien")
            .isNotEqualTo(cellIndex(8.0, grid));
    }

    /**
     * Les deux routes qui agrègent valident déjà {@code zoom} dans [1, 20]. Le
     * bornage interne n'est là que pour qu'un appel non validé — un futur
     * appelant, un test — ne puisse pas produire une maille aberrante.
     */
    @Test
    void unZoomHorsBornes_doitEtreRameneALaPlageValide() {
        assertThat(mapService.calculateGridSize(60))
            .as("plafonné au palier maximal")
            .isEqualTo(mapService.calculateGridSize(MAX_ZOOM));
        assertThat(mapService.calculateGridSize(0))
            .as("ramené au palier minimal")
            .isEqualTo(mapService.calculateGridSize(MIN_ZOOM));
        assertThat(mapService.calculateGridSize(-3))
            .as("un zoom négatif ne doit pas retourner la pente")
            .isEqualTo(mapService.calculateGridSize(MIN_ZOOM));
    }

    /**
     * Côté d'une cellule en pixels écran, en projection Web Mercator à la
     * latitude de Paris. C'est l'unité dans laquelle le défaut se voyait : la
     * maille en degrés, elle, décroissait bien à chaque palier — trop lentement.
     */
    private double cellSizeInPixels(int zoom) {
        double metersPerPixel = 156_543.03 * Math.cos(Math.toRadians(48.85)) / Math.pow(2, zoom);
        return mapService.calculateGridSize(zoom) * 111_320.0 / metersPerPixel;
    }

    /** Indice de cellule sur un axe, comme {@code clusterMarkers} le calcule. */
    private static int cellIndex(double degrees, double gridSize) {
        return (int) Math.floor(degrees / gridSize);
    }
}
