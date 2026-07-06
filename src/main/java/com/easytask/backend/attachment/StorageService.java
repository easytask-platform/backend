package com.easytask.backend.attachment;

import com.easytask.backend.config.EasyTaskProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores attachment bytes under {@code easytask.storage.dir}; stored name = attachment UUID. */
@Service
public class StorageService {

    private final Path root;

    public StorageService(EasyTaskProperties properties) {
        this.root = Path.of(properties.storage().dir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    void createDirectory() {
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directory " + root, e);
        }
    }

    public void store(MultipartFile file, String storedFilename) {
        try {
            Files.copy(file.getInputStream(), root.resolve(storedFilename),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store attachment", e);
        }
    }

    public Resource load(String storedFilename) {
        return new PathResource(root.resolve(storedFilename));
    }

    public void delete(String storedFilename) {
        try {
            Files.deleteIfExists(root.resolve(storedFilename));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete attachment", e);
        }
    }
}
