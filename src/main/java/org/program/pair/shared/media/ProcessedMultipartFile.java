package org.program.pair.shared.media;

import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Une image réencodée par {@code ImageProcessor}, présentée au stockage sous la
 * forme d'un {@link MultipartFile}.
 *
 * <p>Quatre contrôleurs en portaient chacun une copie privée, au mot près
 * (P-BA-17) : une correction dans l'une aurait laissé les trois autres derrière.
 * Le type annoncé est toujours {@code image/jpeg} — c'est ce que produit le
 * réencodage — et la taille n'est pas connue sans lire le flux, d'où {@code 0}.
 */
public final class ProcessedMultipartFile implements MultipartFile {

    private final String originalFilename;
    private final InputStream inputStream;

    public ProcessedMultipartFile(String originalFilename, InputStream inputStream) {
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
    public void transferTo(File dest) {
        throw new UnsupportedOperationException();
    }
}
