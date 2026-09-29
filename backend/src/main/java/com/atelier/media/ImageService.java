package com.atelier.media;

import com.atelier.shared.error.BusinessException;
import com.atelier.shared.error.ErrorCode;
import org.springframework.stereotype.Service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/**
 * Valida e grava imagens enviadas pelo admin (PRD 20.1): só JPEG/PNG de verdade (decodificados, não pela
 * extensão), até 10 MB e 1200 px de largura mínima. A imagem é regravada, o que descarta EXIF (GPS, câmera)
 * e qualquer conteúdo embutido.
 * ponytail: sem WebP/AVIF na entrada (ImageIO não lê); os formatos modernos são gerados na entrega pela CDN.
 */
@Service
public class ImageService {

    public record StoredImage(String key, String url, int width, int height) {}

    static final int MAX_BYTES = 10 * 1024 * 1024;
    static final int MIN_WIDTH = 1200;
    static final int MAX_DIMENSION = 8000;

    private final ImageStorage storage;

    ImageService(ImageStorage storage) {
        this.storage = storage;
    }

    public StoredImage store(String folder, byte[] data, int minWidth) {
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE, "Imagem vazia ou maior que 10 MB");
        }
        String format = detectFormat(data);
        BufferedImage image = format == null ? null : decode(data);
        if (format == null || image == null) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE, "Envie uma imagem JPEG ou PNG");
        }
        if (image.getWidth() < minWidth) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE, "Largura mínima de " + minWidth + " px");
        }
        byte[] clean = format.equals("png") ? writePng(image) : writeJpeg(image);
        String key = folder + "/" + UUID.randomUUID() + "." + (format.equals("png") ? "png" : "jpg");
        String url = storage.put(key, clean, format.equals("png") ? "image/png" : "image/jpeg");
        return new StoredImage(key, url, image.getWidth(), image.getHeight());
    }

    public StoredImage store(String folder, byte[] data) {
        return store(folder, data, MIN_WIDTH);
    }

    public void delete(String key) {
        if (key != null) storage.delete(key);
    }

    /**
     * Lê as dimensões pelo cabeçalho antes de decodificar: um PNG de poucos KB pode declarar 30000×30000 px
     * (bomba de descompressão) e esgotar a memória no ImageIO.read.
     */
    private static BufferedImage decode(byte[] data) {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (reader.getWidth(0) > MAX_DIMENSION || reader.getHeight(0) > MAX_DIMENSION) {
                    throw new BusinessException(ErrorCode.INVALID_IMAGE, "Dimensão máxima de " + MAX_DIMENSION + " px");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException be) throw be;
            return null;
        }
    }

    /** Assinatura do arquivo (magic bytes), não o Content-Type informado pelo cliente. */
    private static String detectFormat(byte[] d) {
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "jpeg";
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "png";
        return null;
    }

    private static byte[] writeJpeg(BufferedImage source) {
        // JPEG não tem alfa: achata sobre fundo branco se necessário.
        BufferedImage rgb = source;
        if (source.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
            g.dispose();
        }
        var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        var params = writer.getDefaultWriteParam();
        params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        params.setCompressionQuality(0.9f);
        var out = new ByteArrayOutputStream();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), params);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static byte[] writePng(BufferedImage image) {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }
}
