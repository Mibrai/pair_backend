package org.program.pair.domain.media;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.domain.media.dto.MediaUploadResponse;
import org.program.pair.shared.security.UserPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/api/media")
@RequiredArgsConstructor
@Slf4j
public class MediaController {

    private final StorageService storageService;
    private final MediaValidator mediaValidator;
    private final ImageProcessor imageProcessor;

    @PostMapping("/upload/image")
    public MediaUploadResponse uploadImage(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "type", defaultValue = "PROGRAM_IMAGE") org.program.pair.domain.media.MediaType mediaType) throws IOException {

        log.info("Upload request from user: {}, file: {}, size: {}",
            principal.getId(), file.getOriginalFilename(), file.getSize());

        // Validate
        mediaValidator.validateImage(file);

        // Process image (resize, re-encode, optimize)
        InputStream processedImage = imageProcessor.processImage(file);

        // Create a new MultipartFile from processed stream
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(
            file.getOriginalFilename(),
            processedImage
        );

        // Store
        String filename = storageService.store(processedFile, principal.getId(), mediaType);

        String url = "/api/media/files/" + filename;

        return new MediaUploadResponse(
            url,
            null,  // TODO: Implement thumbnail generation
            filename,
            file.getSize(),
            file.getContentType()
        );
    }

    @PostMapping("/upload/avatar")
    public MediaUploadResponse uploadAvatar(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam("file") MultipartFile file) throws IOException {

        log.info("Avatar upload from user: {}, file: {}", principal.getId(), file.getOriginalFilename());

        mediaValidator.validateImage(file);
        InputStream processedImage = imageProcessor.processImage(file);
        ProcessedMultipartFile processedFile = new ProcessedMultipartFile(file.getOriginalFilename(), processedImage);

        String filename = storageService.store(processedFile, principal.getId(), org.program.pair.domain.media.MediaType.USER_AVATAR);

        return new MediaUploadResponse(
            "/api/media/files/" + filename,
            null,
            filename,
            file.getSize(),
            file.getContentType()
        );
    }

    @GetMapping("/files/**")
    public ResponseEntity<InputStreamResource> serveFile(@AuthenticationPrincipal UserPrincipal principal,
                                                          @RequestParam(required = false) String path) {
        try {
            // Extract path from request
            String filename = path != null ? path : extractPath();

            InputStream inputStream = storageService.loadAsResource(filename);

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .contentType(MediaType.IMAGE_JPEG)
                .body(new InputStreamResource(inputStream));

        } catch (IOException e) {
            log.error("Error serving file", e);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
    }

    @DeleteMapping("/files/**")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFile(@AuthenticationPrincipal UserPrincipal principal) {
        try {
            String filename = extractPath();
            storageService.delete(filename);
            log.info("User {} deleted file: {}", principal.getId(), filename);
        } catch (IOException e) {
            log.error("Error deleting file", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not delete file");
        }
    }

    private String extractPath() {
        // TODO: Extract path from request context
        throw new UnsupportedOperationException("Path extraction not implemented");
    }

    // Helper class for processed files
    private static class ProcessedMultipartFile implements MultipartFile {
        private final String originalFilename;
        private final InputStream inputStream;

        ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
            this.originalFilename = originalFilename;
            this.inputStream = inputStream;
        }

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return "image/jpeg";
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public long getSize() {
            return 0;
        }

        @Override
        public byte[] getBytes() throws IOException {
            return inputStream.readAllBytes();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
            throw new UnsupportedOperationException();
        }
    }
}
