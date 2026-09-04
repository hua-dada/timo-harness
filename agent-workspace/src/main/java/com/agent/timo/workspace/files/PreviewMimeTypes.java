package com.agent.timo.workspace.files;

import java.util.Locale;
import java.util.Map;

/**
 * 预览端点 MIME 映射（照搬源项目 files/router.ts 的 MIME_BY_EXT）：
 * HTML 引用的常见资源类型，缺省二进制流，让 {@code <link>/<script>/<img>} 引用的相对路径
 * 资源被浏览器正确渲染。
 */
public final class PreviewMimeTypes {

    /** 未识别扩展名的缺省类型。 */
    public static final String DEFAULT = "application/octet-stream";

    private static final Map<String, String> BY_EXT = Map.ofEntries(
            Map.entry(".html", "text/html; charset=utf-8"),
            Map.entry(".htm", "text/html; charset=utf-8"),
            Map.entry(".md", "text/markdown; charset=utf-8"),
            Map.entry(".markdown", "text/markdown; charset=utf-8"),
            Map.entry(".css", "text/css; charset=utf-8"),
            Map.entry(".js", "text/javascript; charset=utf-8"),
            Map.entry(".mjs", "text/javascript; charset=utf-8"),
            Map.entry(".json", "application/json; charset=utf-8"),
            Map.entry(".map", "application/json; charset=utf-8"),
            Map.entry(".svg", "image/svg+xml"),
            Map.entry(".png", "image/png"),
            Map.entry(".jpg", "image/jpeg"),
            Map.entry(".jpeg", "image/jpeg"),
            Map.entry(".gif", "image/gif"),
            Map.entry(".webp", "image/webp"),
            Map.entry(".ico", "image/x-icon"),
            Map.entry(".woff", "font/woff"),
            Map.entry(".woff2", "font/woff2"),
            Map.entry(".ttf", "font/ttf"),
            Map.entry(".otf", "font/otf"));

    private PreviewMimeTypes() {
    }

    /**
     * 按文件名扩展名取 Content-Type；无扩展名或未识别返回 {@link #DEFAULT}。
     *
     * <p>{@code dot <= 0} 对齐 Node {@code extname}：".bashrc" 视为无扩展名。
     */
    public static String forFilename(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) {
            return DEFAULT;
        }
        return BY_EXT.getOrDefault(filename.substring(dot).toLowerCase(Locale.ROOT), DEFAULT);
    }
}
