package com.chat.cloudability.ibm.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

@Configuration
public class AnthropicConfig {

    private static final Logger log = LoggerFactory.getLogger(AnthropicConfig.class);

    /**
     * Cliente da API da Anthropic. Se a chave nao estiver configurada o bean
     * ainda sobe (para a aplicacao iniciar e a UI abrir), mas qualquer chamada
     * falha com mensagem explicita.
     */
    @Bean
    public AnthropicClient anthropicClient(AppProperties props) {
        if (!props.anthropic().configured()) {
            log.warn("ANTHROPIC_API_KEY nao configurada — o chat respondera com erro ate que ela seja definida.");
            return AnthropicOkHttpClient.builder().apiKey("nao-configurada").build();
        }
        return AnthropicOkHttpClient.builder()
                .apiKey(props.anthropic().apiKey())
                .build();
    }
}
