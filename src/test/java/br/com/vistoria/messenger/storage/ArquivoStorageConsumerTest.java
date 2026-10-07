package br.com.vistoria.messenger.storage;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ArquivoStorageConsumer}. Mirrors the RabbitListener consumer test technique for
 * exercising the mode-switch resolver (ReflectionTestUtils to set the @Value-injected field
 * directly, no Spring context) plus the idempotency skip when arquivo.storageProvider is
 * already set.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ArquivoStorageConsumer")
class ArquivoStorageConsumerTest {

    @Mock private ArquivoService arquivoService;
    @Mock private FileStorage local;
    @Mock private FileStorage s3;

    private ArquivoStorageConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new ArquivoStorageConsumer(arquivoService, local, s3);
    }

    private static final UUID ARQUIVO_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ARQUIVO_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ARQUIVO_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ARQUIVO_4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID ARQUIVO_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private Message message(String json) {
        return new Message(json.getBytes(), new MessageProperties());
    }

    @Test
    @DisplayName("uploads content via local provider (default) and marks offload")
    void on_localProvider_uploadsAndMarksOffload() {
        ReflectionTestUtils.setField(consumer, "provider", "local");

        Arquivo arquivo = new Arquivo();
        arquivo.setId(ARQUIVO_1);
        arquivo.setMime("application/pdf");
        arquivo.setR2Key("laudo/1/uuid-pdf");
        arquivo.setStorageProvider(null);

        when(arquivoService.get(ARQUIVO_1)).thenReturn(arquivo);
        when(arquivoService.lerConteudo(ARQUIVO_1)).thenReturn(new byte[]{1, 2, 3});
        when(local.name()).thenReturn("local");

        consumer.on(message("{\"eventId\":\"upload-1\",\"arquivoId\":\"" + ARQUIVO_1 + "\"}"));

        verify(local).upload("laudo/1/uuid-pdf", new byte[]{1, 2, 3}, "application/pdf");
        verify(s3, never()).upload(anyString(), any(byte[].class), anyString());
        verify(arquivoService).marcarStorageOffload(ARQUIVO_1, "local", "laudo/1/uuid-pdf");
    }

    @Test
    @DisplayName("uploads content via s3 provider when inspecta.storage.provider=s3")
    void on_s3Provider_uploadsAndMarksOffload() {
        ReflectionTestUtils.setField(consumer, "provider", "s3");

        Arquivo arquivo = new Arquivo();
        arquivo.setId(ARQUIVO_2);
        arquivo.setMime("image/png");
        arquivo.setR2Key("laudo/2/uuid-png");
        arquivo.setStorageProvider(null);

        when(arquivoService.get(ARQUIVO_2)).thenReturn(arquivo);
        when(arquivoService.lerConteudo(ARQUIVO_2)).thenReturn(new byte[]{9, 9});
        when(s3.name()).thenReturn("s3");

        consumer.on(message("{\"eventId\":\"upload-2\",\"arquivoId\":\"" + ARQUIVO_2 + "\"}"));

        verify(s3).upload("laudo/2/uuid-png", new byte[]{9, 9}, "image/png");
        verify(local, never()).upload(anyString(), any(byte[].class), anyString());
        verify(arquivoService).marcarStorageOffload(ARQUIVO_2, "s3", "laudo/2/uuid-png");
    }

    @Test
    @DisplayName("idempotency guard: skips upload if storageProvider is already set (replay)")
    void on_alreadyOffloaded_skipsUpload() {
        ReflectionTestUtils.setField(consumer, "provider", "local");

        Arquivo arquivo = new Arquivo();
        arquivo.setId(ARQUIVO_3);
        arquivo.setMime("application/pdf");
        arquivo.setR2Key("laudo/3/uuid-pdf");
        arquivo.setStorageProvider("local");

        when(arquivoService.get(ARQUIVO_3)).thenReturn(arquivo);

        consumer.on(message("{\"eventId\":\"upload-3\",\"arquivoId\":\"" + ARQUIVO_3 + "\"}"));

        verify(arquivoService, never()).lerConteudo(any());
        verify(local, never()).upload(anyString(), any(byte[].class), anyString());
        verify(arquivoService, never()).marcarStorageOffload(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("invalid inspecta.storage.provider throws IllegalStateException")
    void on_invalidProvider_throws() {
        ReflectionTestUtils.setField(consumer, "provider", "azure");

        Arquivo arquivo = new Arquivo();
        arquivo.setId(ARQUIVO_4);
        arquivo.setStorageProvider(null);
        when(arquivoService.get(ARQUIVO_4)).thenReturn(arquivo);

        assertThatThrownBy(() -> consumer.on(message("{\"eventId\":\"upload-4\",\"arquivoId\":\"" + ARQUIVO_4 + "\"}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("azure");
    }

    @Test
    @DisplayName("unwraps double-JSON-encoded outbox payload")
    void on_doubleEncodedPayload_unwraps() throws Exception {
        ReflectionTestUtils.setField(consumer, "provider", "local");

        Arquivo arquivo = new Arquivo();
        arquivo.setId(ARQUIVO_5);
        arquivo.setMime("application/pdf");
        arquivo.setR2Key("laudo/5/uuid-pdf");
        arquivo.setStorageProvider(null);

        when(arquivoService.get(ARQUIVO_5)).thenReturn(arquivo);
        when(arquivoService.lerConteudo(ARQUIVO_5)).thenReturn(new byte[]{7});
        when(local.name()).thenReturn("local");

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String inner = "{\"eventId\":\"upload-5\",\"arquivoId\":\"" + ARQUIVO_5 + "\"}";
        String doubleEncoded = mapper.writeValueAsString(inner);

        assertThatCode(() -> consumer.on(message(doubleEncoded))).doesNotThrowAnyException();

        verify(local).upload("laudo/5/uuid-pdf", new byte[]{7}, "application/pdf");
        verify(arquivoService).marcarStorageOffload(ARQUIVO_5, "local", "laudo/5/uuid-pdf");
    }
}
