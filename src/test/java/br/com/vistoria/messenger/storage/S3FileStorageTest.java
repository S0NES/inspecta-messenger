package br.com.vistoria.messenger.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link S3FileStorage}. Mocks {@link S3Client} directly (injected via
 * reflection into the memoized field) to verify putObject/getObject/deleteObject are called
 * with the right bucket/key, and that the client is genuinely built lazily (not in the
 * constructor) so a local-provider deployment isn't forced to configure real S3 credentials.
 */
@DisplayName("S3FileStorage")
class S3FileStorageTest {

    private S3FileStorage storage;

    @BeforeEach
    void setUp() {
        storage = new S3FileStorage();
        ReflectionTestUtils.setField(storage, "bucket", "meu-bucket");
        ReflectionTestUtils.setField(storage, "region", "us-east-1");
        ReflectionTestUtils.setField(storage, "endpoint", "");
        ReflectionTestUtils.setField(storage, "accessKey", "AKIA");
        ReflectionTestUtils.setField(storage, "secretKey", "secret");
        ReflectionTestUtils.setField(storage, "pathStyleAccess", true);
    }

    @Test
    @DisplayName("name returns 's3'")
    void name_returnsS3() {
        assertThat(storage.name()).isEqualTo("s3");
    }

    @Test
    @DisplayName("S3Client is not built until first use (lazy, no eager construction in constructor)")
    void s3Client_notBuiltUntilFirstUse() {
        Object clientBeforeUse = ReflectionTestUtils.getField(storage, "client");
        assertThat(clientBeforeUse).isNull();
    }

    @Test
    @DisplayName("upload calls putObject with the configured bucket, key and content type")
    void upload_callsPutObjectWithBucketAndKey() {
        S3Client mockClient = mock(S3Client.class);
        ReflectionTestUtils.setField(storage, "client", mockClient);
        when(mockClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        storage.upload("laudo/1/arquivo.pdf", new byte[]{1, 2, 3}, "application/pdf");

        var captor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockClient).putObject(captor.capture(), any(RequestBody.class));
        assertThat(captor.getValue().bucket()).isEqualTo("meu-bucket");
        assertThat(captor.getValue().key()).isEqualTo("laudo/1/arquivo.pdf");
        assertThat(captor.getValue().contentType()).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("download calls getObjectAsBytes with the configured bucket and key")
    void download_callsGetObjectAsBytesWithBucketAndKey() {
        S3Client mockClient = mock(S3Client.class);
        ReflectionTestUtils.setField(storage, "client", mockClient);
        byte[] conteudo = "conteudo".getBytes();
        ResponseBytes<GetObjectResponse> responseBytes =
                ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), conteudo);
        when(mockClient.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(responseBytes);

        byte[] resultado = storage.download("laudo/1/arquivo.pdf");

        assertThat(resultado).isEqualTo(conteudo);
        var captor = org.mockito.ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(mockClient).getObjectAsBytes(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("meu-bucket");
        assertThat(captor.getValue().key()).isEqualTo("laudo/1/arquivo.pdf");
    }

    @Test
    @DisplayName("delete calls deleteObject with the configured bucket and key")
    void delete_callsDeleteObjectWithBucketAndKey() {
        S3Client mockClient = mock(S3Client.class);
        ReflectionTestUtils.setField(storage, "client", mockClient);

        storage.delete("laudo/1/arquivo.pdf");

        var captor = org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(mockClient).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo("meu-bucket");
        assertThat(captor.getValue().key()).isEqualTo("laudo/1/arquivo.pdf");
    }

    @Test
    @DisplayName("once built, the memoized S3Client is reused across calls (not rebuilt)")
    void s3Client_isMemoizedAcrossCalls() {
        S3Client mockClient = mock(S3Client.class);
        ReflectionTestUtils.setField(storage, "client", mockClient);
        when(mockClient.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        storage.upload("a", new byte[]{1}, "text/plain");
        storage.upload("b", new byte[]{2}, "text/plain");

        assertThat(ReflectionTestUtils.getField(storage, "client")).isSameAs(mockClient);
        verify(mockClient, never()).close();
    }

    @Test
    @DisplayName("pathStyleAccessEnabled(true) is wired from inspecta.storage.s3.path-style-access config")
    void pathStyleAccess_isWiredFromConfig_whenTrue() {
        // T7.2 (historico): the SDK never infers this from endpointOverride alone -- it must
        // always be set explicitly from config. Verify the S3Configuration our code builds
        // actually carries the configured flag through, rather than trusting the SDK default.
        ReflectionTestUtils.setField(storage, "pathStyleAccess", true);

        software.amazon.awssdk.services.s3.S3Configuration config =
                (software.amazon.awssdk.services.s3.S3Configuration) ReflectionTestUtils.invokeMethod(storage, "serviceConfiguration");

        assertThat(config.pathStyleAccessEnabled()).isTrue();
    }

    @Test
    @DisplayName("pathStyleAccessEnabled(false) is wired from inspecta.storage.s3.path-style-access config")
    void pathStyleAccess_isWiredFromConfig_whenFalse() {
        ReflectionTestUtils.setField(storage, "pathStyleAccess", false);

        software.amazon.awssdk.services.s3.S3Configuration config =
                (software.amazon.awssdk.services.s3.S3Configuration) ReflectionTestUtils.invokeMethod(storage, "serviceConfiguration");

        assertThat(config.pathStyleAccessEnabled()).isFalse();
    }

    @Test
    @DisplayName("buildClient wires an endpointOverride when endpoint is configured (self-hosted MinIO/R2)")
    void buildClient_wiresEndpointOverride_whenEndpointConfigured() {
        ReflectionTestUtils.setField(storage, "endpoint", "http://localhost:9000");

        S3Client built = (S3Client) ReflectionTestUtils.invokeMethod(storage, "buildClient");

        assertThat(built).isNotNull();
        assertThat(built.serviceClientConfiguration().endpointOverride()).isPresent();
        built.close();
    }

    @Test
    @DisplayName("endpointOverride is only applied when endpoint is non-blank (real AWS otherwise)")
    void endpointOverride_onlyAppliedWhenNonBlank() {
        ReflectionTestUtils.setField(storage, "endpoint", "");

        S3Client built = (S3Client) ReflectionTestUtils.invokeMethod(storage, "buildClient");

        assertThat(built.serviceClientConfiguration().endpointOverride()).isEmpty();
        built.close();
    }
}
