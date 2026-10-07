package br.com.vistoria.messenger.signature;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * q.signature-create &lt;- jobs.signature.create-document (publicado pelo inspecta-worker depois do
 * preparo). Monta e envia o {@code createDocument} multipart pra Autentique, e grava
 * {@code external_signer_id}/{@code external_document_id}/{@code signing_url} por signatário
 * ({@code AssinaturaService.vincularDocumento}, que dispara o e-mail com o link).
 *
 * <p>A chamada HTTP roda fora de transação; cada {@code vincularDocumento} abre a sua.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SignatureCreateConsumer {

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

    @RabbitListener(queues = "q.signature-create", containerFactory = "signatureListenerFactory")
    public void on(Message message) {
        JsonNode e = parse(message.getBody());
        UUID pdfArquivoId = UUID.fromString(e.path("pdfArquivoId").asText());
        String documentName = e.path("documentName").asText();

        List<SignatureBroker.SignerRef> signers = new ArrayList<>();
        for (JsonNode s : e.path("signers")) {
            signers.add(new SignatureBroker.SignerRef(
                    UUID.fromString(s.path("signatarioId").asText()), s.path("nome").asText()));
        }
        if (signers.isEmpty()) {
            log.warn("[signature] create-document sem signers - ignorado ({})", documentName);
            return;
        }

        byte[] pdf = arquivoService.lerConteudo(pdfArquivoId);
        SignatureBroker.EnvioResult r = broker().criarDocumento(documentName, signers, pdf);

        r.assinantes().forEach(a -> assinaturaService.vincularDocumento(
                a.signatarioId(), a.externalSignerId(), r.externalDocumentId(), a.signingUrl()));

        log.info("[signature] createDocument '{}' -> documentId={} assinantes={}",
                documentName, r.externalDocumentId(), r.assinantes().size());
    }

    private JsonNode parse(byte[] body) {
        try {
            JsonNode e = mapper.readTree(body);
            if (e.isTextual()) e = mapper.readTree(e.asText());   // double-JSON-encoding do outbox relay
            return e;
        } catch (Exception ex) {
            throw new IllegalArgumentException("payload jobs.signature.create-document invalido", ex);
        }
    }
}
