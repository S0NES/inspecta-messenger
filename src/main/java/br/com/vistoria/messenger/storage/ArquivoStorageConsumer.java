package br.com.vistoria.messenger.storage;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * q.storage <- jobs.storage.upload-requested (ArquivoService.salvarConteudo publica pra
 * TODO tipo de arquivo, nao so imagem). Espelha o padrao mock/http dos consumidores de integracao:
 * selecao por {@code inspecta.storage.provider}, "local" default.
 *
 * Idempotencia: arquivo.storageProvider != null significa que ja foi ofertado -> skip
 * (evita reupload redundante em redelivery at-least-once).
 */
@Component
@Slf4j
public class ArquivoStorageConsumer {

    private final ArquivoService arquivoService;
    private final FileStorage local;
    private final FileStorage s3;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.storage.provider:local}") private String provider;

    public ArquivoStorageConsumer(ArquivoService arquivoService,
                                   @Qualifier("local") FileStorage local,
                                   @Qualifier("s3") FileStorage s3) {
        this.arquivoService = arquivoService;
        this.local = local;
        this.s3 = s3;
    }

    private FileStorage storage() {
        if ("s3".equals(provider)) return s3;
        if ("local".equals(provider)) return local;
        throw new IllegalStateException("Invalid inspecta.storage.provider: '" + provider + "'. Valid values: s3, local");
    }

    @RabbitListener(queues = "q.storage", containerFactory = "storageListenerFactory")
    public void on(Message message) {
        JsonNode e = parse(message.getBody());
        UUID arquivoId = UUID.fromString(e.path("arquivoId").asText());

        Arquivo arquivo = arquivoService.get(arquivoId);
        if (arquivo.getStorageProvider() != null) {
            log.info("[storage] replay ignorado (arquivo {} ja ofertado em {})", arquivoId, arquivo.getStorageProvider());
            return;
        }

        byte[] bytes = arquivoService.lerConteudo(arquivoId);
        storage().upload(arquivo.getR2Key(), bytes, arquivo.getMime());
        arquivoService.marcarStorageOffload(arquivoId, storage().name(), arquivo.getR2Key());
        log.info("[storage] arquivo {} ofertado em {} key={}", arquivoId, storage().name(), arquivo.getR2Key());
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode e = mapper.readTree(body);
            // Quirk de double-JSON-encoding do outbox relay (mesmo tratamento do NotifyConsumer):
            // se o node parseado for uma string, parseia de novo.
            if (e.isTextual()) {
                e = mapper.readTree(e.asText());
            }
            return e;
        } catch (Exception ex) {
            throw new IllegalArgumentException("payload jobs.storage invalido", ex);
        }
    }
}
