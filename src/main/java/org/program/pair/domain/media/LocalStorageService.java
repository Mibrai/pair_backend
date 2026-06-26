package org.program.pair.domain.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
@Slf4j
public class LocalStorageService implements StorageService {

    private final Path rootLocation;

    public LocalStorageService(@Value("${storage.location:uploads}") String storageLocation) {
        this.rootLocation = Paths.get(storageLocation);
    }

    @Override
    public String store(MultipartFile file, UUID ownerId, MediaType mediaType) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot store empty file");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }

        // Generate unique filename
        String extension = "";
        int lastDot = originalFilename.lastIndexOf('.');
        if (lastDot > 0) {
            extension = originalFilename.substring(lastDot);
        }

        String filename = UUID.randomUUID().toString() + extension;
        Path destinationDir = rootLocation.resolve(mediaType.name().toLowerCase());

        if (!Files.exists(destinationDir)) {
            Files.createDirectories(destinationDir);
        }

        Path destinationFile = destinationDir.resolve(filename);

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            log.info("Stored file: {} for owner: {}", filename, ownerId);
            return mediaType.name().toLowerCase() + "/" + filename;
        }
    }

    @Override
    public Path load(String filename) {
        return rootLocation.resolve(filename);
    }

    @Override
    public InputStream loadAsResource(String filename) throws IOException {
        Path file = load(filename);
        if (!Files.exists(file) || !Files.isReadable(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        return Files.newInputStream(file);
    }

    @Override
    public void delete(String filename) throws IOException {
        Path file = load(filename);
        if (Files.exists(file)) {
            Files.delete(file);
            log.info("Deleted file: {}", filename);
        }
    }

    @Override
    public void init() throws IOException {
        if (!Files.exists(rootLocation)) {
            Files.createDirectories(rootLocation);
        }

        for (MediaType type : MediaType.values()) {
            Path typeDir = rootLocation.resolve(type.name().toLowerCase());
            if (!Files.exists(typeDir)) {
                Files.createDirectories(typeDir);
            }
        }

        log.info("Storage initialized at: {}", rootLocation.toAbsolutePath());
    }
}
