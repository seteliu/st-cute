package com.stioc.cute.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Set;

/**
 * 图像处理工具类：提供图片等比缩放、质量压缩、缩略图生成等功能
 * 同时集中维护全平台统一的图片压缩规格常量，避免各调用方散落硬编码
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
     * 【平台统一规格】图片压缩最长边上限：超过此分辨率的图片等比缩小
     */
    public static final int MAX_DIMENSION = 2048;

    /**
     * 单张图片可解码的像素总数上限（约 1 亿像素）。
     * <p>
     * 防御「解压炸弹」型图片：PNG 等格式可将极大分辨率压缩到极小体积，
     * 一张声明 6 万×6 万（36 亿像素）的 PNG 文件本身可能不足 10MB，能绕过体积限制，
     * 但解码为 BufferedImage 需连续分配数 GB 堆内存，直接触发 OOM
     * （OOM 属 Error 而非 Exception，调用方的 catch (Exception) 无法兜住，会穿透到容器层）。
     * </p>
     * <p>
     * 该上限按像素总数而非单边计算：60000×1600 与 10000×10000 的显存/内存压力相当，
     * 只限单边仍可被"一维拉长"形态绕过。10000×10000 已远超实际业务需要（压缩目标仅 2048 边长）。
     * </p>
     */
    public static final long MAX_PIXEL_COUNT = 100_000_000L;

    /**
     * 【平台统一规格】图片有损压缩质量（0.0 ~ 1.0）
     */
    public static final float COMPRESS_QUALITY = 0.75f;

    /**
     * 【平台统一规格】跳过压缩的文件大小阈值：不超过该体积的图片直接原样使用。
     * 上传链路已压缩过的产物通常在几百 KB 内，跳过可避免二次有损压缩造成画质世代损失
     */
    public static final int SKIP_COMPRESS_THRESHOLD_BYTES = 1024 * 1024;

    /**
     * 【平台统一规格】缩略图最长边上限
     */
    public static final int THUMBNAIL_MAX_DIMENSION = 200;

    /**
     * 【平台统一规格】缩略图 JPEG 压缩质量
     */
    public static final float THUMBNAIL_QUALITY = 0.8f;

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
     * 按需智能压缩（全平台统一入口）：
     * 不超过 {@link #SKIP_COMPRESS_THRESHOLD_BYTES} 的图片跳过压缩（已压过或本身体积可控，避免二次有损）；
     * 其余大图按平台统一规格（{@link #MAX_DIMENSION} + {@link #COMPRESS_QUALITY}）压缩并统一转码为 JPEG。
     * GIF 压缩时仅取首帧（ImageIO 解码动画 GIF 天然只读首帧）
     *
     * @return 处理后的字节数组；跳过场景返回原字节
     */
    public static byte[] compressIfNeeded(byte[] inputBytes, String extension) {
        if (inputBytes == null || inputBytes.length == 0) {
            return inputBytes;
        }
        // 小文件跳过压缩：避免对已压缩产物二次有损
        if (inputBytes.length <= SKIP_COMPRESS_THRESHOLD_BYTES) {
            return inputBytes;
        }
        return compressAndResize(inputBytes, extension, MAX_DIMENSION, COMPRESS_QUALITY);
    }

    /**
     * 通过魔数探测图片字节的真实格式。
     *
     * @return 格式扩展名（jpg/png/gif/bmp/webp）；无法识别返回 null
     */
    public static String detectImageFormat(byte[] data) {
        if (data == null || data.length < 12) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if ((data[0] & 0xFF) == 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) {
            return "png";
        }
        // GIF: GIF87a / GIF89a
        if (data[0] == 0x47 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x38) {
            return "gif";
        }
        // BMP: 42 4D
        if (data[0] == 0x42 && data[1] == 0x4D) {
            return "bmp";
        }
        // WebP: RIFF....WEBP
        if (data[0] == 0x52 && data[1] == 0x49 && data[2] == 0x46 && data[3] == 0x46
                && data[8] == 0x57 && data[9] == 0x45 && data[10] == 0x42 && data[11] == 0x50) {
            return "webp";
        }
        return null;
    }

    /**
     * 对图片字节数组进行等比缩放与质量压缩，产物统一转码为 JPEG（全平台统一编码策略）。
     * GIF 压缩时仅取首帧（ImageIO 解码动画 GIF 天然只读首帧，动画丢失属预期）；
     * 若未缩放且重新编码后体积反弹变大，自动保留原图字节。
     * 注意：返回字节为 JPEG 时，调用方需将文件后缀/MIME 同步为 jpg（可用 {@link #detectImageFormat} 探测）
     *
     * @param inputBytes   原始图片字节数组
     * @param extension    图片后缀（如 jpg, png, bmp），仅作日志参考
     * @param maxDimension 最长边上限（例如 2048）
     * @param quality      压缩质量（0.0 ~ 1.0，推荐 0.75f）
     * @return 压缩处理后的 JPEG 字节数组；跳过场景返回原字节
     */
    public static byte[] compressAndResize(byte[] inputBytes, String extension, int maxDimension, float quality) {
        if (inputBytes == null || inputBytes.length == 0) {
            return inputBytes;
        }
        // 解码前先读图片头部声明的尺寸：体积极小的「高分辨率炸弹图」可绕过上传体积限制，
        // 但解码时需按分辨率分配巨额堆内存。此处提前拒绝，避免 OOM（OOM 无法被 catch(Exception) 兜住）
        if (isTooLargeByHeader(inputBytes, extension)) {
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

            // 统一输出 JPEG：JPEG 不支持透明通道，目标画布统一 RGB 并以白底填充（原图透明区域渲染为白色）
            BufferedImage targetImage = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = targetImage.createGraphics();

            // 设置高质量缩放渲染参数
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2d.setColor(Color.WHITE);
            g2d.fillRect(0, 0, targetWidth, targetHeight);
            g2d.drawImage(originalImage, 0, 0, targetWidth, targetHeight, null);
            g2d.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            writeJpegWithQuality(targetImage, baos, quality);
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
     * 依据图片头部声明的尺寸预判是否为「超大分辨率图片」。
     * <p>
     * 仅读取头部元数据，不进行整图解码，因此本身不占用大内存；
     * 相比解码后再检查尺寸，能在分配巨额堆内存之前就拦下来。
     * 任一格式解析失败或无法取得尺寸时返回 false（放行），由后续解码流程自行处理，
     * 不影响正常图片与其格式兼容性。
     * </p>
     *
     * @param inputBytes 图片字节数组
     * @param extension  图片后缀（仅用于日志）
     * @return true 表示分辨率超限，应跳过压缩处理
     */
    private static boolean isTooLargeByHeader(byte[] inputBytes, String extension) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(inputBytes))) {
            if (iis == null) {
                return false;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(iis, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                long pixels = (long) width * (long) height;
                if (pixels > MAX_PIXEL_COUNT) {
                    log.warn("图片分辨率超限，跳过解码以规避 OOM: ext={}, 尺寸={}x{}（约 {} 亿像素），上限 {} 亿像素",
                            extension, width, height, pixels / 100_000_000.0, MAX_PIXEL_COUNT / 100_000_000.0);
                    return true;
                }
                return false;
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            // 头部探测失败不影响正常流程：交由解码路径处理
            log.debug("图片头部尺寸探测失败，继续常规解码流程: ext={}, 原因={}", extension, e.getMessage());
            return false;
        }
    }

    /**
     * 为文件生成缩略图字节数据（最长边 200px）
     */
    public static byte[] generateThumbnail(File file, String extension) {
        try {
            // 与压缩路径同一防护：超大分辨率图片解码即 OOM，且 OOM 无法被下方的 catch(Exception) 兜住
            if (isTooLargeByHeader(Files.readAllBytes(file.toPath()), extension)) {
                return null;
            }
            BufferedImage originalImage = ImageIO.read(file);
            if (originalImage == null) {
                return null;
            }

            int origWidth = originalImage.getWidth();
            int origHeight = originalImage.getHeight();
            int maxSide = Math.max(origWidth, origHeight);

            int targetWidth = origWidth;
            int targetHeight = origHeight;
            if (maxSide > THUMBNAIL_MAX_DIMENSION) {
                double ratio = (double) THUMBNAIL_MAX_DIMENSION / (double) maxSide;
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
                writeJpegWithQuality(thumbnail, baos, THUMBNAIL_QUALITY);
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
