package br.com.vistoria.messenger.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link LocalFileStorage}: real filesystem via {@code @TempDir}, no mocking
 * of java.nio.file — this is the zero-config default that plays the mock broker's role
 * for storage, so it must actually round-trip real bytes on real disk.
 */
@DisplayName("LocalFileStorage")
class LocalFileStorageTest {

    @TempDir
    Path tempDir;

    private LocalFileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileStorage();
        ReflectionTestUtils.setField(storage, "basePath", tempDir.toString());
    }

    @Test
    @DisplayName("name returns 'local'")
    void name_returnsLocal() {
        assertThat(storage.name()).isEqualTo("local");
    }

    @Test
    @DisplayName("upload then download round-trips the same bytes")
    void uploadThenDownload_roundTrips() {
        byte[] conteudo = "hello world".getBytes();

        storage.upload("laudo/1/arquivo.txt", conteudo, "text/plain");
        byte[] baixado = storage.download("laudo/1/arquivo.txt");

        assertThat(baixado).isEqualTo(conteudo);
    }

    @Test
    @DisplayName("upload creates parent directories that do not exist yet")
    void upload_createsMissingParentDirectories() {
        storage.upload("a/b/c/arquivo.bin", new byte[]{1, 2, 3}, "application/octet-stream");

        Path esperado = tempDir.resolve("a").resolve("b").resolve("c").resolve("arquivo.bin");
        assertThat(Files.exists(esperado)).isTrue();
    }

    @Test
    @DisplayName("delete removes the file from disk")
    void delete_removesFile() {
        storage.upload("laudo/2/arquivo.txt", "conteudo".getBytes(), "text/plain");
        Path esperado = tempDir.resolve("laudo").resolve("2").resolve("arquivo.txt");
        assertThat(Files.exists(esperado)).isTrue();

        storage.delete("laudo/2/arquivo.txt");

        assertThat(Files.exists(esperado)).isFalse();
    }

    @Test
    @DisplayName("delete on a nonexistent key does not throw")
    void delete_nonexistentKey_doesNotThrow() {
        assertThatCode(() -> storage.delete("nao/existe/arquivo.txt")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a key containing '..' is rejected as path traversal")
    void key_withDotDotSegment_isRejected() {
        assertThatThrownBy(() -> storage.upload("../../etc/passwd", new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    @DisplayName("a key containing '..' is rejected on download too")
    void key_withDotDotSegment_isRejectedOnDownload() {
        assertThatThrownBy(() -> storage.download("foo/../../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    @DisplayName("a key containing '..' is rejected on delete too")
    void key_withDotDotSegment_isRejectedOnDelete() {
        assertThatThrownBy(() -> storage.delete("foo/../../secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path traversal");
    }

    @Test
    @DisplayName("blank key is rejected")
    void blankKey_isRejected() {
        assertThatThrownBy(() -> storage.upload("", new byte[]{1}, "text/plain"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
