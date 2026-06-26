package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Component
@Slf4j
public class ImageProcessor {

    private static final int MAX_WIDTH = 1920;
    private static final int MAX_HEIGHT = 1080;
    private static final int THUMBNAIL_SIZE = 300;
    private static final float QUALITY = 0.85f;

    /**
     * Process and optimize an image
     * - Resize if too large
     * - Re-encode for security
     * - Compress to reduce file size
     */
    public InputStream processImage(MultipartFile file) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Thumbnails.of(file.getInputStream())
            .size(MAX_WIDTH, MAX_HEIGHT)
            .outputQuality(QUALITY)
            .toOutputStream(outputStream);

        byte[] processedBytes = outputStream.toByteArray();
        log.info("Processed image: {} -> {} bytes", file.getOriginalFilename(), processedBytes.length);

        return new ByteArrayInputStream(processedBytes);
    }

    /**
     * Generate a thumbnail
     */
    public InputStream generateThumbnail(MultipartFile file) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        Thumbnails.of(file.getInputStream())
            .size(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
            .outputQuality(QUALITY)
            .toOutputStream(outputStream);

        byte[] thumbnailBytes = outputStream.toByteArray();
        log.info("Generated thumbnail: {} -> {} bytes", file.getOriginalFilename(), thumbnailBytes.length);

        return new ByteArrayInputStream(thumbnailBytes);
    }

    /**
     * Process and generate thumbnail in one go
     */
    public ProcessedImage processWithThumbnail(MultipartFile file) throws IOException {
        InputStream mainImage = processImage(file);
        InputStream thumbnail = generateThumbnail(file);

        return new ProcessedImage(mainImage, thumbnail);
    }

    public record ProcessedImage(InputStream mainImage, InputStream thumbnail) {}
}
