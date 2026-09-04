package com.agent.timo.workspace.files;

/**
 * 文件路由业务异常：{@link Kind} 供 web 层映射 HTTP 状态码，{@code message} 即前端可见文案
 * （与源项目 apps/server/src/files/router.ts 各分支的 {@code reply.code(x).send({error})} 一一对应）。
 *
 * <p>不在本模块引入 HTTP 类型：agent-workspace 是纯业务模块，状态码映射留在 app-server。
 */
public class WorkspaceFileException extends RuntimeException {

    /** 失败类别；web 层据此映射状态码（NOT_FOUND→404、TOO_LARGE→413、IO_FAILED→500 等）。 */
    public enum Kind {
        /** 请求本身不合法（源 400「缺少文件」等）。 */
        BAD_REQUEST,
        /** 目标不存在（对应源 404「目录不存在」/「文件不存在」/「路径不存在」）。 */
        NOT_FOUND,
        /** 目标存在但不是普通文件（源 400「非文件」/「非文件，无法下载」）。 */
        NOT_A_FILE,
        /** 超出大小上限（源 413）。 */
        TOO_LARGE,
        /** 拒绝对 workspace 根做破坏性操作（源 400「不能删除根目录」）。 */
        ROOT_PROTECTED,
        /** 同名冲突无法自动改名（源 409）。 */
        CONFLICT,
        /** 底层 IO 失败（源 500「写入失败：…」/「删除失败：…」/「清空失败：…」）。 */
        IO_FAILED
    }

    private final Kind kind;

    public WorkspaceFileException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public WorkspaceFileException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
