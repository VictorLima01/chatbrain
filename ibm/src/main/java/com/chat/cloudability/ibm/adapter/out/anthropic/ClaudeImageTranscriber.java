package com.chat.cloudability.ibm.adapter.out.anthropic;

import java.util.Base64;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlockParam;
import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.port.out.ImageTranscriberPortOut;

/**
 * Adaptador de saida: transcricao de imagens pela visao do Claude.
 *
 * <p>Usado para o {@code Perguntas.png}, que traz questoes do quiz como captura de
 * tela — sem isso esse material ficaria de fora da base.
 */
@Component
public class ClaudeImageTranscriber implements ImageTranscriberPortOut {

    private static final Logger log = LoggerFactory.getLogger(ClaudeImageTranscriber.class);

    private static final String PROMPT = """
            Transcreva TODO o conteudo textual desta imagem em Markdown, preservando a estrutura.

            Se forem questoes de multipla escolha, use este formato para cada uma:

            ### Pergunta
            <enunciado exatamente como aparece>
            - [ ] alternativa A
            - [ ] alternativa B
            ...
            (marque com [x] a alternativa assinalada, se houver alguma marcacao visivel)

            Nao resuma, nao traduza e nao comente. Devolva apenas a transcricao.
            """;

    private final AnthropicClient client;
    private final AppProperties props;

    public ClaudeImageTranscriber(AnthropicClient client, AppProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean handles(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    @Override
    public boolean isAvailable() {
        return props.anthropic().configured();
    }

    @Override
    public String transcribe(String filename, byte[] content) {
        String base64 = Base64.getEncoder().encodeToString(content);
        Base64ImageSource.MediaType type = filename.toLowerCase(Locale.ROOT).endsWith(".png")
                ? Base64ImageSource.MediaType.IMAGE_PNG
                : Base64ImageSource.MediaType.IMAGE_JPEG;

        ContentBlockParam image = ContentBlockParam.ofImage(
                ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(type)
                                .data(base64)
                                .build())
                        .build());

        ContentBlockParam instruction = ContentBlockParam.ofText(
                TextBlockParam.builder().text(PROMPT).build());

        MessageCreateParams params = MessageCreateParams.builder()
                .model(props.anthropic().model())
                .maxTokens(16000L)
                .addUserMessageOfBlockParams(List.of(image, instruction))
                .build();

        Message response = client.messages().create(params);
        StringBuilder text = new StringBuilder();
        response.content().stream()
                .flatMap(block -> block.text().stream())
                .forEach(block -> text.append(block.text()));

        log.info("Imagem {} transcrita ({} caracteres)", filename, text.length());
        return text.toString();
    }
}
