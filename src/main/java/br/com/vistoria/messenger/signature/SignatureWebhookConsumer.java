package br.com.vistoria.messenger.signature;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.messenger.signature.broker.SignatureBroker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * q.signature-webhook &lt;- jobs.signature.webhook-received (publicado por
 * {@code AssinaturaService.receberWebhook} depois do dedup, na borda). O HMAC já foi validado no
 * {@code WebhookController}; aqui só parse + aplicação:
 *
 * <ul>
 *   <li>{@code document.finished} -&gt; baixa o PDF assinado na Autentique, registra {@code Arquivo}
 *       e conclui o laudo ({@code aplicarDocumentoAssinado}). Guard {@code documentoJaConcluido}.</li>
 *   <li>{@code signature.accepted}/{@code rejected} -&gt; {@code aplicarEventoAssinatura}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureWebhookConsumer {

    private final Map<String, SignatureBroker> brokers;
    private final AssinaturaService assinaturaService;
    private final ArquivoService arquivoService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.signature.broker:mock}")
    private String brokerAtivo;

    private SignatureBroker broker() {
        SignatureBroker b = brokers.get(brokerAtivo);
        if (b == null) throw new IllegalArgumentException("Broker de assinatura desconhecido: " + brokerAtivo);
        return b;
    }

    @RabbitListener(queues = "q.signature-webhook", containerFactory = "signatureListenerFactory")
    public void on(Message message) {
        JsonNode e = parse(message.getBody());
        String payload = e.path("payload").asText();
        SignatureBroker.WebhookEvent evt = broker().parseWebhook(payload);

        if ("document.finished".equals(evt.tipo())) {
            concluirDocumento(evt.externalDocumentId());
        } else {
            assinaturaService.aplicarEventoAssinatura(
                    evt.eventId(), evt.tipo(), evt.externalDocumentId(), evt.externalSignerId());
            log.info("[signature] evento {} aplicado documento={} signer={}",
                    evt.tipo(), evt.externalDocumentId(), evt.externalSignerId());
        }
    }

    private void concluirDocumento(String externalDocumentId) {
        if (assinaturaService.documentoJaConcluido(externalDocumentId)) {
            log.info("[signature] replay ignorado (documento {} ja concluido)", externalDocumentId);
            return;
        }
        byte[] pdfAssinado = broker().baixarPdfAssinado(externalDocumentId);
        String key = "autentique/" + externalDocumentId + "/assinado.pdf";
        Arquivo arquivo = arquivoService.registrar(key, "application/pdf", (long) pdfAssinado.length, "laudo-assinado", null, true);
        arquivoService.salvarConteudo(arquivo.getId(), pdfAssinado);
        assinaturaService.aplicarDocumentoAssinado(externalDocumentId, arquivo.getId());
        log.info("[signature] documento {} concluido, pdf assinado arquivo={}", externalDocumentId, arquivo.getId());
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode e = mapper.readTree(body);
            if (e.isTextual()) e = mapper.readTree(e.asText());
            return e;
        } catch (Exception ex) {
            throw new IllegalArgumentException("payload jobs.signature.webhook-received invalido", ex);
        }
    }
}
