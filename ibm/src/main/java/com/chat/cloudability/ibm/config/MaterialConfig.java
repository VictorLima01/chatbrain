package com.chat.cloudability.ibm.config;

import java.net.URI;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Cliente S3.
 *
 * <p>Um unico bean cobre os dois ambientes, e a diferenca entre eles esta toda
 * em variaveis de ambiente:
 *
 * <ul>
 *   <li><strong>local</strong> — {@code MATERIAL_ENDPOINT} aponta para o
 *       LocalStack, com credenciais de brinquedo e <em>path-style</em> (o
 *       LocalStack nao resolve o dominio {@code bucket.localhost});
 *   <li><strong>AWS</strong> — nada disso e definido. Sem credencial explicita, o
 *       SDK percorre a cadeia padrao e encontra a <em>IAM Role</em> anexada a
 *       instancia EC2. Nenhuma chave de acesso precisa existir no servidor.
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "app.material.provider", havingValue = "s3", matchIfMissing = true)
public class MaterialConfig {

    @Bean
    public S3Client s3Client(AppProperties props) {
        AppProperties.Material material = props.material();

        S3ClientBuilder builder = S3Client.builder().region(Region.of(material.region()));

        if (material.hasCustomEndpoint()) {
            builder.endpointOverride(URI.create(material.endpoint())).forcePathStyle(true);
        }
        if (material.hasStaticCredentials()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(material.accessKey(), material.secretKey())));
        }

        return builder.build();
    }
}
