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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Dispatcher}: idempotency short-circuit, template resolution,
 * retry-with-backoff, and mock-fallback routing per channel. Collaborators
 * (NotifyService, adapters) are mocked so no real DB/HTTP is touched.
 */
class DispatcherTest {

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

    private Dispatcher.Outbound outbound(String canal) {
        return new Dispatcher.Outbound("idem-1", canal, "tpl-1", "dest@example.com",
                Map.of("nome", "Maria"), TENANT_1);
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
    void dispatchShortCircuitsOnIdempotentReplay() throws Exception {
        Entrega existing = new Entrega();
        existing.setStatus(Entrega.Status.enviado.name());
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.of(existing));

        dispatcher.dispatch(outbound("email"));

        verify(notifyService, never()).template(anyString(), anyString());
        verify(notifyService, never()).iniciar(anyString(), anyString(), anyString(), anyString(), any());
        verify(notifyService, never()).concluir(anyString(), anyString(), anyInt());
        verify(resend, never()).send(any());
    }

    @Test
    void dispatchIsOnlySentOnceAcrossTwoCallsWithSameIdempotencyKey() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new Entrega()));
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-1");

        dispatcher.dispatch(outbound("email"));
        dispatcher.dispatch(outbound("email"));

        verify(resend, times(1)).send(any());
        verify(notifyService, times(1)).concluir(eq("idem-1"), eq("ext-1"), eq(1));
    }

    @Test
    void dispatchThrowsWhenTemplateMissing() {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Template nao encontrado");

        verify(notifyService, never()).iniciar(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void dispatchRetriesThreeTimesThenRecordsFailure() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email")).thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenThrow(new RuntimeException("boom"));

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email")))
                .isInstanceOf(IllegalStateException.class);

        verify(resend, times(3)).send(any());
        verify(notifyService).falhar(eq("idem-1"), eq(3), eq("boom"));
        verify(notifyService, never()).concluir(anyString(), anyString(), anyInt());
    }

    @Test
    void dispatchRecoversAfterTransientFailureWithinRetryBudget() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email")).thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any()))
                .thenThrow(new RuntimeException("temp-fail"))
                .thenReturn("ext-999");

        dispatcher.dispatch(outbound("email"));

        verify(resend, times(2)).send(any());
        verify(notifyService).concluir("idem-1", "ext-999", 2);
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }

    /**
     * O send() do broker foi ok mas o concluir() seguinte lançou (ex.: perda momentânea do
     * DB). O e-mail já saiu — o Dispatcher NÃO pode re-enviar. Garante que concluir() está
     * fora do loop de retry: exatamente 1 send() e a exceção propaga para a mensagem voltar
     * pra fila (onde o guard porIdempotencyKey/entregas.idempotency_key barra a duplicata).
     */
    @Test
    void dispatchDoesNotResendWhenConcluirThrowsAfterSuccessfulSend() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-sent");
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(notifyService).concluir(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> dispatcher.dispatch(outbound("email")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db down");

        verify(resend, times(1)).send(any());
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }

    @Test
    void dispatchRendersHandlebarsSubjectAndBody() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Ola {{nome}}", "Bem-vindo, {{nome}}!")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-123");

        dispatcher.dispatch(outbound("email"));

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());
        assertThat(captor.getValue().assunto()).isEqualTo("Ola Maria");
        assertThat(captor.getValue().corpo()).isEqualTo("Bem-vindo, Maria!");

        verify(notifyService).iniciar("idem-1", "email", "dest@example.com", "tpl-1", TENANT_1);
        verify(notifyService).concluir("idem-1", "ext-123", 1);
    }

    /**
     * O template de e-mail do link de pagamento é semeado em
     * inspecta-core V5__notify_schema.sql e disparado pelo PaymentConsumer (inspecta-worker)
     * com as variáveis {valor, checkoutUrl}. Este teste amarra o renderizador do
     * inspecta-messenger ao contrato do seed: nada de {{...}} sobra e os valores entram no corpo.
     */
    @Test
    void pagamentoLinkTemplateRendersCheckoutUrlAndValor() throws Exception {
        String corpoSeed = "Sua cobranca de R$ {{valor}} esta pronta. Pague em: {{checkoutUrl}}";
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Link de pagamento — Inspecta", corpoSeed)));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-pay");

        var msg = new Dispatcher.Outbound("idem-1", "email", "tpl-1", "financeiro@viva.com",
                Map.of("valor", "350.00", "checkoutUrl", "https://pay.abacate/x/abc123"), TENANT_1);
        dispatcher.dispatch(msg);

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());
        assertThat(captor.getValue().corpo())
                .isEqualTo("Sua cobranca de R$ 350.00 esta pronta. Pague em: https://pay.abacate/x/abc123")
                .doesNotContain("{{");
    }

    /** Idem para pagamento-confirmado ({valor, solicitacaoId}), disparado por PagamentoService. */
    @Test
    void pagamentoConfirmadoTemplateRendersValorAndSolicitacao() throws Exception {
        String corpoSeed = "Pagamento de R$ {{valor}} confirmado para a solicitacao #{{solicitacaoId}}.";
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Pagamento confirmado — Inspecta", corpoSeed)));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-ok");

        var msg = new Dispatcher.Outbound("idem-1", "email", "tpl-1", "financeiro@viva.com",
                Map.of("valor", "350.00", "solicitacaoId", "1042"), TENANT_1);
        dispatcher.dispatch(msg);

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());
        assertThat(captor.getValue().corpo())
                .isEqualTo("Pagamento de R$ 350.00 confirmado para a solicitacao #1042.")
                .doesNotContain("{{");
    }

    /**
     * Template do onboarding do vistoriador espelhado na Pleno — semeado em
     * inspecta-core V29__pleno_vistoriador_template.sql, disparado pelo
     * PlenoWriteClientHttp (inspecta-worker) com {nome, portalUrl}. Amarra o renderizador ao seed.
     */
    @Test
    void vistoriadorPlenoCriadoTemplateRendersNomeAndPortalUrl() throws Exception {
        String corpoSeed = "Ola {{nome}}, criamos seu acesso de vistoriador na Pleno Sistemas. "
                + "Defina sua senha de acesso em: {{portalUrl}}";
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", "Seu acesso de vistoriador na Pleno — Inspecta", corpoSeed)));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-pleno");

        var msg = new Dispatcher.Outbound("idem-1", "email", "tpl-1", "vist@imob.com",
                Map.of("nome", "Vitor", "portalUrl", "https://sub.sistemaspleno.com/api/portalDoCliente/usuario/recuperarSenha"), TENANT_1);
        dispatcher.dispatch(msg);

        ArgumentCaptor<BrokerAdapter.Rendered> captor = ArgumentCaptor.forClass(BrokerAdapter.Rendered.class);
        verify(resend).send(captor.capture());
        assertThat(captor.getValue().corpo())
                .isEqualTo("Ola Vitor, criamos seu acesso de vistoriador na Pleno Sistemas. "
                        + "Defina sua senha de acesso em: https://sub.sistemaspleno.com/api/portalDoCliente/usuario/recuperarSenha")
                .doesNotContain("{{");
    }

    @Test
    void dispatchFallsBackToMockWhenResendNotConfigured() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email")).thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(false);

        dispatcher.dispatch(outbound("email"));

        verify(resend, never()).send(any());
        verify(notifyService).concluir(eq("idem-1"), startsWith("mock-"), eq(1));
    }

    @Test
    void dispatchFallsBackToMockWhenInfobipNotConfigured() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "sms")).thenReturn(Optional.of(template("sms", null, "Codigo: {{nome}}")));
        when(infobip.configurado()).thenReturn(false);

        dispatcher.dispatch(outbound("sms"));

        verify(infobip, never()).send(any());
        verify(notifyService).concluir(eq("idem-1"), startsWith("mock-"), eq(1));
    }

    @Test
    void dispatchFallsBackToMockWhenExpoNotConfigured() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "push")).thenReturn(Optional.of(template("push", null, "Push: {{nome}}")));
        when(expo.configurado()).thenReturn(false);

        dispatcher.dispatch(outbound("push"));

        verify(expo, never()).send(any());
        verify(notifyService).concluir(eq("idem-1"), startsWith("mock-"), eq(1));
    }

    @Test
    void dispatchUsesMockForUnknownChannelRegardlessOfAdapters() {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "whatsapp")).thenReturn(Optional.of(template("whatsapp", null, "Ola {{nome}}")));

        dispatcher.dispatch(outbound("whatsapp"));

        verify(notifyService).concluir(eq("idem-1"), startsWith("mock-"), eq(1));
        verifyNoInteractions(resend, infobip, expo);
    }

    @Test
    void dispatchSkipsSendWhenIniciarSignalsCollision() {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email")).thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(notifyService.iniciar(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Optional.empty());

        dispatcher.dispatch(outbound("email"));

        verifyNoInteractions(resend, infobip, expo);
        verify(notifyService, never()).concluir(anyString(), anyString(), anyInt());
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }

    /**
     * T4.3's actual target: a concurrent at-least-once redelivery of the same
     * idempotencyKey (e.g. two listener threads, or a redelivery racing the original
     * delivery, both past the porIdempotencyKey replay check because neither has
     * committed yet). Every caller races into iniciar(); the DB's unique constraint on
     * idempotency_key lets exactly one caller win (NotifyService.iniciar() catches
     * DataIntegrityViolationException and returns Optional.empty() to every loser).
     * This test simulates that unique-constraint race deterministically with an
     * AtomicBoolean compare-and-set (no real DB, no sleeps, no timing dependence) and
     * asserts the control-flow contract: however many threads race, the broker is sent
     * to exactly once and notifyService.concluir() is recorded exactly once.
     */
    @Test
    void dispatchSendsExactlyOnceUnderConcurrentRedeliveryOfSameIdempotencyKey() throws Exception {
        when(notifyService.porIdempotencyKey("idem-1")).thenReturn(Optional.empty());
        when(notifyService.template("tpl-1", "email"))
                .thenReturn(Optional.of(template("email", null, "Ola {{nome}}")));
        when(resend.configurado()).thenReturn(true);
        when(resend.send(any())).thenReturn("ext-race-winner");

        AtomicBoolean rowClaimed = new AtomicBoolean(false);
        when(notifyService.iniciar(anyString(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> rowClaimed.compareAndSet(false, true)
                        ? Optional.of(new Entrega())
                        : Optional.empty());

        int concurrentRedeliveries = 8;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentRedeliveries);
        CountDownLatch allThreadsReady = new CountDownLatch(concurrentRedeliveries);
        CountDownLatch releaseAllAtOnce = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < concurrentRedeliveries; i++) {
                futures.add(pool.submit(() -> {
                    allThreadsReady.countDown();
                    try {
                        releaseAllAtOnce.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    dispatcher.dispatch(outbound("email"));
                }));
            }

            assertThat(allThreadsReady.await(5, TimeUnit.SECONDS)).isTrue();
            releaseAllAtOnce.countDown();

            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        verify(resend, times(1)).send(any());
        verify(notifyService, times(1)).concluir("idem-1", "ext-race-winner", 1);
        verify(notifyService, never()).falhar(anyString(), anyInt(), anyString());
    }
}
