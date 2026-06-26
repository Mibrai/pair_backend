package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
public class MediaValidator {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB
    private static final List<String> ALLOWED_IMAGE_TYPES = Arrays.asList(
        "image/jpeg",
        "image/jpg",
        "image/png",
        "image/webp"
    );
    private static final List<String> ALLOWED_VIDEO_TYPES = Arrays.asList(
        "video/mp4",
        "video/quicktime",
        "video/x-msvideo"
    );

    private final Tika tika = new Tika();

    public void validateImage(MultipartFile file) throws IOException {
        validateFileNotEmpty(file);
        validateFileSize(file);

        String detectedType = tika.detect(file.getInputStream());
        log.info("Detected MIME type: {} for file: {}", detectedType, file.getOriginalFilename());

        if (!ALLOWED_IMAGE_TYPES.contains(detectedType)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid file type. Only JPEG, PNG, and WebP images are allowed. Detected: " + detectedType
            );
        }
    }

    public void validateVideo(MultipartFile file) throws IOException {
        validateFileNotEmpty(file);
        validateFileSize(file);

        String detectedType = tika.detect(file.getInputStream());
        log.info("Detected MIME type: {} for file: {}", detectedType, file.getOriginalFilename());

        if (!ALLOWED_VIDEO_TYPES.contains(detectedType)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid file type. Only MP4, MOV, and AVI videos are allowed. Detected: " + detectedType
            );
        }
    }

    private void validateFileNotEmpty(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File is empty");
        }
    }

    private void validateFileSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "File size exceeds maximum allowed size of 10MB"
            );
        }
    }
}
