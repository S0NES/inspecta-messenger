package br.com.vistoria.messenger.engine;

import br.com.vistoria.core.modules.notify.Entrega;
import br.com.vistoria.core.modules.notify.MessageTemplate;
import br.com.vistoria.core.modules.notify.NotifyService;
import br.com.vistoria.messenger.adapters.BrokerAdapter;
import br.com.vistoria.messenger.adapters.ExpoAdapter;
import br.com.vistoria.messenger.adapters.InfobipAdapter;
import br.com.vistoria.messenger.adapters.ResendAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration tests for {@link Dispatcher} per broker/channel.
 * Tests that messages flow through the dispatcher, correct broker is selected,
 * template is rendered, adapter is called correctly, and NotifyService lifecycle
 * methods are invoked (iniciar, concluir, falhar) with proper status transitions.
 * Covers success scenarios, failures, retries, and channel-specific routing.
 */
class DispatcherBrokerIntegrationTest {

    private NotifyService notifyService;
    private ResendAdapter resend;
    private InfobipAdapter infobip;
    private ExpoAdapter expo;
    private Dispatcher dispatcher;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        resend = mock(ResendAdapter.class);
        infobip = mock(InfobipAdapter.class);
        expo = mock(ExpoAdapter.class);
        dispatcher = new Dispatcher(notifyService, resend, infobip, expo);
        when(notifyService.iniciar(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.of(new Entrega()));
    }

    private static final UUID TENANT_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TENANT_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TENANT_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID TENANT_4 = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID TENANT_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID TENANT_6 = UUID.fromString("00000000-0000-0000-0000-000000000006");

    private Dispatcher.Outbound outbound(String canal, String destinatario) {
        return new Dispatcher.Outbound("idem-1", canal, "tpl-1", destinatario,
                Map.of("nome", "Alice"), TENANT_5);
    }

    private MessageTemplate template(String canal, String assunto, String corpo) {
        MessageTemplate tpl = new MessageTemplate();
        tpl.setUuid(UUID.randomUUID());
        tpl.setCanal(canal);
        tpl.setAssunto(assunto);
        tpl.setCorpo(corpo);
        tpl.setAtivo(true);
        return tpl;
    }


