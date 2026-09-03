/**
 * 用户 workspace 文件操作（M1-12）：list / 读写 / 删除 / 清空 / 下载 / 预览 / 上传。
 *
 * <p>移植自源项目 {@code apps/server/src/files/}（router.ts + upload.ts + safe-path.ts）的业务部分。
 * HTTP 表面（状态码、Content-Disposition、multipart）在 app-server 的 {@code web.files} 包，
 * 本包只做纯文件语义 + 大小上限 + 防穿越，便于脱离 Spring 单测。
 *
 * <p>不含源项目的加解密（CRYPTO_* + rustfs 外部服务）分支：M1~M3 无对应任务。
 */
package com.apeloa.agent.workspace.files;
