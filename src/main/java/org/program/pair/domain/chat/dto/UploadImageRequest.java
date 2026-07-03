package org.program.pair.domain.chat.dto;

import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public record UploadImageRequest(
    @NotNull(message = "L'image est requise")
    MultipartFile image
) {}
