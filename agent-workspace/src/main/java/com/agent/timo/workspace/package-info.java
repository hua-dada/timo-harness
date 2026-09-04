/**
 * agent-workspace —— 共享容器 Docker 沙箱（M1-9/10）与后续文件路由（M1-12）。
 *
 * <p>模型（移植自源项目 apps/server/src/sandbox/，隔离语义不变）：
 * <ul>
 *   <li>所有用户复用同一容器 {@code pi-shared-java}（{@link SandboxManager}），
 *       命令经 {@code docker exec --user uid:gid} 以每用户独立 Linux uid 执行
 *       （{@link DockerExecExecutor}，BashTool 的沙箱后端）；
 *   <li>文件隔离靠 DAC：per-user 目录 0700 + 数值 uid chown（{@link SecureDirs}），
 *       宿主 sandboxRoot 整体挂载容器 /data（{@link SandboxPaths}）；
 *   <li>容器实况以 docker inspect 为真源（无内存状态机）：缺失→重建、paused→unpause、exited→start；
 *   <li>治理：pids 配额巡检 + 空闲 destroy 回收（{@link com.timo.agent.workspace.pids} /
 *       {@link ReclaimScheduler}），refCount&gt;0 时拒绝一切降级操作。
 * </ul>
 *
 * <p>{@link SandboxStore} 是 sandboxes 表访问 SPI（uid 自
 * {@code pi_linux_uid_seq} 分配），DB 实现随 M1-11 落 app-server；local 开发模式（无 docker）
 * 只建目录不碰容器。
 */
package com.agent.timo.workspace;

