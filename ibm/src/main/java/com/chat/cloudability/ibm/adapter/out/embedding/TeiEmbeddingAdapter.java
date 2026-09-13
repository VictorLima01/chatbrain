package com.chat.cloudability.ibm.adapter.out.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.port.out.EmbeddingPortOut;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Adaptador de saida: Text Embeddings Inference rodando em container local.
 *
 * <p>A API da Anthropic nao expoe endpoint de embeddings — o Claude gera as
 * respostas e os vetores vem daqui. O modelo padrao e o
 * {@code intfloat/multilingual-e5-small}: 384 dimensoes, CPU, PT e EN.
 *
 * <p>O e5 exige prefixos distintos para documento e consulta; isso e detalhe do
 * modelo, entao mora no adaptador e nao vaza para o dominio.
 *
 * <p>Com {@code app.embedding.provider=none} cai num vetor deterministico por
 * hashing, so para a aplicacao subir sem Docker durante o desenvolvimento.
 */
@Component
public class TeiEmbeddingAdapter implements EmbeddingPortOut {

    private static final Logger log = LoggerFactory.getLogger(TeiEmbeddingAdapter.class);

    private final AppProperties props;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TeiEmbeddingAdapter(AppProperties props, HttpClient http) {
        this.props = props;
        this.http = http;
    }

    @Override
    public int dimensions() {
        return props.embedding().dimensions();
    }

    @Override
    public float[] embedQuery(String text) {
        return embedBatch(List.of(props.embedding().queryPrefix() + text)).get(0);
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        return embedBatch(texts.stream()
                .map(t -> props.embedding().documentPrefix() + t)
                .toList());
    }

    private List<float[]> embedBatch(List<String> inputs) {
        if ("none".equalsIgnoreCase(props.embedding().provider())) {
            return inputs.stream().map(this::deterministicFallback).toList();
        }
        try {
            String body = mapper.writeValueAsString(Map.of("inputs", inputs));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(props.embedding().baseUrl() + "/embed"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Servico de embeddings respondeu " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = mapper.readTree(response.body());
            List<float[]> result = new ArrayList<>(root.size());
            for (JsonNode vectorNode : root) {
                float[] vector = new float[vectorNode.size()];
                for (int i = 0; i < vectorNode.size(); i++) {
                    vector[i] = (float) vectorNode.get(i).asDouble();
                }
                result.add(vector);
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrompido ao gerar embeddings", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Falha ao gerar embeddings em " + props.embedding().baseUrl()
                            + ". O container de embeddings esta no ar? (docker compose up embeddings)",
                    e);
        }
    }

    /**
     * Fallback de desenvolvimento. A recuperacao semantica fica ruim — serve
     * apenas para desenvolver a interface sem subir o container.
     */
    private float[] deterministicFallback(String text) {
        log.debug("Embedding de fallback (provider=none) — qualidade de busca reduzida");
        float[] vector = new float[props.embedding().dimensions()];
        for (String token : text.toLowerCase().split("\\W+")) {
            if (token.isBlank()) {
                continue;
            }
            vector[Math.abs(token.hashCode()) % vector.length] += 1.0f;
        }
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= (float) norm;
            }
        }
        return vector;
    }
}
