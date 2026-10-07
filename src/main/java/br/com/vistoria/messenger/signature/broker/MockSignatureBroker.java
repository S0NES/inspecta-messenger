package br.com.vistoria.messenger.signature.broker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Default (inspecta.signature.broker=mock) — nenhuma chamada externa. */
@Component("mock")
@Slf4j
public class MockSignatureBroker implements SignatureBroker {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String nome() { return "mock"; }

    @Override
    public EnvioResult criarDocumento(String documentName, List<SignerRef> signers, byte[] pdf) {
        String externalDocumentId = "mock-doc-" + UUID.randomUUID();
        List<SignerResult> assinantes = signers.stream()
                .map(s -> new SignerResult(s.signatarioId(), "mock-signer-" + UUID.randomUUID(),
                        "https://mock.assinatura/" + s.signatarioId()))
                .toList();
        log.info("[signature-mock] criarDocumento '{}' -> documentId={} assinantes={}",
                documentName, externalDocumentId, assinantes.size());
        return new EnvioResult(externalDocumentId, assinantes);
    }

    @Override
    public byte[] baixarPdfAssinado(String externalDocumentId) {
        log.info("[signature-mock] baixarPdfAssinado {}", externalDocumentId);
        return ("mock-pdf-assinado-" + externalDocumentId).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public WebhookEvent parseWebhook(String payload) {
        try {
            JsonNode event = mapper.readTree(payload).path("event");
            String eventId = event.path("id").asText(null);
            String tipo = event.path("type").asText(null);
            if (eventId == null || tipo == null)
                throw new IllegalArgumentException("payload mock exige event.id e event.type");
            JsonNode data = event.path("data");
            JsonNode obj = data.path("object").isObject() ? data.path("object") : data;
            String doc = obj.path("document").asText(obj.path("id").asText(data.path("id").asText(null)));
            String signer = obj.path("public_id").asText(data.path("public_id").asText(null));
            return new WebhookEvent(eventId, tipo, doc,
                    signer == null ? null : signer.replace("-", "").toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload de webhook mock invalido", e);
        }
    }
}
