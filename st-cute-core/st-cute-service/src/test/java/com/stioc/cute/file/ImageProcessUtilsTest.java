package com.stioc.cute.file;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图片压缩规格与三档决策单元测试。
 * 覆盖：小图跳过、分辨率超标强制压缩、体积超标试压与收益闸门、头部尺寸探测。
 */
class ImageProcessUtilsTest {

    /**
     * 构造指定尺寸的随机噪点 JPEG 字节（ImageIO 默认质量编码；噪点使 JPEG 难以压缩，便于验证体积相关分支）
     */
    private byte[] noisyJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(42L);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, random.nextInt(0xFFFFFF));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * 构造指定尺寸的纯色 PNG 字节（纯色压缩率极高，体积很小）
     */
    private byte[] solidPng(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private int[] readSize(byte[] data) throws Exception {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        return new int[]{img.getWidth(), img.getHeight()};
    }

    @Nested
    @DisplayName("compressIfNeeded 三档决策")
    class CompressDecisionTests {

        @Test
        @DisplayName("档位二：小图（体积低于阈值且分辨率达标）原样返回，不做任何处理")
        void smallImageSkipped() throws Exception {
            byte[] small = solidPng(100, 100);
            assertTrue(small.length <= ImageProcessUtils.SKIP_COMPRESS_THRESHOLD_BYTES,
                    "用例前提：构造图体积应低于跳过阈值，实际 " + small.length);

            byte[] result = ImageProcessUtils.compressIfNeeded(small, "png");

            // 必须原样返回同一引用，证明未发生解码与重编码
            assertSame(small, result);
        }

        @Test
        @DisplayName("档位一：分辨率超标强制压缩至目标规格，即便体积增大也采用结果")
        void oversizeImageForceCompressed() throws Exception {
            // 构造超过 MAX_DIMENSION 的纯色图：体积小但长边超标，必须被缩到目标规格
            int target = ImageProcessUtils.MAX_DIMENSION;
            byte[] oversize = solidPng(target + 500, target + 500);
            int[] before = readSize(oversize);
            assertEquals(target + 500, Math.max(before[0], before[1]));

            byte[] result = ImageProcessUtils.compressIfNeeded(oversize, "png");

            int[] after = readSize(result);
            assertEquals(target, Math.max(after[0], after[1]),
                    "分辨率超标必须被缩放到 " + target);
        }

        @Test
        @DisplayName("档位三：分辨率达标但体积超标，收益达阈值时采用压缩结果")
        void oversizeBytesWithGainCompressed() throws Exception {
            // 大尺寸噪点 PNG：体积远超阈值且 PNG 转 JPEG 收益显著，应被压缩
            byte[] noisy = noisyJpeg(2000, 2000);
            assertTrue(noisy.length > ImageProcessUtils.SKIP_COMPRESS_THRESHOLD_BYTES,
                    "用例前提：构造成图体积应高于跳过阈值，实际 " + noisy.length);
            int[] size = readSize(noisy);
            assertTrue(Math.max(size[0], size[1]) <= ImageProcessUtils.MAX_DIMENSION,
                    "用例前提：分辨率应达标，实际 " + size[0] + "x" + size[1]);

            byte[] result = ImageProcessUtils.compressIfNeeded(noisy, "jpg");

            double gain = 1.0 - (double) result.length / noisy.length;
            assertTrue(gain >= ImageProcessUtils.MIN_COMPRESS_GAIN_RATIO,
                    "收益应达到阈值，实际收益 " + Math.round(gain * 100) + "%");
        }

        @Test
        @DisplayName("档位三：收益不足阈值时保留原图（原引用返回）")
        void insufficientGainKeepsOriginal() throws Exception {
            // 已高度优化的 JPEG：重编码收益极低甚至为负，应保留原图
            byte[] alreadyOptimized = noisyJpeg(1600, 1600);
            int[] size = readSize(alreadyOptimized);
            assertTrue(Math.max(size[0], size[1]) <= ImageProcessUtils.MAX_DIMENSION,
                    "用例前提：分辨率应达标");

            byte[] result = ImageProcessUtils.compressIfNeeded(alreadyOptimized, "jpg");

            if (result.length < alreadyOptimized.length
                    && 1.0 - (double) result.length / alreadyOptimized.length >= ImageProcessUtils.MIN_COMPRESS_GAIN_RATIO) {
                // 恰好收益达标则正常采用，不算失败
                return;
            }
            assertSame(alreadyOptimized, result, "收益不足时应原样返回原图字节");
        }

        @Test
        @DisplayName("空输入与 null 输入安全返回")
        void emptyInputSafe() {
            assertNull(ImageProcessUtils.compressIfNeeded(null, "png"));
            byte[] empty = new byte[0];
            assertSame(empty, ImageProcessUtils.compressIfNeeded(empty, "png"));
        }
    }

    @Nested
    @DisplayName("压缩规格常量")
    class SpecTests {

        @Test
        @DisplayName("压缩目标与收益阈值符合平台规格")
        void specValues() {
            assertEquals(2048, ImageProcessUtils.MAX_DIMENSION);
            assertEquals(0.75f, ImageProcessUtils.COMPRESS_QUALITY);
            assertEquals(512 * 1024, ImageProcessUtils.SKIP_COMPRESS_THRESHOLD_BYTES);
            assertEquals(0.05, ImageProcessUtils.MIN_COMPRESS_GAIN_RATIO);
        }
    }

    @Nested
    @DisplayName("图片格式探测")
    class FormatDetectTests {

        @Test
        @DisplayName("魔数探测可识别 JPEG 与 PNG")
        void detectFormats() throws Exception {
            assertEquals("jpg", ImageProcessUtils.detectImageFormat(noisyJpeg(64, 64)));
            assertEquals("png", ImageProcessUtils.detectImageFormat(solidPng(64, 64)));
        }

        @Test
        @DisplayName("超大像素图片：头部预检拦截，跳过解码以规避 OOM")
        void hugePixelImageSkipped() throws Exception {
            // 构造声明 40000×40000（16 亿像素）的图片：仅写头部元数据即可触达像素预检，
            // 关键在于压缩入口只读头部尺寸、不做整图解码，故不会真的分配 16 亿像素内存
            byte[] huge = fakeJpegWithDeclaredSize(40000, 40000);

            byte[] result = ImageProcessUtils.compressIfNeeded(huge, "jpg");

            // 像素炸弹防御触发后不做解码，原样返回（避免 OOM 穿透）
            assertSame(huge, result, "超像素图片应跳过压缩原样返回");
        }

        /**
         * 构造仅含合法 JPEG 头部（SOI + SOF0 帧头声明尺寸）的伪图片，无实际扫描数据。
         * 用于验证基于头部元数据的像素预检，避免真实解码时的巨额内存分配。
         */
        private byte[] fakeJpegWithDeclaredSize(int width, int height) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{(byte) 0xFF, (byte) 0xD8});                    // SOI
            out.write(new byte[]{(byte) 0xFF, (byte) 0xC0});                    // SOF0
            out.write(new byte[]{0x00, 0x11});                                  // 段长度 17
            out.write(new byte[]{0x08});                                        // 精度 8bit
            out.write(new byte[]{(byte) (height >> 8), (byte) height});          // 高
            out.write(new byte[]{(byte) (width >> 8), (byte) width});            // 宽
            out.write(new byte[]{0x03});                                        // 分量数 3
            out.write(new byte[]{0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00});
            out.write(new byte[]{(byte) 0xFF, (byte) 0xD9});                    // EOI
            return out.toByteArray();
        }
    }
}
