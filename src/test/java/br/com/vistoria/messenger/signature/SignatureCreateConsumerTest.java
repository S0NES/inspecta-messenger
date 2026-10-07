package br.com.vistoria.messenger.signature;

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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SignatureCreateConsumer")
class SignatureCreateConsumerTest {

    @Mock private SignatureBroker autentique;
    @Mock private AssinaturaService assinaturaService;
    @Mock private ArquivoService arquivoService;

    private SignatureCreateConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SignatureCreateConsumer(Map.of("autentique", autentique), assinaturaService, arquivoService);
        ReflectionTestUtils.setField(consumer, "brokerAtivo", "autentique");
    }

    private Message msg(String payload) {
        MessageProperties p = new MessageProperties();
        p.setReceivedRoutingKey("jobs.signature.create-document");
        return new Message(payload.getBytes(), p);
    }

    @Test
    @DisplayName("lê o PDF, chama o broker e vincula cada assinante")
    void on_criaEVincula() {
        UUID pdfId = UUID.randomUUID();
        UUID sig1 = UUID.randomUUID();
        when(arquivoService.lerConteudo(pdfId)).thenReturn("%PDF".getBytes());
        when(autentique.criarDocumento(eq("Laudo x"), any(), any()))
                .thenReturn(new SignatureBroker.EnvioResult("doc-1",
                        List.of(new SignatureBroker.SignerResult(sig1, "signer-1", "https://assina/1"))));

        consumer.on(msg("{\"pdfArquivoId\":\"" + pdfId + "\",\"documentName\":\"Laudo x\","
                + "\"signers\":[{\"signatarioId\":\"" + sig1 + "\",\"nome\":\"Maria\"}]}"));

        verify(assinaturaService).vincularDocumento(sig1, "signer-1", "doc-1", "https://assina/1");
    }

    @Test
    @DisplayName("sem signers -> no-op")
    void on_semSigners() {
        UUID pdfId = UUID.randomUUID();
        consumer.on(msg("{\"pdfArquivoId\":\"" + pdfId + "\",\"documentName\":\"L\",\"signers\":[]}"));
        verify(arquivoService, never()).lerConteudo(any());
    }
}
