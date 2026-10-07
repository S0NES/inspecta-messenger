package br.com.vistoria.messenger.signature.broker;

import java.util.List;
import java.util.UUID;

/**
 * Broker de assinatura digital — padrão genérico, igual ao {@code PaymentGateway}: cada broker
 * é um {@code @Component("<nome>")}, o {@code SignatureCreateConsumer} injeta o
 * {@code Map<String, SignatureBroker>} e seleciona o ativo por {@code inspecta.signature.broker}
 * ({@code SIGNATURE_BROKER}). Trocar o nome da config troca o broker sem mexer no fluxo.
 *
 * <p>Vive no {@code inspecta-messenger}: o inspecta-worker só prepara (resolve laudo + signatários, garante o
 * PDF) e enfileira {@code jobs.signature.create-document}; quem monta e envia a request pra
 * Autentique é o inspecta-messenger.</p>
 */
public interface SignatureBroker {

    String nome();

    /** Um signatário a enviar: {@code signatarioId} = id do {@code Signatario} local. */
    record SignerRef(UUID signatarioId, String nome) {}

    /** {@code externalSignerId} + {@code signingUrl} vêm do provider. */
    record SignerResult(UUID signatarioId, String externalSignerId, String signingUrl) {}

    record EnvioResult(String externalDocumentId, List<SignerResult> assinantes) {}

    /**
     * Evento de webhook normalizado. {@code tipo} é o tipo cru do provider
     * ({@code document.finished}, {@code signature.accepted}, {@code signature.rejected}, ...).
     * {@code externalSignerId} pode ser {@code null} em eventos de documento.
     */
    record WebhookEvent(String eventId, String tipo, String externalDocumentId, String externalSignerId) {}

    /** Cria o documento no provider com 1 signatário por {@code SignerRef}, capturando o link de cada um. */
    EnvioResult criarDocumento(String documentName, List<SignerRef> signers, byte[] pdf);

    /** Baixa o PDF final assinado. Só chamar quando o documento estiver concluído (document.finished). */
    byte[] baixarPdfAssinado(String externalDocumentId);

    /** Parseia o payload do webhook (autenticidade HMAC já validada na borda, no {@code WebhookController}). */
    WebhookEvent parseWebhook(String payload);
}
