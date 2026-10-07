package br.com.vistoria.messenger.adapters;

import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Slf4j
public class MockAdapter implements BrokerAdapter {

    private final String canal;

    public MockAdapter(String canal) { this.canal = canal; }

    @Override public String name() { return "mock-" + canal; }

    @Override
    public String send(Rendered msg) {
        log.info("[notify-mock:{}] para={} assunto=\"{}\" corpo=\"{}\"",
                canal, msg.destinatario(), msg.assunto() == null ? "" : msg.assunto(), msg.corpo());
        return "mock-" + UUID.randomUUID();
    }
}
