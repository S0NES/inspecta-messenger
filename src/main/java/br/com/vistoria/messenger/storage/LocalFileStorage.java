package br.com.vistoria.messenger.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Default (inspecta.storage.provider=local) zero-config: grava no disco local sob um diretorio
 * base, sem nenhuma dependencia externa — igual ao papel que os brokers mock cumprem,
 * cobre test/dev local sem credencial nenhuma.
 */
@Component("local")
@Slf4j
public class LocalFileStorage implements FileStorage {

    @Value("${inspecta.storage.local.base-path:./data/uploads}") private String basePath;

    @Override
    public String name() {
        return "local";
    }

    @Override
    public void upload(String key, byte[] bytes, String mimeType) {
        Path destino = resolve(key);
        try {
            Files.createDirectories(destino.getParent());
            Files.write(destino, bytes);
            log.info("[storage-local] upload key={} bytes={} mime={}", key, bytes.length, mimeType);
        } catch (IOException e) {
            throw new UncheckedIOException("local storage upload falhou key=" + key, e);
        }
    }

    @Override
    public byte[] download(String key) {
        Path origem = resolve(key);
        try {
            return Files.readAllBytes(origem);
        } catch (IOException e) {
            throw new UncheckedIOException("local storage download falhou key=" + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path alvo = resolve(key);
        try {
            Files.deleteIfExists(alvo);
            log.info("[storage-local] delete key={}", key);
        } catch (IOException e) {
            throw new UncheckedIOException("local storage delete falhou key=" + key, e);
        }
    }

    /** Rejeita qualquer key com segmento ".." (path traversal) antes de resolver contra o base dir. */
    private Path resolve(String key) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("storage key nao pode ser vazia");
        for (String segmento : key.split("[/\\\\]")) {
            if ("..".equals(segmento)) {
                throw new IllegalArgumentException("storage key invalida (path traversal): " + key);
            }
        }
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        return base.resolve(key).normalize();
    }
}
