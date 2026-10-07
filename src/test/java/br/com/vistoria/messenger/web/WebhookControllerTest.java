package br.com.vistoria.messenger.web;

import br.com.vistoria.core.modules.assinatura.AssinaturaService;
import br.com.vistoria.core.modules.financeiro.PagamentoService;
import br.com.vistoria.core.modules.notify.NotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WebhookController}: verifies that webhook endpoints
 * correctly validate signatures, parse event payloads, and delegate to the
 * right service. Covers the messaging brokers (resend/infobip/expo via
 * NotifyService) and the third-party edge webhooks moved here from the inspecta-api
 * (autentique signature webhook, payment provider webhook).
 */
class WebhookControllerTest {

    private NotifyService notifyService;
    private WebhookSignatureVerifier signatureVerifier;
    private AutentiqueSignatureVerifier autentiqueVerifier;
    private AbacatePaySignatureVerifier abacatePayVerifier;
    private AssinaturaService assinaturaService;
    private PagamentoService pagamentoService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        signatureVerifier = mock(WebhookSignatureVerifier.class);
        autentiqueVerifier = mock(AutentiqueSignatureVerifier.class);
        abacatePayVerifier = mock(AbacatePaySignatureVerifier.class);
        assinaturaService = mock(AssinaturaService.class);
        pagamentoService = mock(PagamentoService.class);
        controller = new WebhookController(notifyService, signatureVerifier, autentiqueVerifier,
                abacatePayVerifier, assinaturaService, pagamentoService);
    }


    @Test
    void webhookRejects401WhenSignatureInvalid() {
        String payload = "{\"id\": \"evt-123\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of("svix-signature", "v1,invalid-signature");

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(false);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(notifyService, never()).registrarWebhook(anyString(), anyString());
    }

    @Test
    void webhookRejects401WhenSignatureMissing() {
        String payload = "{\"id\": \"evt-123\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of("Content-Type", "application/json");

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(false);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(notifyService, never()).registrarWebhook(anyString(), anyString());
    }

    @Test
    void webhookAccepts200WhenSignatureValid() {
        String payload = "{\"id\": \"evt-456\", \"status\": \"bounce\"}";
        Map<String, String> headers = Map.of("svix-signature", "v1,valid-sig");

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-456")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService).registrarWebhook("resend", "evt-456");
    }


    @Test
    void webhookExtractsIdFromResendPayload() {
        String payload = "{\"id\": \"resend-evt-789\", \"status\": \"delivered\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "resend-evt-789")).thenReturn(true);

        controller.webhook("resend", payload, headers);

        verify(notifyService).registrarWebhook("resend", "resend-evt-789");
    }

    @Test
    void webhookExtractsEventIdFromExpoPayload() {
        String payload = "{\"event_id\": \"expo-evt-111\", \"type\": \"push_notification_delivered\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("expo", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("expo", "expo-evt-111")).thenReturn(true);

        controller.webhook("expo", payload, headers);

        verify(notifyService).registrarWebhook("expo", "expo-evt-111");
    }

    @Test
    void webhookExtractsMessageIdFromInfobipPayload() {
        String payload = "{\"messageId\": \"infobip-msg-222\", \"type\": \"DELIVERY\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("infobip", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("infobip", "infobip-msg-222")).thenReturn(true);

        controller.webhook("infobip", payload, headers);

        verify(notifyService).registrarWebhook("infobip", "infobip-msg-222");
    }

    @Test
    void webhookReturnsOkWhenNoEventIdFound() {
        String payload = "{\"status\": \"processed\"}"; // no id, event_id, or messageId
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService, never()).registrarWebhook(anyString(), anyString());
    }

    @Test
    void webhookReturnsOkWhenEventIdIsBlank() {
        String payload = "{\"id\": \"\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService, never()).registrarWebhook(anyString(), anyString());
    }

    @Test
    void webhookReturnsOkWhenWebhookNotNew() {
        String payload = "{\"id\": \"evt-duplicate\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-duplicate")).thenReturn(false);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService, never()).atualizarStatusPorExternalId(anyString(), anyString());
    }


    @Test
    void webhookUpdatesStatusWhenExternalIdAndStatusFound() {
        String payload = "{\"id\": \"evt-123\", \"externalId\": \"ext-msg-456\", \"status\": \"delivered\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-123")).thenReturn(true);

        controller.webhook("resend", payload, headers);

        verify(notifyService).atualizarStatusPorExternalId("ext-msg-456", "delivered");
    }

    @Test
    void webhookUpdatesStatusWithTypeFieldForExpo() {
        String payload = "{\"event_id\": \"evt-expo-123\", \"messageId\": \"msg-456\", \"type\": \"success\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("expo", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("expo", "evt-expo-123")).thenReturn(true);

        controller.webhook("expo", payload, headers);

        verify(notifyService).atualizarStatusPorExternalId("msg-456", "success");
    }

    @Test
    void webhookExtractsEmailIdFromDataPath() {
        String payload = "{\"messageId\": \"evt-789\", \"data\": {\"email_id\": \"ext-email-123\"}, \"status\": \"bounce\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-789")).thenReturn(true);

        controller.webhook("resend", payload, headers);

        verify(notifyService).atualizarStatusPorExternalId("evt-789", "bounce");
    }

    @Test
    void webhookUsesIdAsExternalIdWhenExternalIdFieldMissing() {
        String payload = "{\"id\": \"evt-123\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-123")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService).atualizarStatusPorExternalId("evt-123", "sent");
    }

    @Test
    void webhookReturnsOkWhenMissingStatus() {
        String payload = "{\"id\": \"evt-123\", \"externalId\": \"ext-456\"}"; // no status
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-123")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService, never()).atualizarStatusPorExternalId(anyString(), anyString());
    }


    @Test
    void webhookReturnsOkWhenPayloadIsInvalidJson() {
        String payload = "{ invalid json }}}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService, never()).registrarWebhook(anyString(), anyString());
    }

    @Test
    void webhookReturnsOkAndRecoverableWhenNotifyServiceThrows() {
        String payload = "{\"id\": \"evt-123\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "evt-123"))
                .thenThrow(new RuntimeException("Database error"));

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }


    @Test
    void webhookHandlesResendBounceEvent() {
        String payload = "{\"id\": \"resend-bounce-123\", \"externalId\": \"ext-123\", \"status\": \"bounce\", \"type\": \"bounce\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "resend-bounce-123")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("resend", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService).atualizarStatusPorExternalId("ext-123", "bounce");
    }

    @Test
    void webhookHandlesExpoPushDeliveredEvent() {
        String payload = "{\"event_id\": \"expo-delivered-456\", \"messageId\": \"ticket-456\", \"type\": \"success\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("expo", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("expo", "expo-delivered-456")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("expo", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService).atualizarStatusPorExternalId("ticket-456", "success");
    }

    @Test
    void webhookHandlesInfobipDeliveryEvent() {
        String payload = "{\"messageId\": \"infobip-789\", \"status\": \"DELIVERED\", \"sentAt\": \"2024-01-01T00:00:00Z\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("infobip", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("infobip", "infobip-789")).thenReturn(true);

        ResponseEntity<Void> response = controller.webhook("infobip", payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(notifyService).atualizarStatusPorExternalId(anyString(), eq("DELIVERED"));
    }

    @Test
    void webhookPrefersPrioritizedFieldsForExtraction() {
        String payload = "{\"id\": \"preferred-id\", \"event_id\": \"second-id\", \"status\": \"sent\"}";
        Map<String, String> headers = Map.of();

        when(signatureVerifier.verifySignature("resend", payload, headers)).thenReturn(true);
        when(notifyService.registrarWebhook("resend", "preferred-id")).thenReturn(true);

        controller.webhook("resend", payload, headers);

        verify(notifyService).registrarWebhook("resend", "preferred-id");
    }


    @Test
    void autentiqueWebhookProcessesWhenHmacValid() {
        String payload = "{\"event\":{\"id\":\"evt_123\",\"type\":\"signature.accepted\"}}";
        Map<String, String> headers = Map.of("x-autentique-signature", "deadbeef");
        when(autentiqueVerifier.verify(payload, "deadbeef")).thenReturn(true);

        ResponseEntity<Map<String, String>> response = controller.webhookAutentique(payload, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ok");
        verify(assinaturaService).receberWebhook(eq(payload), anyMap());
    }

    @Test
    void autentiqueWebhookRejects401WhenHmacInvalid() {
        String payload = "{\"event\":{\"id\":\"evt_123\"}}";
        when(autentiqueVerifier.verify(anyString(), any())).thenReturn(false);

        ResponseEntity<Map<String, String>> response =
                controller.webhookAutentique(payload, Map.of("x-autentique-signature", "bogus"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(assinaturaService, never()).receberWebhook(anyString(), anyMap());
    }

    @Test
    void autentiqueWebhookRejects401WhenSignatureHeaderMissing() {
        // verifier default (Mockito) devolve false -> fail closed
        ResponseEntity<Map<String, String>> response =
                controller.webhookAutentique("{\"event\":{\"id\":\"evt_123\"}}", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(assinaturaService, never()).receberWebhook(anyString(), anyMap());
    }


    @Test
    void pagamentoWebhookExtractsEventIdAndForwards() {
        String payload = "{\"eventId\": \"evt_123\", \"status\": \"completed\"}";
        Map<String, String> headers = Map.of("content-type", "application/json");

        ResponseEntity<Map<String, String>> response =
                controller.webhookPagamento("stripe", payload, null, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "ok");
        verify(pagamentoService).receberWebhook(eq("stripe"), eq("evt_123"), eq(payload), anyMap());
    }

    @Test
    void pagamentoWebhookFallsBackFromEventIdToEventUnderscoreIdToId() {
        controller.webhookPagamento("stripe", "{\"event_id\": \"evt_snake\"}", null, Map.of());
        verify(pagamentoService).receberWebhook(eq("stripe"), eq("evt_snake"), anyString(), anyMap());

        controller.webhookPagamento("square", "{\"id\": \"evt_plain\"}", null, Map.of());
        verify(pagamentoService).receberWebhook(eq("square"), eq("evt_plain"), anyString(), anyMap());
    }

    @Test
    void pagamentoWebhookInvalidJsonForwardsNullEventId() {
        ResponseEntity<Map<String, String>> response =
                controller.webhookPagamento("square", "invalid json", null, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Payload nao-JSON nao pode derrubar o webhook: o provider reenviaria em loop.
        verify(pagamentoService).receberWebhook(eq("square"), isNull(), eq("invalid json"), anyMap());
    }

    @Test
    void pagamentoWebhookAbacatepayValidSecretIsProcessed() {
        when(abacatePayVerifier.verify("s3cr3t")).thenReturn(true);
        String payload = "{\"id\": \"evt_ab\", \"event\": \"checkout.completed\"}";

        ResponseEntity<Map<String, String>> response =
                controller.webhookPagamento("abacatepay", payload, "s3cr3t", Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(pagamentoService).receberWebhook(eq("abacatepay"), eq("evt_ab"), anyString(), anyMap());
    }

    @Test
    void pagamentoWebhookAbacatepayBadSecretReturns401() {
        when(abacatePayVerifier.verify(any())).thenReturn(false);

        ResponseEntity<Map<String, String>> bad =
                controller.webhookPagamento("abacatepay", "{\"id\": \"evt_ab\"}", "wrong", Map.of());
        assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // case-insensitive no path tambem e barrado
        ResponseEntity<Map<String, String>> badCase =
                controller.webhookPagamento("AbacatePay", "{\"id\": \"evt_ab\"}", null, Map.of());
        assertThat(badCase.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        verify(pagamentoService, never()).receberWebhook(anyString(), any(), anyString(), anyMap());
    }

    @Test
    void pagamentoWebhookOtherProviderIsNotSignatureChecked() {
        ResponseEntity<Map<String, String>> response =
                controller.webhookPagamento("stripe", "{\"id\": \"evt_other\"}", null, Map.of());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(abacatePayVerifier, never()).verify(any());
        verify(pagamentoService).receberWebhook(eq("stripe"), eq("evt_other"), anyString(), anyMap());
    }
}
