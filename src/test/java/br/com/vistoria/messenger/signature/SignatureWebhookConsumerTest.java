package br.com.vistoria.messenger.signature;

import br.com.vistoria.core.modules.arquivo.Arquivo;
import br.com.vistoria.core.modules.arquivo.ArquivoService;
import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.messenger.signature.broker.SignatureBroker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureWebhookConsumer")
class SignatureWebhookConsumerTest {

    @Mock private SignatureBroker autentique;
    @Mock private AssinaturaService assinaturaService;
    @Mock private ArquivoService arquivoService;

    private SignatureWebhookConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SignatureWebhookConsumer(Map.of("autentique", autentique), assinaturaService, arquivoService);
        ReflectionTestUtils.setField(consumer, "brokerAtivo", "autentique");
    }

    private Message msg(String payload) {
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey("jobs.signature.webhook-received");
        return new Message(payload.getBytes(), p);
    }

    @Test
    @DisplayName("signature.accepted -> aplicarEventoAssinatura")
    void on_accepted() {
        when(autentique.parseWebhook(anyString())).thenReturn(
                new SignatureBroker.WebhookEvent("evt-1", "signature.accepted", "doc-1", "signer-1"));

        consumer.on(msg("{\"eventId\":\"evt-1\",\"payload\":\"{}\"}"));

        verify(assinaturaService).aplicarEventoAssinatura("evt-1", "signature.accepted", "doc-1", "signer-1");
    }

    @Test
    @DisplayName("document.finished -> baixa PDF, registra Arquivo, conclui")
    void on_finished() {
        when(autentique.parseWebhook(anyString())).thenReturn(
                new SignatureBroker.WebhookEvent("evt-2", "document.finished", "doc-1", null));
        when(assinaturaService.documentoJaConcluido("doc-1")).thenReturn(false);
        when(autentique.baixarPdfAssinado("doc-1")).thenReturn("pdf".getBytes());
        Arquivo arq = new Arquivo();
        arq.setId(UUID.randomUUID());
        when(arquivoService.registrar(anyString(), eq("application/pdf"), anyLong(), eq("laudo-assinado"), any(), eq(true)))
                .thenReturn(arq);

        consumer.on(msg("{\"eventId\":\"evt-2\",\"payload\":\"{}\"}"));

        verify(arquivoService).salvarConteudo(eq(arq.getId()), any());
        verify(assinaturaService).aplicarDocumentoAssinado("doc-1", arq.getId());
    }

    @Test
    @DisplayName("document.finished replay -> no-op")
    void on_finishedReplay() {
        when(autentique.parseWebhook(anyString())).thenReturn(
                new SignatureBroker.WebhookEvent("evt-2", "document.finished", "doc-1", null));
        when(assinaturaService.documentoJaConcluido("doc-1")).thenReturn(true);

        consumer.on(msg("{\"eventId\":\"evt-2\",\"payload\":\"{}\"}"));

        verify(autentique, never()).baixarPdfAssinado(anyString());
        verify(assinaturaService, never()).aplicarDocumentoAssinado(anyString(), any());
    }
}
