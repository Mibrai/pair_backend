package org.program.pair.domain.media.dto;

public record MediaUploadResponse(
    String url,
    String thumbnailUrl,
    String filename,
    long size,
    String mimeType
) {}
