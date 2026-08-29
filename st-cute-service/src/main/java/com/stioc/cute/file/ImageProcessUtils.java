package com.stioc.cute.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

/**
 * 图像处理工具类：提供图片等比缩放、质量压缩、缩略图生成等功能
 */
@Slf4j
public class ImageProcessUtils {

    /**
     * 支持的图片扩展名集合（小写）
     */
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "webp", "gif", "bmp"
    );

    /**
     * 判定指定扩展名或 MIME 类型是否为图片
     */
    public static boolean isImage(String extension, String mimeType) {
        if (StringUtils.hasText(extension) && IMAGE_EXTENSIONS.contains(extension.toLowerCase())) {
            return true;
        }
        return StringUtils.hasText(mimeType) && mimeType.toLowerCase().startsWith("image/");
    }

    /**
     * 对图片字节数组进行等比缩放与质量压缩（若长边超过 maxDimension 则等比缩小，并控制画质）
     *
     * @param inputBytes   原始图片字节数组
     * @param extension    图片后缀（如 jpg, png, bmp）
     * @param maxDimension 最长边上限（例如 2048）
     * @param quality      压缩质量（0.0 ~ 1.0，推荐 0.75f）
     * @return 压缩处理后的字节数组；若未缩放且重新编码后体积变大，则自动保留原图字节
     */
    public static byte[] compressAndResize(byte[] inputBytes, String extension, int maxDimension, float quality) {
        if (inputBytes == null || inputBytes.length == 0) {
            return inputBytes;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(inputBytes)) {
            BufferedImage originalImage = ImageIO.read(bais);
            if (originalImage == null) {
                return inputBytes;
            }

            int origWidth = originalImage.getWidth();
            int origHeight = originalImage.getHeight();
            int maxSide = Math.max(origWidth, origHeight);

            int targetWidth = origWidth;
            int targetHeight = origHeight;

            boolean needResize = maxSide > maxDimension;
            if (needResize) {
                double ratio = (double) maxDimension / (double) maxSide;
                targetWidth = (int) Math.max(1, Math.round(origWidth * ratio));
                targetHeight = (int) Math.max(1, Math.round(origHeight * ratio));
            }

            // 检查是否有透明通道
            boolean hasAlpha = originalImage.getColorModel() != null && originalImage.getColorModel().hasAlpha();
            int imageType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

            BufferedImage targetImage = new BufferedImage(targetWidth, targetHeight, imageType);
            Graphics2D g2d = targetImage.createGraphics();

            // 设置高质量缩放渲染参数
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (!hasAlpha) {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, targetWidth, targetHeight);
            }

            g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String formatName = (extension != null && !extension.isBlank()) ? extension.toLowerCase() : "jpg";
            if ("jpeg".equals(formatName)) {
                formatName = "jpg";
            }

            // 若无透明通道或为 jpg，优先采用 JPEG 质量压缩
            if (!hasAlpha || "jpg".equals(formatName)) {
                writeJpegWithQuality(targetImage, baos, quality);
            } else {
                ImageIO.write(targetImage, formatName, baos);
            }

            byte[] outputBytes = baos.toByteArray();

            // 防反向膨胀保护：若无需缩小分辨率且压缩后体积反弹变大，则直接保留原图
            if (!needResize && outputBytes.length >= inputBytes.length) {
                log.info("图片原图已最简（原大小: {} bytes, 重新编码后: {} bytes），自动保留原始数据", inputBytes.length, outputBytes.length);
                return inputBytes;
            }

            return outputBytes;
        } catch (Exception e) {
            log.error("图片压缩缩放处理异常: {}", e.getMessage(), e);
            return inputBytes;
        }
    }

    /**
     * 对图片输入流进行等比缩放与质量压缩
     */
    public static byte[] compressAndResize(InputStream inputStream, String extension, int maxDimension, float quality) {
        try {
            byte[] inputBytes = inputStream.readAllBytes();
            return compressAndResize(inputBytes, extension, maxDimension, quality);
        } catch (Exception e) {
            log.error("读取图片流异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 为文件生成缩略图字节数据（最长边 200px）
     */
    public static byte[] generateThumbnail(File file, String extension) {
        try {
            BufferedImage originalImage = ImageIO.read(file);
            if (originalImage == null) {
                return null;
            }

            int origWidth = originalImage.getWidth();
            int origHeight = originalImage.getHeight();
            int maxSide = Math.max(origWidth, origHeight);

            int targetWidth = origWidth;
            int targetHeight = origHeight;
            if (maxSide > 200) {
                double ratio = 200.0 / (double) maxSide;
                targetWidth = (int) Math.max(1, Math.round(origWidth * ratio));
                targetHeight = (int) Math.max(1, Math.round(origHeight * ratio));
            }

            boolean hasAlpha = originalImage.getColorModel().hasAlpha();
            int imageType = hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

            BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, imageType);
            Graphics2D g2d = thumbnail.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (!hasAlpha) {
                g2d.setColor(Color.WHITE);
                g2d.fillRect(0, 0, targetWidth, targetHeight);
            }

            g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            String formatName = (extension != null && !extension.isBlank()) ? extension.toLowerCase() : "jpg";
            if ("jpeg".equals(formatName)) {
                formatName = "jpg";
            }
            if ("jpg".equals(formatName)) {
                writeJpegWithQuality(thumbnail, baos, 0.8f);
            } else {
                ImageIO.write(thumbnail, formatName, baos);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("生成图片缩略图异常: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 按指定压缩质量输出 JPEG 图片
     */
    private static void writeJpegWithQuality(BufferedImage image, ByteArrayOutputStream baos, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", baos);
            return;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }
}
