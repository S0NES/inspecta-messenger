package br.com.vistoria.messenger.signature.broker;

import br.com.vistoria.core.gateway.HttpGateway;
import br.com.vistoria.core.gateway.HttpGatewayRequest;
import br.com.vistoria.core.gateway.HttpGatewayResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Broker real contra a API GraphQL v2 da Autentique ({@code api.autentique.com.br/v2/graphql}).
 * Auth: header {@code Authorization: Bearer <API_KEY>}.
 *
 * <p>{@link #criarDocumento}: {@code createDocument} multipart (GraphQL upload spec: partes
 * {@code operations} / {@code map} / {@code 0}). Signatários adicionados <b>só com {@code name}</b>
 * (sem {@code email}) — a Autentique NÃO manda o convite dela e devolve
 * {@code signatures[].link.short_link}, que salvamos e enviamos por e-mail.</p>
 *
 * <p>{@code sandbox: true} só quando {@code inspecta.signature.autentique.sandbox=true}.
 * Autenticidade do webhook: validada na borda ({@code WebhookController}, HMAC-SHA256 de
 * {@code x-autentique-signature}); {@link #parseWebhook} só faz parsing do payload já autenticado.</p>
 */
@Component("autentique")
public class AutentiqueBroker extends AbstractHttpSignatureBroker implements SignatureBroker {

    private static final String CREATE_DOCUMENT =
            "mutation CreateDocument($document: DocumentInput!, $signers: [SignerInput!]!, $file: Upload!) {"
          + " createDocument(%s document: $document, signers: $signers, file: $file) {"
          + " id signatures { public_id name email action { name } link { short_link } } } }";

    private static final String DOCUMENT_FILES =
            "query DocumentFiles($id: UUID!) { document(id: $id) { id files { pades signed original } } }";

    private static final String BOUNDARY = "----inspectaAutentiqueBoundary";

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${inspecta.signature.autentique.api-url:https://api.autentique.com.br/v2/graphql}") private String apiUrl;
    @Value("${inspecta.signature.autentique.api-key:}") private String apiKey;
    @Value("${inspecta.signature.autentique.sandbox:false}") private boolean sandbox;

    public AutentiqueBroker(HttpGateway httpGateway) {
        super(httpGateway);
    }

    public boolean configurado() { return apiKey != null && !apiKey.isBlank(); }

    @Override public String nome() { return "autentique"; }

    @Override
    public EnvioResult criarDocumento(String documentName, List<SignerRef> signersRef, byte[] pdf) {
        if (signersRef == null || signersRef.isEmpty())
            throw new IllegalStateException("Nenhum signatario para " + documentName);
        try {
            ObjectNode variables = mapper.createObjectNode();
            variables.putObject("document").put("name", documentName);
            ArrayNode signers = variables.putArray("signers");
            for (SignerRef s : signersRef) {
                ObjectNode signer = signers.addObject();
                signer.put("name", s.nome());
                signer.put("action", "SIGN");
            }
            variables.putNull("file");

            ObjectNode operations = mapper.createObjectNode();
            operations.put("query", String.format(CREATE_DOCUMENT, sandbox ? "sandbox: true," : ""));
            operations.set("variables", variables);

            byte[] corpo = montarMultipart(mapper.writeValueAsString(operations), "{\"0\":[\"variables.file\"]}",
                    pdf, safeFilename(documentName), BOUNDARY);

            HttpGatewayResponse res = chamar(HttpGatewayRequest.postBinario(
                    URI.create(apiUrl), headers("multipart/form-data; boundary=" + BOUNDARY), corpo));
            JsonNode doc = extrairDataOuFalhar(res, "createDocument").path("createDocument");
            String externalDocumentId = doc.path("id").asText();

            // A Autentique adiciona o dono da API key na lista de assinaturas com name=null.
            // Ficamos só com os signatários que NÓS enviamos — todos com name —, preservando a ordem.
            List<JsonNode> nossos = new ArrayList<>();
            for (JsonNode sig : doc.path("signatures")) {
                if (!sig.path("name").asText("").isBlank()) nossos.add(sig);
            }

            List<SignerResult> assinantes = new ArrayList<>();
            for (int i = 0; i < signersRef.size(); i++) {
                JsonNode sig = casarSignature(nossos, signersRef.get(i).nome(), i);
                String externalSignerId = normalizarId(sig.path("public_id").asText(null));
                String signingUrl = sig.path("link").path("short_link").asText(null);
                assinantes.add(new SignerResult(signersRef.get(i).signatarioId(),
                        emptyToNull(externalSignerId), emptyToNull(signingUrl)));
            }
            return new EnvioResult(externalDocumentId, assinantes);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("autentique criarDocumento falhou", e);
        }
    }

    @Override
    public byte[] baixarPdfAssinado(String externalDocumentId) {
        try {
            ObjectNode variables = mapper.createObjectNode();
            variables.put("id", externalDocumentId);
            ObjectNode operations = mapper.createObjectNode();
            operations.put("query", DOCUMENT_FILES);
            operations.set("variables", variables);

            HttpGatewayResponse res = chamar(HttpGatewayRequest.post(
                    URI.create(apiUrl), headers("application/json"), mapper.writeValueAsString(operations)));
            JsonNode files = extrairDataOuFalhar(res, "document.files").path("document").path("files");
            String url = firstNonBlank(files.path("pades").asText(null), files.path("signed").asText(null));
            if (url == null) throw new IllegalStateException("autentique: documento " + externalDocumentId + " sem PDF assinado (files.pades/signed nulos)");

            HttpGatewayResponse pdf = chamar(HttpGatewayRequest.getBinario(URI.create(url), headers(null)));
            if (!pdf.sucesso()) throw new IllegalStateException("autentique download PDF assinado HTTP " + pdf.status());
            return pdf.bodyComoBytes();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("autentique baixarPdfAssinado falhou", e);
        }
    }

    @Override
    public WebhookEvent parseWebhook(String payload) {
        try {
            JsonNode event = mapper.readTree(payload).path("event");
            String eventId = event.path("id").asText(null);
            String tipo = event.path("type").asText(null);
            if (eventId == null || tipo == null)
                throw new IllegalArgumentException("payload de webhook autentique sem event.id/type");

            // Produção manda o recurso direto em event.data (data.object = "signature"|"document"
            // como rótulo string). A doc mostra aninhado em event.data.object — aceitamos os dois.
            JsonNode data = event.path("data");
            JsonNode obj = data.path("object").isObject() ? data.path("object") : data;

            String externalDocumentId;
            String externalSignerId = null;
            if (tipo.startsWith("signature.")) {
                externalDocumentId = firstNonBlank(obj.path("document").asText(null), data.path("document").asText(null));
                externalSignerId = normalizarId(firstNonBlank(obj.path("public_id").asText(null), data.path("public_id").asText(null)));
            } else {
                externalDocumentId = firstNonBlank(obj.path("id").asText(null), data.path("id").asText(null));
            }
            return new WebhookEvent(eventId, tipo, emptyToNull(externalDocumentId), emptyToNull(externalSignerId));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("payload de webhook autentique invalido", e);
        }
    }

    /**
     * Normaliza o id de assinatura: o {@code createDocument} devolve hex compacto e o webhook
     * manda UUID com hífen — mesmo valor. Guardamos e comparamos sempre sem hífen, minúsculo.
     */
    static String normalizarId(String id) {
        return id == null ? null : id.replace("-", "").toLowerCase(Locale.ROOT);
    }


    private static String safeFilename(String documentName) {
        String base = documentName == null ? "documento" : documentName.replaceAll("[^A-Za-z0-9._-]", "-");
        return base + ".pdf";
    }

    /** Casa a assinatura devolvida pelo provider com o signatário local: por nome, com fallback por índice. */
    private JsonNode casarSignature(List<JsonNode> signatures, String nome, int indice) {
        for (JsonNode sig : signatures) {
            if (nome != null && nome.equals(sig.path("name").asText(null))) return sig;
        }
        if (indice < signatures.size()) return signatures.get(indice);
        return mapper.createObjectNode();
    }

    private byte[] montarMultipart(String operations, String map, byte[] arquivo, String nomeArquivo, String boundary) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(parteTexto(boundary, "operations", operations));
            out.write(parteTexto(boundary, "map", map));
            String fileHeader = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"0\"; filename=\"" + nomeArquivo + "\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n";
            out.write(fileHeader.getBytes(StandardCharsets.UTF_8));
            out.write(arquivo);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("autentique: falha ao montar multipart", e);
        }
    }

    private byte[] parteTexto(String boundary, String nome, String valor) {
        String s = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + nome + "\"\r\n\r\n"
                + valor + "\r\n";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private JsonNode extrairDataOuFalhar(HttpGatewayResponse res, String contexto) throws Exception {
        if (!res.sucesso()) throw new IllegalStateException("autentique " + contexto + " HTTP " + res.status() + ": " + res.body());
        JsonNode root = mapper.readTree(res.body().isBlank() ? "{}" : res.body());
        if (root.has("errors") && root.path("errors").isArray() && !root.path("errors").isEmpty()) {
            throw new IllegalStateException("autentique " + contexto + ": " + root.path("errors").toString());
        }
        return root.path("data");
    }

    private Map<String, String> headers(String contentType) {
        if (contentType == null) return Map.of("Authorization", "Bearer " + apiKey);
        return Map.of("Authorization", "Bearer " + apiKey, "Content-Type", contentType);
    }

    private static String emptyToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) if (v != null && !v.isBlank()) return v;
        return null;
    }
}
