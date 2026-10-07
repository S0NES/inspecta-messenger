package br.com.vistoria.messenger.engine;

import br.com.vistoria.core.modules.notify.MessageTemplate;
import br.com.vistoria.core.modules.notify.NotifyService;
import br.com.vistoria.messenger.adapters.*;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class Dispatcher {

    private static final int MAX_TENTATIVAS = 3;

    private final NotifyService notifyService;
    private final ResendAdapter resend;
    private final InfobipAdapter infobip;
    private final ExpoAdapter expo;
    private final Handlebars handlebars = new Handlebars();

    public record Outbound(String idempotencyKey, String canal, String templateId,
                           String destinatario, Map<String, Object> variaveis, UUID tenantId) {}

    private BrokerAdapter brokerFor(String canal) {
        return switch (canal) {
            case "email" -> resend.configurado() ? resend : new MockAdapter("email");
            case "sms" -> infobip.configurado() ? infobip : new MockAdapter("sms");
            case "push" -> expo.configurado() ? expo : new MockAdapter("push");
            default -> new MockAdapter(canal);
        };
    }

    public void dispatch(Outbound msg) {
        var existing = notifyService.porIdempotencyKey(msg.idempotencyKey());
        if (existing.isPresent()) {
            log.info("[notify] replay idempotencyKey={} status={}", msg.idempotencyKey(), existing.get().getStatus());
            return;
        }

        MessageTemplate tpl = notifyService.template(msg.templateId(), msg.canal())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template nao encontrado ou inativo: " + msg.templateId() + "/" + msg.canal()));

        String assunto = tpl.getAssunto() == null ? null : render(tpl.getAssunto(), msg.variaveis());
        String corpo = render(tpl.getCorpo(), msg.variaveis());
        var rendered = new BrokerAdapter.Rendered(msg.canal(), msg.destinatario(), assunto, corpo,
                msg.variaveis(), msg.idempotencyKey());

        var entrega = notifyService.iniciar(msg.idempotencyKey(), msg.canal(), msg.destinatario(), msg.templateId(), msg.tenantId());
        if (entrega.isEmpty()) {
            log.info("[notify] colisao idempotencyKey={} - iniciar() perdeu a corrida, ignorando", msg.idempotencyKey());
            return;
        }

        BrokerAdapter broker = brokerFor(msg.canal());
        String externalId = null;
        Exception lastError = null;
        int tentativasFeitas = 0;
        // O loop re-tenta APENAS o send(). O provider recebe a Idempotency-Key (quando
        // suporta), entao re-tentar apos um timeout ou 5xx nao duplica o envio. O concluir()
        // fica FORA do loop: se ele falhar, o e-mail ja saiu — re-enviar seria o bug que
        // gerava "3 e-mails seguidos" no log do Resend.
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS; tentativa++) {
            tentativasFeitas = tentativa;
            try {
                externalId = broker.send(rendered);
                lastError = null;
                break;
            } catch (Exception err) {
                lastError = err;
                if (tentativa < MAX_TENTATIVAS) sleep(200L * tentativa);
            }
        }

        if (lastError != null) {
            notifyService.falhar(msg.idempotencyKey(), MAX_TENTATIVAS, lastError.getMessage());
            throw new IllegalStateException("Envio falhou apos " + MAX_TENTATIVAS + " tentativas", lastError);
        }

        notifyService.concluir(msg.idempotencyKey(), externalId, tentativasFeitas);
    }

    private String render(String source, Map<String, Object> vars) {
        try {
            Template t = handlebars.compileInline(source);
            return t.apply(vars);
        } catch (Exception e) {
            throw new IllegalArgumentException("Template invalido", e);
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }
}
