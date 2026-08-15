package org.program.pair.domain.map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La maille d'agrégation, vérifiée comme une <b>pente</b> et non comme une table
 * de valeurs.
 *
 * <p>Le défaut corrigé n'était pas un réglage trop grossier en un point : la
 * table divisait la maille par deux tous les <i>deux</i> paliers, quand la
 * résolution de la carte l'est à <i>chaque</i> palier. La cellule doublait donc
 * de taille apparente à chaque niveau gagné, jusqu'à ~11 332 px au palier 20 —
 * vingt-huit largeurs d'écran. Une pastille y était irrésolvable : zoomer ne la
 * défaisait pas.
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
    private final MapService mapService = new MapService(null, null, null, null, null, null);

    private static final int ANCHOR_ZOOM = 12;
    private static final int MAX_ZOOM = 20;

    /**
     * L'invariant, directement : au-dessus de l'ancre, une cellule occupe
     * toujours la même fraction de l'écran.
     *
     * <p>C'est le test qui aurait échoué sur l'ancienne table, et le seul qui
     * échouera si quelqu'un réintroduit un palier plat.
     */
    @Test
    void auDessusDeLAncre_laCelluleDoitGarderLaMemeTailleEcran() {
        double reference = cellSizeInPixels(ANCHOR_ZOOM);

        for (int zoom = ANCHOR_ZOOM + 1; zoom <= MAX_ZOOM; zoom++) {
            assertThat(cellSizeInPixels(zoom))
                .as("palier %d : la cellule doit rester à l'échelle de l'écran", zoom)
                .isCloseTo(reference, org.assertj.core.data.Offset.offset(1e-6));
        }
    }

    /** La même chose exprimée sur la maille elle-même : un palier, une moitié. */
    @Test
    void auDessusDeLAncre_laMailleDoitEtreDiviseeParDeuxAChaquePalier() {
        for (int zoom = ANCHOR_ZOOM + 1; zoom <= MAX_ZOOM; zoom++) {
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
     * La conséquence demandée par le client : au palier maximal, la maille est de
     * l'ordre de la cinquantaine de mètres, pas du kilomètre. Assertion large à
     * dessein — c'est l'ordre de grandeur qui est contractuel, pas la valeur.
     */
    @Test
    void auPalierMaximal_laMailleDoitEtreDeLOrdreDeLaCinquantaineDeMetres() {
        double meters = mapService.calculateGridSize(MAX_ZOOM) * 111_320.0;

        assertThat(meters).isBetween(20.0, 80.0);
    }

    /**
     * Les paliers bas ne bougent pas. La même dérive les rend au contraire trop
     * fins — quasiment aucune agrégation sur une carte monde — mais ce défaut
     * reste ouvert et se traite séparément. Ce test dit que le geste actuel ne
     * l'a pas touché.
     */
    @Test
    void enDessousDeLAncre_lesValeursHistoriquesDoiventEtreIntactes() {
        assertThat(mapService.calculateGridSize(1)).isEqualTo(5.0);
        assertThat(mapService.calculateGridSize(3)).isEqualTo(5.0);
        assertThat(mapService.calculateGridSize(5)).isEqualTo(2.0);
        assertThat(mapService.calculateGridSize(7)).isEqualTo(1.0);
        assertThat(mapService.calculateGridSize(9)).isEqualTo(0.5);
        assertThat(mapService.calculateGridSize(11)).isEqualTo(0.25);
    }

    /**
     * Les deux routes qui agrègent valident déjà {@code zoom} dans [1, 20]. Le
     * plafond interne n'est là que pour qu'un appel non validé — un futur
     * appelant, un test — ne puisse pas produire un décalage aberrant.
     */
    @Test
    void unZoomHorsBornes_neDoitPasProduireDeMailleAberrante() {
        assertThat(mapService.calculateGridSize(60))
            .as("plafonné au palier maximal, jamais un décalage de 48 bits")
            .isEqualTo(mapService.calculateGridSize(MAX_ZOOM));
        assertThat(mapService.calculateGridSize(0))
            .as("repli historique")
            .isEqualTo(1.0);
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
}
