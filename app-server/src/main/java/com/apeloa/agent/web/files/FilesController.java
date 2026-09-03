package com.apeloa.agent.web.files;

import com.apeloa.agent.web.auth.CurrentUserProvider;
import com.apeloa.agent.web.auth.UnauthenticatedException;
import com.apeloa.agent.workspace.files.WorkspaceFileException;
import com.apeloa.agent.workspace.files.WorkspaceFileException.Kind;
import com.apeloa.agent.workspace.files.WorkspaceFileService;
import com.apeloa.agent.workspace.files.WorkspaceFileService.Download;
import com.apeloa.agent.workspace.files.WorkspaceFileService.Entry;
import com.apeloa.agent.workspace.files.WorkspaceFileService.Preview;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

/**
 * 文件 REST 路由（M1-12），移植自源项目 {@code apps/server/src/files/router.ts}：
 * list / content(GET·PUT·PATCH·DELETE) / workspace / download / preview / upload，
 * 全部限定在当前用户沙箱 workspace 内。
 *
 * <p>路径同时挂 {@code /files/**}（前端 files-api.ts + preview.ts 原样复用，零改动）与
 * {@code /api/files/**}（spec §3.2 的 /api 命名空间）。spec 表里的 {@code POST/PATCH} 写语义
 * 由 {@code PUT|PATCH /content} 覆盖。
 *
 * <p>鉴权：{@link CurrentUserProvider} 取 userId，null → 401；M1-3 前是开发态占位实现。
 * 异常 → 状态码映射见 {@link FilesApiExceptionHandler}。
 */
@RestController
@RequestMapping({"/files", "/api/files"})
public class FilesController {

    private static final String PREVIEW_MARKER = "/preview/";

    private final WorkspaceFileService files;
    private final CurrentUserProvider currentUser;

    public FilesController(WorkspaceFileService files, CurrentUserProvider currentUser) {
        this.files = files;
        this.currentUser = currentUser;
    }

    /** 目录列表响应：{@code {entries:[{name,type}]}}。 */
    public record ListingResponse(List<Entry> entries) {
    }

    /** 读文件响应：{@code {path, content}}（path 回显客户端传入的相对路径）。 */
    public record ContentResponse(String path, String content) {
    }

    /** 写 / 删 / 上传响应：{@code {path, ok:true}}。 */
    public record OkResponse(String path, boolean ok) {
    }

    /** 清空响应：{@code {ok:true, removed:[…]}}。 */
    public record ClearResponse(boolean ok, List<String> removed) {
    }

    /** 写文件请求体：{@code {path, content}}；缺字段按空串处理（对齐源）。 */
    public record WriteRequest(String path, String content) {
    }

    /** 列目录（单层，过滤隐藏文件；放行 .pi 以便用户查看 agent 内部状态）。 */
    @GetMapping("/list")
    public ListingResponse list(@RequestParam(name = "path", defaultValue = "") String path) {
        return new ListingResponse(files.list(requireUserId(), path));
    }

    /** 读文件（utf8；>1MB 拒绝）。 */
    @GetMapping("/content")
    public ContentResponse read(@RequestParam(name = "path", defaultValue = "") String path) {
        return new ContentResponse(path, files.readText(requireUserId(), path));
    }

    /** 写文件（utf8；>1MB 拒绝；父目录自动建）。PATCH 为 spec §3.2 的别名。 */
    @RequestMapping(path = "/content", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public OkResponse write(@RequestBody(required = false) WriteRequest body) {
        String userId = requireUserId();
        String path = body == null || body.path() == null ? "" : body.path();
        String content = body == null || body.content() == null ? "" : body.content();
        files.writeText(userId, path, content);
        return new OkResponse(path, true);
    }

    /** 删文件 / 递归删目录（拒绝删 workspace 根）。 */
    @DeleteMapping("/content")
    public OkResponse delete(@RequestParam(name = "path", defaultValue = "") String path) {
        files.delete(requireUserId(), path);
        return new OkResponse(path, true);
    }

    /** 清空工作空间：删根下所有非隐藏条目，保留 .pi 等隐藏项。 */
    @DeleteMapping("/workspace")
    public ClearResponse clear() {
        return new ClearResponse(true, files.clearWorkspace(requireUserId()));
    }

    /** 下载文件（仅文件，二进制流；>10MB 拒绝）。 */
    @GetMapping("/download")
    public ResponseEntity<byte[]> download(
            @RequestParam(name = "path", defaultValue = "") String path) {
        Download file = files.download(requireUserId(), path);
        // RFC 5987：filename*=UTF-8''<enc> 兼容中文文件名（浏览器实际用前端 a.download）
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(file.bytes());
    }

    /**
     * 预览：path-style 暴露 workspace 内静态文件（HTML 及其引用的 CSS/JS/图片）。
     *
     * <p>用通配 {@code /**} 而非 query，让 iframe 内 {@code <link href="style.css">} 等相对路径
     * 正确解析为 {@code /files/preview/style.css}（query 会让相对路径错位）。
     * {@code no-store} 防缓存旧版本（配合前端 iframe key remount 双保险）。
     */
    @GetMapping("/preview/**")
    public ResponseEntity<byte[]> preview(HttpServletRequest request) {
        String userId = requireUserId();
        Preview preview = files.preview(userId, previewRelPath(request));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, preview.contentType())
                .cacheControl(CacheControl.noStore())
                .body(preview.bytes());
    }

    /** 上传到 workspace 根（multipart/form-data，field 名 "file"）：同名自动改名，>10MB 拒绝。 */
    @PostMapping("/upload")
    public OkResponse upload(
            @RequestParam(name = "file", required = false) MultipartFile file) {
        String userId = requireUserId();
        if (file == null) {
            throw new WorkspaceFileException(Kind.BAD_REQUEST, "缺少文件");
        }
        if (file.getSize() > WorkspaceFileService.MAX_UPLOAD_BYTES) {
            throw new WorkspaceFileException(Kind.TOO_LARGE, FilesApiExceptionHandler.tooLargeMessage());
        }
        String rel;
        try {
            rel = files.upload(userId, file.getOriginalFilename(), file.getInputStream());
        } catch (IOException e) {
            throw new WorkspaceFileException(Kind.IO_FAILED, "上传失败：" + e, e);
        }
        return new OkResponse(rel, true);
    }

    /** 当前登录用户；未登录抛 {@link UnauthenticatedException}（→ 401 未登录）。 */
    private String requireUserId() {
        String userId = currentUser.currentUserId();
        if (userId == null) {
            throw new UnauthenticatedException();
        }
        return userId;
    }

    /**
     * 取 {@code /preview/} 之后的相对路径并解码。
     *
     * <p>用 requestURI 里的 {@code /preview/} 标记切分而非依赖 handler 匹配细节，
     * 两个类级前缀（/files、/api/files）与任意 context-path 都成立。
     * {@link UriUtils#decode} 而非 URLDecoder：后者会把 {@code +} 解成空格，路径语义下是错的。
     */
    static String previewRelPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int marker = uri.indexOf(PREVIEW_MARKER);
        if (marker < 0) {
            return "";
        }
        return UriUtils.decode(uri.substring(marker + PREVIEW_MARKER.length()), StandardCharsets.UTF_8);
    }
}
