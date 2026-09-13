package com.quanta.demo0.controller.user;

import com.quanta.demo0.annotation.RateLimit;
import com.quanta.demo0.result.Result;
import com.quanta.demo0.utils.AliOssUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/common")
@Slf4j
public class CommonController {

    private static final Set<String> ALLOWED_IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"
    );

    @Autowired
    private AliOssUtil aliOssUtil;

    /**
     * 文件上传接口
     *
     * @return
     */
    @RateLimit(
            scene = "file-upload",
            limit = 10,
            windowSeconds = 60
    )
    @PostMapping("/upload")
    public Result<String> upload(MultipartFile file) {
        log.info("文件上传：{}", file);
        if (file == null || file.isEmpty()) {
            log.warn("文件上传失败：文件为空");
            return Result.error("文件为空");
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            log.warn("文件上传失败：文件大小超过限制, size={} bytes", file.getSize());
            return Result.error("文件大小超过10MB");
        }

        try {
            byte[] bytes = file.getBytes();
            if (!isAllowedImage(file, bytes)) {
                log.warn("文件上传失败：文件类型校验不通过, filename={}, contentType={}",
                        file.getOriginalFilename(), file.getContentType());
                return Result.error("文件类型错误");
            }
            String extension = resolveImageExtension(file, bytes);
            String newName = UUID.randomUUID().toString() + extension;
            String filePath = aliOssUtil.upload(bytes, newName);
            log.info("文件上传成功：filename={}, objectName={}", file.getOriginalFilename(), newName);
            return Result.success(filePath);
        } catch (IOException e) {
            log.error("文件读取失败", e);
            return Result.error("文件上传失败");
        } catch (RuntimeException e) {
            log.error("OSS 上传失败", e);
            return Result.error("文件上传失败，请稍后重试");
        }
    }

    /**
     * 微信小程序 wx.uploadFile 常将 Content-Type 标为 application/octet-stream，
     * 不能仅依赖 MIME，需结合扩展名或文件头魔数判断。
     */
    private static boolean isAllowedImage(MultipartFile file, byte[] bytes) {
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            return true;
        }
        String ext = extensionFromFilename(file.getOriginalFilename());
        if (ext != null && ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
            return true;
        }
        return hasImageMagicBytes(bytes);
    }

    private static String resolveImageExtension(MultipartFile file, byte[] bytes) {
        String ext = extensionFromFilename(file.getOriginalFilename());
        if (ext != null && ALLOWED_IMAGE_EXTENSIONS.contains(ext)) {
            return ext;
        }
        String fromMagic = extensionFromMagicBytes(bytes);
        if (fromMagic != null) {
            return fromMagic;
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            String lower = contentType.toLowerCase(Locale.ROOT);
            if (lower.contains("jpeg") || lower.contains("jpg")) {
                return ".jpg";
            }
            if (lower.contains("png")) {
                return ".png";
            }
            if (lower.contains("gif")) {
                return ".gif";
            }
            if (lower.contains("webp")) {
                return ".webp";
            }
            if (lower.contains("bmp")) {
                return ".bmp";
            }
        }
        return ".jpg";
    }

    private static boolean hasImageMagicBytes(byte[] bytes) {
        return extensionFromMagicBytes(bytes) != null;
    }

    private static String extensionFromMagicBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 3) {
            return null;
        }
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        int b2 = bytes[2] & 0xFF;
        if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) {
            return ".jpg";
        }
        if (bytes.length >= 8
                && bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return ".png";
        }
        if (bytes.length >= 3 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') {
            return ".gif";
        }
        if (bytes.length >= 2 && bytes[0] == 'B' && bytes[1] == 'M') {
            return ".bmp";
        }
        if (bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static String extensionFromFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot).toLowerCase(Locale.ROOT);
    }
}
