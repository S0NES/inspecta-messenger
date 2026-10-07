package br.com.vistoria.messenger.storage;

/**
 * Padrao mock/http: selecao por {@code inspecta.storage.provider}, "local" default.
 */
public interface FileStorage {

    String name();

    void upload(String key, byte[] bytes, String mimeType);

    byte[] download(String key);

    void delete(String key);
}
