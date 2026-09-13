package com.chat.cloudability.ibm.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuracao da aplicacao, mapeada do bloco `app` do application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Cors cors,
        Access access,
        Anthropic anthropic,
        Embedding embedding,
        Rag rag,
        Material material) {

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * Alcadas.
     *
     * @param defaultLevel alcada assumida quando a requisicao nao identifica quem
     *                     chama. Enquanto {@code authEnabled} for falso, e a alcada
     *                     de todo mundo
     * @param authEnabled  reservado para quando o login existir; hoje sempre falso,
     *                     e a interface usa isso para avisar que a area
     *                     administrativa esta aberta
     */
    public record Access(String defaultLevel, boolean authEnabled) {
    }

    /**
     * @param economyEffort    profundidade de raciocinio no modo economico
     * @param economyMaxTokens teto de saida no modo economico. Um teto, nao a
     *                         meta: quem encurta a resposta e a instrucao no
     *                         prompt — cortar por {@code max_tokens} truncaria a
     *                         resposta no meio de uma frase
     * @param imageMaxEdge     lado maior, em pixels, ate onde um print e enviado
     *                         sem reducao. Imagem custa por area; 0 desliga a
     *                         reducao
     */
    public record Anthropic(String apiKey, String model, long maxTokens, String effort,
            String economyEffort, long economyMaxTokens, int imageMaxEdge) {

        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Embedding(
            String provider,
            String baseUrl,
            int dimensions,
            String documentPrefix,
            String queryPrefix) {
    }

    public record Rag(int chunkSize, int chunkOverlap, int topK, int economyTopK,
            double minScore) {
    }

    /**
     * Onde o material de estudo vive.
     *
     * @param provider     {@code s3} (padrao) ou {@code filesystem}
     * @param prefix       pasta raiz dentro do bucket; abaixo dela vem uma pasta
     *                     por modulo
     * @param endpoint     em branco na AWS. Preenchido apenas para apontar para o
     *                     LocalStack
     * @param accessKey    em branco na AWS: sem credencial explicita, o SDK usa a
     *                     cadeia padrao e cai na IAM Role do EC2
     * @param createBucket cria o bucket no boot se faltar. Ligado no local, onde o
     *                     LocalStack sobe vazio; desligado na AWS
     * @param basePath     raiz em disco, usada so quando o provider e filesystem
     */
    public record Material(
            String provider,
            String bucket,
            String prefix,
            String region,
            String endpoint,
            String accessKey,
            String secretKey,
            boolean createBucket,
            String basePath,
            boolean autoSyncOnStartup) {

        /** Endpoint proprio significa "nao e a AWS de verdade" — hoje, LocalStack. */
        public boolean hasCustomEndpoint() {
            return endpoint != null && !endpoint.isBlank();
        }

        public boolean hasStaticCredentials() {
            return accessKey != null && !accessKey.isBlank();
        }
    }
}
