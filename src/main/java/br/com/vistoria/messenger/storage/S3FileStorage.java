package br.com.vistoria.messenger.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.net.URI;

/**
 * Real (inspecta.storage.provider=s3), AWS SDK v2. Serve tanto AWS de verdade (endpoint em branco)
 * quanto S3-compativel self-hosted (MinIO/R2, endpoint setado).
 *
 * Licao do T7.2 (specs/tasks/T7.2-s3-path-style-switch.md, historico): o SDK v2 usa addressing
 * virtual-hosted-style por default e NAO infere path-style so por causa de endpointOverride —
 * e exatamente a ausencia desse switch explicito que tornou o R2Storage antigo "nao verificado"
 * e o levou a ser abandonado. Aqui pathStyleAccessEnabled(...) e sempre setado explicitamente
 * a partir de config, nunca deixado no default do SDK.
 *
 * O client S3 e construido preguicosamente (lazy, memoizado) no primeiro uso, e NAO no
 * construtor: este bean sempre existe (igual aos brokers http, que existem mesmo quando o
 * provider ativo e mock), entao um deploy com inspecta.storage.provider=local nao pode ser
 * forcado a fornecer credencial S3 real so pra este bean nao-usado conseguir instanciar.
 */
@Component("s3")
@Slf4j
public class S3FileStorage implements FileStorage {

    @Value("${inspecta.storage.s3.bucket:inspecta}") private String bucket;
    @Value("${inspecta.storage.s3.region:us-east-1}") private String region;
    @Value("${inspecta.storage.s3.endpoint:}") private String endpoint;
    @Value("${inspecta.storage.s3.access-key:}") private String accessKey;
    @Value("${inspecta.storage.s3.secret-key:}") private String secretKey;
    @Value("${inspecta.storage.s3.path-style-access:true}") private boolean pathStyleAccess;

    private volatile S3Client client;

    @Override
    public String name() {
        return "s3";
    }

    @Override
    public void upload(String key, byte[] bytes, String mimeType) {
        client().putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(mimeType).build(),
                RequestBody.fromBytes(bytes));
        log.info("[storage-s3] upload bucket={} key={} bytes={}", bucket, key, bytes.length);
    }

    @Override
    public byte[] download(String key) {
        return client().getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        client().deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.info("[storage-s3] delete bucket={} key={}", bucket, key);
    }

    private S3Client client() {
        S3Client c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    c = client = buildClient();
                }
            }
        }
        return c;
    }

    private S3Client buildClient() {
        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(serviceConfiguration());
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /**
     * Extraida a parte pra ficar testavel isoladamente: pathStyleAccessEnabled(...) tem que
     * vir sempre explicitamente daqui, nunca do default do SDK (licao do T7.2 historico).
     */
    private S3Configuration serviceConfiguration() {
        return S3Configuration.builder()
                .pathStyleAccessEnabled(pathStyleAccess)
                .build();
    }
}
