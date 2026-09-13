package com.chat.cloudability.ibm.adapter.out.s3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.MaterialRef;
import com.chat.cloudability.ibm.domain.port.out.MaterialCatalogPortOut;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Adaptador de saida: material de estudo guardado no S3.
 *
 * <p>O layout do bucket e {@code <prefixo>/<slug do modulo>/<caminho do arquivo>}.
 * Uma pasta por modulo nao e organizacao estetica: e o que permite listar,
 * sincronizar e apagar o material de um modulo sem tocar no de outro, com uma
 * unica chamada de API.
 *
 * <p>Local, o endpoint aponta para o LocalStack e as credenciais sao estaticas.
 * Na AWS nao ha endpoint nem credencial configurada: o SDK usa a cadeia padrao e
 * encontra a <em>IAM Role</em> da instancia EC2. O codigo e o mesmo nos dois
 * casos — muda so o que esta no ambiente.
 */
@Component
@ConditionalOnProperty(name = "app.material.provider", havingValue = "s3", matchIfMissing = true)
public class S3MaterialCatalog implements MaterialCatalogPortOut {

    private static final Logger log = LoggerFactory.getLogger(S3MaterialCatalog.class);

    private static final List<String> SUPPORTED_SUFFIXES =
            List.of(".vtt", ".pdf", ".pptx", ".md", ".txt", ".png", ".jpg", ".jpeg");

    private final S3Client s3;
    private final AppProperties props;

    public S3MaterialCatalog(S3Client s3, AppProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    /**
     * Cria o bucket quando a configuracao pede.
     *
     * <p>Ligado no ambiente local (o LocalStack sobe vazio) e desligado na AWS,
     * onde a Role da instancia so tem permissao sobre os objetos — criar bucket
     * e trabalho do provisionamento, nao da aplicacao.
     */
    @PostConstruct
    void ensureBucket() {
        if (!props.material().createBucket()) {
            return;
        }
        try {
            s3.headBucket(b -> b.bucket(bucket()));
        } catch (NoSuchBucketException e) {
            log.info("Criando bucket {}", bucket());
            s3.createBucket(b -> b.bucket(bucket()));
        } catch (Exception e) {
            log.warn("Nao foi possivel verificar o bucket {}: {}", bucket(), e.getMessage());
        }
    }

    @Override
    public List<MaterialRef> list(String moduleSlug) {
        String prefix = prefixOf(moduleSlug);
        List<MaterialRef> refs = new ArrayList<>();

        ListObjectsV2Request.Builder request = ListObjectsV2Request.builder()
                .bucket(bucket())
                .prefix(prefix);

        for (S3Object object : s3.listObjectsV2Paginator(request.build()).contents()) {
            String key = object.key();
            // Consoles de S3 representam pastas como objetos vazios terminados em "/".
            if (key.endsWith("/") || object.size() == 0) {
                continue;
            }
            String path = key.substring(prefix.length());
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (isSupported(filename)) {
                refs.add(new MaterialRef(key, path, filename));
            }
        }

        log.info("Modulo {}: {} arquivo(s) elegiveis em s3://{}/{}",
                moduleSlug, refs.size(), bucket(), prefix);
        return refs;
    }

    @Override
    public byte[] read(MaterialRef ref) {
        return s3.getObjectAsBytes(GetObjectRequest.builder()
                .bucket(bucket())
                .key(ref.location())
                .build()).asByteArray();
    }

    @Override
    public MaterialRef store(String moduleSlug, String filename, byte[] content) {
        String safeName = sanitize(filename);
        String path = "enviados/" + safeName;
        String key = prefixOf(moduleSlug) + path;

        s3.putObject(PutObjectRequest.builder().bucket(bucket()).key(key).build(),
                RequestBody.fromBytes(content));

        return new MaterialRef(key, path, safeName);
    }

    @Override
    public void deleteModule(String moduleSlug) {
        String prefix = prefixOf(moduleSlug);
        List<ObjectIdentifier> batch = new ArrayList<>();

        for (S3Object object : s3.listObjectsV2Paginator(
                r -> r.bucket(bucket()).prefix(prefix)).contents()) {
            batch.add(ObjectIdentifier.builder().key(object.key()).build());
            if (batch.size() == 1000) {
                deleteBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            deleteBatch(batch);
        }
        log.info("Material do modulo {} removido de s3://{}/{}", moduleSlug, bucket(), prefix);
    }

    private void deleteBatch(List<ObjectIdentifier> keys) {
        s3.deleteObjects(DeleteObjectsRequest.builder()
                .bucket(bucket())
                .delete(Delete.builder().objects(keys).build())
                .build());
    }

    private String bucket() {
        return props.material().bucket();
    }

    /** {@code modules/} + slug + {@code /} — sempre com barra no fim. */
    private String prefixOf(String moduleSlug) {
        String root = props.material().prefix();
        String normalized = root == null || root.isBlank() ? "" : root.replaceAll("^/+|/+$", "") + "/";
        return normalized + moduleSlug + "/";
    }

    /** Nome de arquivo previsivel: sem caminho, sem caractere que atrapalhe a chave. */
    private static String sanitize(String filename) {
        String name = filename == null ? "arquivo.txt" : filename;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("[^A-Za-z0-9._ -]", "_").strip();
        return name.isBlank() ? "arquivo.txt" : name;
    }

    private static boolean isSupported(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return SUPPORTED_SUFFIXES.stream().anyMatch(lower::endsWith);
    }
}
