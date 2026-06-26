package org.program.pair.domain.media;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface StorageService {

    /**
     * Store a file and return its unique identifier
     */
    String store(MultipartFile file, UUID ownerId, MediaType mediaType) throws IOException;

    /**
     * Load a file as a Path
     */
    Path load(String filename);

    /**
     * Load a file as an InputStream
     */
    InputStream loadAsResource(String filename) throws IOException;

    /**
     * Delete a file
     */
    void delete(String filename) throws IOException;

    /**
     * Initialize storage (create directories, etc.)
     */
    void init() throws IOException;
}
