package br.com.vistoria.messenger.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("SignatureBrokerValidator")
class SignatureBrokerValidatorTest {

    private final ApplicationArguments args = mock(ApplicationArguments.class);

    private SignatureBrokerValidator with(String broker) {
        SignatureBrokerValidator v = new SignatureBrokerValidator();
        ReflectionTestUtils.setField(v, "broker", broker);
        return v;
    }

    @Test
    void aceitaMockEAutentique() {
        assertThatCode(() -> with("mock").run(args)).doesNotThrowAnyException();
        assertThatCode(() -> with("autentique").run(args)).doesNotThrowAnyException();
    }

    @Test
    void rejeitaDesconhecido() {
        assertThatThrownBy(() -> with("assinafy").run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid inspecta.signature.broker: 'assinafy'");
    }
}