    @Test
    void dispatchEmailSuccessfullyInvokesResendAndRecordsCompletion() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Bem-vindo {{nome}}", "Olá {{nome}}!")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-resend-id-123");

        dispatcher.dispatch(outbound("email", "alice@example.com"));

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());

        BrokerAdapter.Rendered rendered = captor.getValue();
        assertThat(rendered.canal()).isEqualTo("email");
        assertThat(rendered.destinatario()).isEqualTo("alice@example.com");
        assertThat(rendered.assunto()).isEqualTo("Bem-vindo Alice");
        assertThat(rendered.corpo()).isEqualTo("Olá Alice!");

        verify(notifyService).iniciar("idem-1", "email", "alice@example.com", "tpl-1", TENANT_5);
        verify(notifyService).concluir("idem-1", "ext-resend-id-123", 1);
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }

    @Test
    void dispatchEmailFailsAfterMaxRetriesAndRecordsFailure() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Message")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenThrow(new RuntimeException("SMTP connection failed"));

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email", "bob@example.com")))
                .isInstanceOf(IllegalStateException.class);

        verify(resend, times(3)).send(any());
        verify(notifyService).falhar("idem-1", 3, "SMTP connection failed");
        verify(notifyService, never()).concluir(anyString(), anyString(), anyInt());
    }

    @Test
    void dispatchEmailRecoveryOnSecondAttempt() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Body")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any()))
                .thenThrow(new RuntimeException("Transient failure"))
                .thenReturn("ext-resend-id-recovered");

        dispatcher.dispatch(outbound("email", "carol@example.com"));

        verify(resend, times(2)).send(any());
        verify(notifyService).concluir("idem-1", "ext-resend-id-recovered", 2);
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }


    @Test
    void dispatchSmsUsesMockWhenInfobipNotConfigured() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "sms"))
                .thenReturn(Optional.of(template("sms", null, "Seu codigo: {{codigo}}")));
        when(infobip.configurado()).thenReturn(false);

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "sms", "tpl-1",
                "+5511999999999", Map.of("codigo", "1234"), TENANT_2));

        verify(infobip, never()).send(any());
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(notifyService).concluir(eq("idem-1"), captor.capture(), eq(1));
        assertThat(captor.getValue()).startsWith("mock-"); // mock ID with UUID
        verify(notifyService).iniciar("idem-1", "sms", "+5511999999999", "tpl-1", TENANT_2);
    }

    @Test
    void dispatchSmsThrowsWhenInfobipConfiguredButStubNotImplemented() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "sms"))
                .thenReturn(Optional.of(template("sms", null, "Message {{id}}")));
        when(infobip.configurado()).thenReturn(true);
        when(infobip.send(any()))
                .thenThrow(new UnsupportedOperationException("Infobip SMS integration not implemented (stub)"));

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("sms", "+5511999999999")))
                .isInstanceOf(IllegalStateException.class);

        verify(infobip, times(3)).send(any()); // retries 3 times
        verify(notifyService).falhar(eq("idem-1"), eq(3), anyString());
    }


    @Test
    void dispatchPushSuccessfullyInvokesExpoAndRecordsCompletion() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "push"))
                .thenReturn(Optional.of(template("push", "Alert", "Click here: {{link}}")));
        when(expo.configurado()).thenReturn(true);
        when(expo.send(any())).thenReturn("expo-ticket-abc-123");

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "push", "tpl-1",
                "ExponentPushToken[token123]", Map.of("link", "https://app.example.com"), TENANT_3));

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(expo).send(captor.capture());

        BrokerAdapter.Rendered rendered = captor.getValue();
        assertThat(rendered.canal()).isEqualTo("push");
        assertThat(rendered.destinatario()).isEqualTo("ExponentPushToken[token123]");
        assertThat(rendered.assunto()).isEqualTo("Alert");
        assertThat(rendered.corpo()).isEqualTo("Click here: https://app.example.com");

        verify(notifyService).iniciar("idem-1", "push", "ExponentPushToken[token123]", "tpl-1", TENANT_3);
        verify(notifyService).concluir("idem-1", "expo-ticket-abc-123", 1);
    }

    @Test
    void dispatchPushFallsBackToMockWhenExpoNotConfigured() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "push"))
                .thenReturn(Optional.of(template("push", "Title", "Body")));
        when(expo.configurado()).thenReturn(false);

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "push", "tpl-1",
                "ExponentPushToken[user]", Map.of(), TENANT_4));

        verify(expo, never()).send(any());
        verify(notifyService).iniciar("idem-1", "push", "ExponentPushToken[user]", "tpl-1", TENANT_4);
        verify(notifyService).concluir(eq("idem-1"), anyString(), eq(1)); // mock adapter ID
    }


    @Test
    void dispatchWhatsappUsesUnknownChannelFallbackToMock() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "whatsapp"))
                .thenReturn(Optional.of(template("whatsapp", null, "WhatsApp message")));

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "whatsapp", "tpl-1",
                "+5511987654321", Map.of(), TENANT_6));

        verify(notifyService).iniciar("idem-1", "whatsapp", "+5511987654321", "tpl-1", TENANT_6);
        verify(notifyService).concluir(eq("idem-1"), anyString(), eq(1)); // mock adapter ID
    }


    @Test
    void dispatchIncludesCorrectRetryCountInCompletion() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Body")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any()))
                .thenThrow(new RuntimeException("Fail 1"))
                .thenThrow(new RuntimeException("Fail 2"))
                .thenReturn("ext-success-id");

        dispatcher.dispatch(outbound("email", "user@example.com"));

        verify(resend, times(3)).send(any());
        verify(notifyService).concluir("idem-1", "ext-success-id", 3); // third attempt succeeded
    }

    @Test
    void dispatchAllRetriesExhaustedRecordsCorrectErrorDetail() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Body")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenThrow(new RuntimeException("Final error message"));

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email", "user@example.com")))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(notifyService).falhar(eq("idem-1"), eq(3), errorCaptor.capture());
        assertThat(errorCaptor.getValue()).isEqualTo("Final error message");
    }


    @Test
    void dispatchRendersHandlebarsVariablesCorrectlyInAssuntoAndCorpo() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email",
                        "Ola {{nome}}, tudo bem?",
                        "Bem-vindo {{nome}}, sua idade eh {{idade}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-id");

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "email", "tpl-1",
                "user@example.com", Map.of("nome", "Maria", "idade", "25"), null));

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());

        BrokerAdapter.Rendered rendered = captor.getValue();
        assertThat(rendered.assunto()).isEqualTo("Ola Maria, tudo bem?");
        assertThat(rendered.corpo()).isEqualTo("Bem-vindo Maria, sua idade eh 25");
    }

    @Test
    void dispatchThrowsOnInvalidHandlebarsTemplate() {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Unclosed: {{nome")));

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email", "user@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template invalido");

        verify(notifyService, never()).iniciar(anyString(), anyString(), anyString(), anyString(), any());
    }


    @Test
    void dispatchHandlesNullTenantIdCorrectly() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Body")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-id");

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "email", "tpl-1",
                "user@example.com", Map.of(), null)); // tenantId is null

        verify(notifyService).iniciar("idem-1", "email", "user@example.com", "tpl-1", null);
    }

    @Test
    void dispatchHandlesEmptyVariablesMap() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Subject", "Body with no variables")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-id");

        dispatcher.dispatch(new Dispatcher.Outbound("idem-1", "email", "tpl-1",
                "user@example.com", Map.of(), TENANT_1));

        verify(notifyService).concluir("idem-1", "ext-id", 1);
    }
}
