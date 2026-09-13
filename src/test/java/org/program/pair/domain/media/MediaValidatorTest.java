package org.program.pair.domain.media;

import org.junit.jupiter.api.Test;
import org.program.pair.shared.exception.ErrorCode;
import org.program.pair.shared.exception.ValidationException;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P-BS-20 — la détection de type tient après la montée de Tika : c'est le contenu
 * qui décide, jamais le nom ni l'en-tête que le client annonce.
 */
class MediaValidatorTest {

    private final MediaValidator validator = new MediaValidator();

    @Test
    void unHtmlDeguiseEnJpeg_estToujoursRefuse() {
        byte[] html = "<!DOCTYPE html><html><body><script>alert(1)</script></body></html>"
            .getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> validator.validateImage(
                new MockMultipartFile("file", "photo.jpg", "image/jpeg", html)))
            .isInstanceOf(ValidationException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.MEDIA_TYPE_NOT_ALLOWED);
    }

    @Test
    void unVraiJpeg_estAccepte() throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB), "jpg", jpeg);

        assertThatCode(() -> validator.validateImage(
                new MockMultipartFile("file", "photo.bin", "application/octet-stream", jpeg.toByteArray())))
            .doesNotThrowAnyException();
    }
}
