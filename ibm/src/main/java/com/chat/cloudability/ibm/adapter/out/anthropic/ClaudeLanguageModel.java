package com.chat.cloudability.ibm.adapter.out.anthropic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Usage;
import com.chat.cloudability.ibm.config.AppProperties;
import com.chat.cloudability.ibm.domain.model.Entities.AnswerMode;
import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;
import com.chat.cloudability.ibm.domain.model.Entities.Message;
import com.chat.cloudability.ibm.domain.model.Entities.Role;
import com.chat.cloudability.ibm.domain.port.out.LanguageModelPortOut;

/**
 * Adaptador de saida: Claude, pela API da Anthropic.
 *
 * <p>Tudo que e especifico do SDK vive aqui — thinking adaptativo, effort, cache
 * do prompt de sistema e o formato dos eventos de streaming. Trocar para outro
 * provedor e escrever outra implementacao desta porta; o dominio nao muda.
 */
@Component
public class ClaudeLanguageModel implements LanguageModelPortOut {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLanguageModel.class);

    private final AnthropicClient client;
    private final AppProperties props;

    public ClaudeLanguageModel(AnthropicClient client, AppProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public boolean isConfigured() {
        return props.anthropic().configured();
    }

    @Override
    public String modelName() {
        return props.anthropic().model();
    }

    /**
     * O modo do dominio vira parametro da API aqui, e so aqui.
     *
     * <p>{@code effort} controla a profundidade do raciocinio — e a alavanca de
     * custo mais direta dentro de um mesmo modelo. O <em>thinking</em> continua
     * adaptativo nos dois modos de proposito: desliga-lo na Opus 5 tem efeitos
     * colaterais conhecidos (a chamada de ferramenta pode sair como texto visivel,
     * tags internas podem vazar), e baixar o effort economiza sem esse risco.
     */
    @Override
    public void streamAnswer(String systemPrompt, List<Message> history, String userMessage,
            List<ImageAttachment> images, AnswerMode mode, Consumer<String> onToken) {

        boolean economy = mode != null && mode.isEconomy();

        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(props.anthropic().model())
                .maxTokens(economy
                        ? props.anthropic().economyMaxTokens()
                        : props.anthropic().maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder()
                        .effort(effort(economy
                                ? props.anthropic().economyEffort()
                                : props.anthropic().effort()))
                        .build())
                // O prompt de sistema e estavel entre chamadas -> vale o cache.
                .systemOfTextBlockParams(List.of(
                        TextBlockParam.builder()
                                .text(systemPrompt)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()));

        for (Message message : history) {
            if (message.role() == Role.USER) {
                builder.addUserMessage(message.content());
            } else {
                builder.addAssistantMessage(message.content());
            }
        }

        if (images == null || images.isEmpty()) {
            builder.addUserMessage(userMessage);
        } else {
            builder.addUserMessageOfBlockParams(userTurnWithImages(userMessage, images));
        }

        // O que a chamada custou, para o log. Um array porque lambda so fecha
        // sobre variavel efetivamente final.
        long[] usage = new long[4];

        try (StreamResponse<RawMessageStreamEvent> stream =
                client.messages().createStreaming(builder.build())) {
            stream.stream().forEach(event -> {
                event.contentBlockDelta()
                        .flatMap(block -> block.delta().text())
                        .ifPresent(textDelta -> onToken.accept(textDelta.text()));

                // A entrada chega no inicio; a saida so no fim, quando o modelo
                // ja sabe quanto escreveu.
                event.messageStart().ifPresent(start -> {
                    Usage counted = start.message().usage();
                    usage[0] = counted.inputTokens();
                    usage[2] = counted.cacheReadInputTokens().orElse(0L);
                    usage[3] = counted.cacheCreationInputTokens().orElse(0L);
                });
                event.messageDelta().ifPresent(delta -> usage[1] = delta.usage().outputTokens());
            });
        }

        logUsage(usage, images, mode);
    }

    /**
     * Registra o custo da chamada.
     *
     * <p>Sem isto nao da para responder "por que a conta subiu?" a nao ser
     * chutando. Com o numero no log, da para comparar uma pergunta com print e
     * uma sem, ou o modo economico e o completo, sem adivinhar.
     */
    private void logUsage(long[] usage, List<ImageAttachment> images, AnswerMode mode) {
        int imageCount = images == null ? 0 : images.size();
        log.info("Tokens [{}]: entrada {} (cache: {} lido / {} gravado), saida {}, {} imagem(ns)",
                mode == null ? "full" : mode.wireValue(),
                usage[0], usage[2], usage[3], usage[1], imageCount);
    }

    /**
     * Monta o turno do usuario com os prints antes do texto.
     *
     * <p>A ordem importa: a documentacao da Anthropic recomenda imagem primeiro,
     * texto depois — a mesma razao pela qual o contexto recuperado vem antes da
     * pergunta.
     */
    private List<ContentBlockParam> userTurnWithImages(String userMessage,
            List<ImageAttachment> images) {

        List<ContentBlockParam> blocks = new ArrayList<>(images.size() * 2 + 1);
        int index = 1;
        for (ImageAttachment original : images) {
            ImageAttachment image = shrinkToFit(original, props.anthropic().imageMaxEdge());
            // Rotular cada imagem deixa a usuaria se referir a "imagem 2" depois.
            blocks.add(ContentBlockParam.ofText(TextBlockParam.builder()
                    .text("Imagem %d (%s):".formatted(index++, image.filename()))
                    .build()));
            blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                    .source(Base64ImageSource.builder()
                            .mediaType(mediaType(image.mediaType()))
                            .data(Base64.getEncoder().encodeToString(image.data()))
                            .build())
                    .build()));
        }
        blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(userMessage).build()));
        return blocks;
    }

    /**
     * Reduz a imagem ate caber no limite de lado maior configurado.
     *
     * <p>O Claude cobra imagem por area: cada bloco de 28x28 pixels e um token
     * visual, entao o custo e {@code ceil(largura/28) * ceil(altura/28)}. Um print
     * de 1920x1080 custa 2691 tokens; o mesmo print reduzido para 1568 de lado
     * maior custa cerca de 1790. Acima do limite do modelo a API reduziria sozinha
     * — reduzir antes so evita pagar pelo que seria descartado.
     *
     * <p>O limite padrao e deliberadamente conservador: prints pequenos passam
     * intactos, e a reducao so entra quando a imagem e grande o bastante para o
     * corte valer mais do que a nitidez perdida. Texto de tela fica ilegivel se
     * encolher demais, e um enunciado mal lido custa mais caro que o token
     * economizado.
     *
     * <p>Falhar aqui nunca derruba a pergunta: se o formato nao for legivel pelo
     * ImageIO (WebP, por exemplo), a imagem original segue como estava.
     */
    static ImageAttachment shrinkToFit(ImageAttachment image, int maxEdge) {
        if (maxEdge <= 0) {
            return image;
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(image.data()));
            if (source == null) {
                return image;
            }
            int longEdge = Math.max(source.getWidth(), source.getHeight());
            if (longEdge <= maxEdge) {
                return image;
            }

            double scale = (double) maxEdge / longEdge;
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));

            BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D canvas = target.createGraphics();
            // Fundo branco: um PNG com transparencia viraria preto sem isto, e
            // texto escuro sobre preto some.
            canvas.setColor(Color.WHITE);
            canvas.fillRect(0, 0, width, height);
            // SCALE_SMOOTH faz media de area — mais lento que bilinear, e bem
            // melhor para texto pequeno, que e o que estes prints carregam.
            canvas.drawImage(source.getScaledInstance(width, height, Image.SCALE_SMOOTH),
                    0, 0, null);
            canvas.dispose();

            Encoded encoded = encode(target, image.data().length);
            if (encoded == null || encoded.bytes().length > ImageAttachment.MAX_BYTES) {
                // Reduzir e uma otimizacao: se ela nao couber nas regras do
                // dominio, a pergunta segue com a imagem original — que ja passou
                // pela validacao quando chegou.
                log.warn("Reducao de {} nao coube no limite de tamanho; enviando o original",
                        image.filename());
                return image;
            }

            log.info("Print {} reduzido de {}x{} para {}x{} (~{} -> ~{} tokens visuais, {} KB -> {} KB)",
                    image.filename(), source.getWidth(), source.getHeight(), width, height,
                    visualTokens(source.getWidth(), source.getHeight()),
                    visualTokens(width, height),
                    image.data().length / 1024, encoded.bytes().length / 1024);

            return new ImageAttachment(image.filename(), encoded.mediaType(), encoded.bytes());
        } catch (Exception e) {
            log.warn("Nao foi possivel reduzir {}, enviando como esta: {}",
                    image.filename(), e.getMessage());
            return image;
        }
    }

    record Encoded(String mediaType, byte[] bytes) {
    }

    /**
     * Codifica a imagem reduzida, preferindo PNG e caindo para JPEG quando o PNG
     * fica maior que o original.
     *
     * <p>PNG e o formato certo para print de tela — sem artefato em texto pequeno.
     * Mas PNG e sem perdas, e numa imagem ruidosa (uma foto, nao um print) o
     * arquivo reduzido pode sair <em>maior</em> que o de entrada: menos pixels,
     * porem cada um com ruido que nao comprime. Nesse caso o JPEG resolve, e a
     * perda de qualidade nao importa porque ali nao ha texto fino para preservar.
     */
    static Encoded encode(BufferedImage image, int originalBytes) throws IOException {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        if (png.size() <= originalBytes) {
            return new Encoded("image/png", png.toByteArray());
        }

        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(jpeg)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.85f);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return jpeg.size() < png.size()
                ? new Encoded("image/jpeg", jpeg.toByteArray())
                : new Encoded("image/png", png.toByteArray());
    }

    /** Blocos de 28x28 que a imagem ocupa — a unidade que a Anthropic cobra. */
    private static int visualTokens(int width, int height) {
        return Math.ceilDiv(width, 28) * Math.ceilDiv(height, 28);
    }

    private static Base64ImageSource.MediaType mediaType(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "image/jpeg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> Base64ImageSource.MediaType.IMAGE_PNG;
        };
    }

    private static OutputConfig.Effort effort(String value) {
        return switch (value == null ? "high" : value.toLowerCase()) {
            case "low" -> OutputConfig.Effort.LOW;
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.HIGH;
        };
    }
}
