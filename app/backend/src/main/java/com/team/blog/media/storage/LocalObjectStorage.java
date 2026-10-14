package com.team.blog.media.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 개발·테스트용 로컬 폴더 저장소. 앱이 /media/{키}로 직접 내려준다 (LocalMediaController).
 * 내용 형식은 확장자로 정한다(키를 만들 때 확장자를 형식에 맞춰 붙인다).
 */
class LocalObjectStorage implements ObjectStorage {
    private final Path root;

    LocalObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".upload", ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<StoredObject> get(String key) {
        Path file = resolve(key);
        try {
            return Optional.of(new StoredObject(Files.readAllBytes(file), contentTypeOf(key)));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 키가 저장소 폴더 밖을 가리키지 못하게 막는다 (../ 등). */
    private Path resolve(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root) || p.equals(root)) throw new IllegalArgumentException("잘못된 저장 키");
        return p;
    }

    static String contentTypeOf(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT);
        if (k.endsWith(".webp")) return "image/webp";
        if (k.endsWith(".mp4")) return "video/mp4"; // 쇼츠 영상 (099)
        if (k.endsWith(".png")) return "image/png";
        if (k.endsWith(".gif")) return "image/gif";
        if (k.endsWith(".jpg") || k.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }
}
