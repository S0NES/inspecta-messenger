package br.com.vistoria.messenger.signature.broker;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;

public abstract class AbstractHttpSignatureBroker implements SignatureBroker {

    protected final HttpGateway httpGateway;

    protected AbstractHttpSignatureBroker(HttpGateway httpGateway) {
        this.httpGateway = httpGateway;
    }

    protected HttpGatewayResponse chamar(HttpGatewayRequest request) throws Exception {
        return httpGateway.execute(request);
    }
}
