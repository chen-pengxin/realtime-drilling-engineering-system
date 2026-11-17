package com.ruoyi.web.controller.drilling;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.MinioException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

/**
 * Small optional MinIO adapter used by the field-scoped file controller.
 *
 * The adapter is deliberately disabled when the endpoint or credentials are
 * absent, which keeps local development usable.  Callers retain the local
 * profile copy as a fallback, while every object key stored in data_file is
 * also a valid MinIO key when this adapter is enabled.
 */
@Component
public class DrillingObjectStorage {
    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private volatile MinioClient client;

    public DrillingObjectStorage(
            @Value("${drilling.storage.endpoint:}") String endpoint,
            @Value("${drilling.storage.access-key:}") String accessKey,
            @Value("${drilling.storage.secret-key:}") String secretKey,
            @Value("${drilling.storage.bucket:drilling-files}") String bucket) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.accessKey = accessKey == null ? "" : accessKey.trim();
        this.secretKey = secretKey == null ? "" : secretKey.trim();
        this.bucket = bucket == null || bucket.isBlank() ? "drilling-files" : bucket.trim();
    }

    public boolean enabled() {
        return !endpoint.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    public void put(String objectKey, InputStream input, long size, String contentType) throws IOException {
        if (!enabled()) return;
        try {
            ensureBucket();
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(input, size, 10L * 1024 * 1024);
            if (contentType != null && !contentType.isBlank()) builder.contentType(contentType);
            client().putObject(builder.build());
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("unable to upload object to MinIO", e);
        }
    }

    public RemoteObject get(String objectKey) throws IOException {
        if (!enabled()) return null;
        try {
            StatObjectResponse stat = client().statObject(StatObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            GetObjectResponse stream = client().getObject(GetObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            return new RemoteObject(stream, stat.size(), stat.contentType());
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("unable to download object from MinIO", e);
        }
    }

    public void remove(String objectKey) throws IOException {
        if (!enabled()) return;
        try {
            client().removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new IOException("unable to remove object from MinIO", e);
        }
    }

    public String bucket() { return bucket; }

    private MinioClient client() {
        MinioClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = MinioClient.builder().endpoint(endpoint)
                            .credentials(accessKey, secretKey).build();
                    client = current;
                }
            }
        }
        return current;
    }

    private void ensureBucket() throws IOException, MinioException, InvalidKeyException, NoSuchAlgorithmException {
        if (!client().bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            client().makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }

    public record RemoteObject(InputStream stream, long size, String contentType) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }
}
