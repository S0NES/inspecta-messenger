package br.com.vistoria.messenger.signature;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Falha rápido no boot se {@code inspecta.signature.broker} ({@code SIGNATURE_BROKER}) não bater com
 * um bean {@code SignatureBroker} conhecido — em vez de esperar a primeira request quebrar.
 */
@Component
@Slf4j
public class SignatureBrokerValidator implements ApplicationRunner {

    private static final Set<String> VALIDOS = Set.of("mock", "autentique");

    @Value("${inspecta.signature.broker:mock}")
    private String broker;

    @Override
    public void run(ApplicationArguments args) {
        if (!VALIDOS.contains(broker)) {
            throw new IllegalStateException(
                    "Invalid inspecta.signature.broker: '" + broker + "'. Valid values are: autentique, mock");
        }
        log.info("Signature broker validation passed: broker={}", broker);
    }
}
