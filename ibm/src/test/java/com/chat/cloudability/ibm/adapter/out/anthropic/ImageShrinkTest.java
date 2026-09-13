package com.chat.cloudability.ibm.adapter.out.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.chat.cloudability.ibm.domain.model.Entities.ImageAttachment;

/**
 * A reducao de prints antes de mandar para a API.
 *
 * <p>Imagem custa por area: um token visual por bloco de 28x28 px. Reduzir o lado
 * maior e a unica alavanca que mexe nesse numero sem mudar de modelo.
 */
class ImageShrinkTest {

    @Test
    void imagemMenorQueOLimitePassaIntacta() throws Exception {
        ImageAttachment original = png(1296, 805);

        ImageAttachment resultado = ClaudeLanguageModel.shrinkToFit(original, 1568);

        assertSame(original, resultado, "sem reducao, nem re-codificar vale a pena");
    }

    @Test
    void imagemGrandeEReduzidaPreservandoAProporcao() throws Exception {
        ImageAttachment resultado = ClaudeLanguageModel.shrinkToFit(png(1920, 1080), 1568);

        BufferedImage saida = ImageIO.read(new ByteArrayInputStream(resultado.data()));
        assertEquals(1568, saida.getWidth());
        assertEquals(882, saida.getHeight(), "1080 * (1568/1920) = 882");
        assertTrue(visualTokens(1568, 882) < visualTokens(1920, 1080),
                "a reducao precisa de fato cortar tokens visuais");
    }

    @Test
    void limiteZeroDesligaAReducao() throws Exception {
        ImageAttachment original = png(4000, 3000);

        assertSame(original, ClaudeLanguageModel.shrinkToFit(original, 0));
    }

    @Test
    void conteudoIlegivelSegueComoEstaEmVezDeQuebrarAPergunta() {
        // WebP, por exemplo: o ImageIO nao le por padrao. Melhor mandar a imagem
        // original e pagar alguns tokens a mais do que derrubar a pergunta.
        ImageAttachment naoDecodificavel =
                new ImageAttachment("x.webp", "image/webp", new byte[] {1, 2, 3, 4});

        assertSame(naoDecodificavel, ClaudeLanguageModel.shrinkToFit(naoDecodificavel, 1568));
    }

    @Test
    void quandoOPngFicaMaiorQueOOriginalAEncodificacaoCaiParaJpeg() throws Exception {
        // O ramo de seguranca: PNG e sem perdas, entao numa imagem ruidosa o
        // arquivo reduzido pode sair MAIOR que o de entrada. Como o construtor de
        // ImageAttachment recusa acima de 5 MB, sem esta queda a pergunta
        // quebraria. "Original de 1 byte" forca o ramo; o ruido garante que o
        // JPEG realmente ganhe do PNG, que e o outro lado da decisao.
        BufferedImage ruido = noisy(600, 400);

        ClaudeLanguageModel.Encoded resultado = ClaudeLanguageModel.encode(ruido, 1);

        assertEquals("image/jpeg", resultado.mediaType(),
                "em imagem que o PNG nao comprime, o JPEG tem de ser escolhido");

        // E a decisao e pelo menor: num print chapado, o PNG continua ganhando.
        BufferedImage chapado = ImageIO.read(new ByteArrayInputStream(printLike(600, 400).data()));
        assertEquals("image/png", ClaudeLanguageModel.encode(chapado, 1).mediaType());
    }

    @Test
    void imagemRuidosaContinuaDentroDoLimite() throws Exception {
        // PNG e sem perdas: reduzir uma imagem ruidosa pode gerar um arquivo
        // MAIOR que o original — menos pixels, mas cada um com ruido que nao
        // comprime. Como o construtor de ImageAttachment recusa acima de 5 MB,
        // sem a queda para JPEG isso derrubaria a pergunta.
        //
        // O que NAO se garante e "o arquivo sempre encolhe": a entrada aqui e um
        // padrao sintetico que o PNG comprime a quase nada, e nenhuma reducao
        // compete com isso. O que importa e o custo em tokens, que cai sempre, e
        // o resultado continuar aceitavel para o dominio.
        ImageAttachment resultado = ClaudeLanguageModel.shrinkToFit(png(3000, 2000), 1568);

        assertTrue(resultado.data().length <= ImageAttachment.MAX_BYTES,
                "acima do limite do dominio a pergunta quebraria");
        assertTrue(visualTokens(1568, 1045) < visualTokens(3000, 2000),
                "o que precisa cair e o custo em tokens");
    }

    @Test
    void aReducaoEncolheUmPrintDeVerdade() throws Exception {
        // Um print real tem areas chapadas e texto, nao ruido: aqui o arquivo
        // encolhe de fato.
        ImageAttachment original = printLike(2400, 1600);

        ImageAttachment resultado = ClaudeLanguageModel.shrinkToFit(original, 1568);

        assertTrue(resultado.data().length < original.data().length,
                "print reduzido deveria trafegar menos bytes: " + resultado.data().length
                        + " vs " + original.data().length);
    }

    private static int visualTokens(int width, int height) {
        return Math.ceilDiv(width, 28) * Math.ceilDiv(height, 28);
    }

    /** Ruido pseudoaleatorio: o pior caso do PNG e o bom caso do JPEG. */
    private static BufferedImage noisy(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        return image;
    }

    /** Imitacao de print: fundo claro com blocos escuros, como texto numa tela. */
    private static ImageAttachment printLike(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.DARK_GRAY);
        for (int linha = 40; linha < height - 40; linha += 34) {
            g.fillRect(60, linha, (int) (width * 0.7), 14);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new ImageAttachment("print.png", "image/png", out.toByteArray());
    }

    /** PNG sintetico com algum desenho, para o compressor nao reduzir a nada. */
    private static ImageAttachment png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x += 3) {
            for (int y = 0; y < height; y += 3) {
                image.setRGB(x, y, ((x * 7 + y * 13) % 2 == 0 ? Color.BLACK : Color.RED).getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return new ImageAttachment("print.png", "image/png", out.toByteArray());
    }
}
