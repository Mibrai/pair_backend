package org.program.pair.shared.web;

import org.junit.jupiter.api.Test;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.data.domain.Pageable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P-BA-14 — la règle de pagination, écrite une fois. */
class PagesTest {

    @Test
    void uneRouteExistante_rameneUneTailleDemesureeAuPlafond() {
        Pageable page = Pages.borne(2, 100_000);

        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getPageNumber()).isEqualTo(2);
    }

    @Test
    void uneTailleRaisonnable_resteTelleQuelle() {
        assertThat(Pages.borne(0, 20).getPageSize()).isEqualTo(20);
    }

    @Test
    void unePageNegative_ouUneTailleNulle_sontRefusees() {
        assertThatThrownBy(() -> Pages.borne(-1, 20)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> Pages.borne(0, 0)).isInstanceOf(ValidationException.class);
    }

    @Test
    void uneNouvelleRoute_refuseUneTailleAuDelaDuPlafond() {
        assertThatThrownBy(() -> Pages.strict(0, 51))
            .isInstanceOf(ValidationException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.PAGE_SIZE_TOO_LARGE);
        assertThat(Pages.strict(0, 50).getPageSize()).isEqualTo(50);
    }
}
