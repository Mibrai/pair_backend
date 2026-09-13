package org.program.pair.domain.media;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P-MS-02 — le réencodage est la purge des métadonnées.
 *
 * <p>Une photo prise au téléphone porte sa position dans ses balises EXIF. L'app
 * les retire avant l'envoi, mais elle ne peut pas le garantir : un échec de
 * préparation envoie l'original, et une ancienne version ne les retire pas du
 * tout. C'est donc le serveur qui tient l'invariant — tout fichier stocké passe
 * par {@link ImageProcessor}, et ce qui en sort ne porte plus aucune balise.
 */
class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    @Test
    void unJpegAvecGps_ressortSansAucuneBaliseExif() throws IOException {
        byte[] withGps = jpegWithGpsExif();
        // Garde de la fixture : sans elle, un test qui ne trouve pas d'EXIF en
        // sortie pourrait simplement n'en avoir jamais mis en entrée.
        assertThat(containsExifSegment(withGps)).as("la fixture porte bien un segment EXIF").isTrue();
        assertThat(indexOf(withGps, "GPS-FIXTURE".getBytes(StandardCharsets.US_ASCII))).isPositive();

        byte[] processed = readAll(processor.processImage(
            new MockMultipartFile("file", "photo.jpg", "image/jpeg", withGps)));

        assertThat(ImageIO.read(new java.io.ByteArrayInputStream(processed)))
            .as("la sortie reste une image lisible").isNotNull();
        assertThat(containsExifSegment(processed)).as("aucun segment EXIF après réencodage").isFalse();
        assertThat(indexOf(processed, "GPS-FIXTURE".getBytes(StandardCharsets.US_ASCII))).isNegative();
    }

    @Test
    void laVignette_neDoitPasNonPlusPorterLesBalises() throws IOException {
        byte[] withGps = jpegWithGpsExif();

        byte[] thumbnail = readAll(processor.generateThumbnail(
            new MockMultipartFile("file", "photo.jpg", "image/jpeg", withGps)));

        assertThat(containsExifSegment(thumbnail)).isFalse();
    }

    // — fixture —

    /**
     * Un JPEG réel, dans lequel on insère juste après SOI un segment APP1 EXIF
     * minimal : un IFD0 qui pointe vers un IFD GPS portant une latitude (48° 51′)
     * et une marque textuelle, pour que la recherche d'octets ne dépende pas
     * d'un encodage de rationnel.
     */
    private static byte[] jpegWithGpsExif() throws IOException {
        BufferedImage image = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 48; y++) {
                image.setRGB(x, y, (x * 4) << 16 | (y * 5) << 8 | 0x40);
            }
        }
        ByteArrayOutputStream plain = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", plain);
        byte[] jpeg = plain.toByteArray();

        byte[] tiff = gpsTiff();
        ByteBuffer app1 = ByteBuffer.allocate(4 + 6 + tiff.length).order(ByteOrder.BIG_ENDIAN);
        app1.put((byte) 0xFF).put((byte) 0xE1);
        app1.putShort((short) (2 + 6 + tiff.length));
        app1.put("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        app1.put(tiff);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                      // SOI
        out.write(app1.array());
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** TIFF big-endian : IFD0 (GPSInfo) → IFD GPS (LatitudeRef, Latitude, ProcessingMethod). */
    private static byte[] gpsTiff() {
        byte[] marker = "ASCII\0\0\0GPS-FIXTURE".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer b = ByteBuffer.allocate(256).order(ByteOrder.BIG_ENDIAN);
        b.put("MM".getBytes(StandardCharsets.US_ASCII)).putShort((short) 42).putInt(8);

        // IFD0 à 8 : une entrée, GPSInfo (0x8825, LONG) → 26
        b.putShort((short) 1);
        b.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(26);
        b.putInt(0);

        // IFD GPS à 26 : trois entrées
        int gpsIfd = 26;
        int dataStart = gpsIfd + 2 + 3 * 12 + 4;    // 68
        b.putShort((short) 3);
        b.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        b.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(dataStart);
        b.putShort((short) 0x001B).putShort((short) 7).putInt(marker.length).putInt(dataStart + 24);
        b.putInt(0);

        // Latitude : 48/1, 51/1, 0/1
        b.putInt(48).putInt(1).putInt(51).putInt(1).putInt(0).putInt(1);
        b.put(marker);

        byte[] tiff = new byte[b.position()];
        b.flip();
        b.get(tiff);
        return tiff;
    }

    // — lecture —

    private static boolean containsExifSegment(byte[] jpeg) {
        // Parcours des segments jusqu'au début des données compressées (SOS).
        int i = 2;
        while (i + 4 <= jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA) {
                return false;
            }
            int length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 <= jpeg.length
                    && new String(jpeg, i + 4, 4, StandardCharsets.US_ASCII).equals("Exif")) {
                return true;
            }
            i += 2 + length;
        }
        return false;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in) {
            return in.readAllBytes();
        }
    }
}
