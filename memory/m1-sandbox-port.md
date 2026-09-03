---
name: m1-sandbox-port
description: M1-9/10 Docker 沙箱移植完成状态、与源项目的偏差清单、已知缺口（无 docker 真机验证）
metadata:
  type: project
---

M1-9（共享容器沙箱）+ M1-10（pids 巡检 + 空闲回收 + 容器自愈）已于 2026-09-02 完成，
全部落在 `agent-workspace` 模块（44 单测绿，全 reactor 104 绿）。任务顺序：M1-7/8 →
M1-9/10 已完 → 下一步 **M1-12（文件路由 + sse.ts 前端接入）** → M2。

与源项目（E:\apeloa\apeloa-agent，只读参考）的关键偏差：
- 共享容器名 `pi-shared-java`（源 `pi-shared`）；镜像默认 `pi-sandbox:dev` 可配。
- 容器只是 bash/文件沙箱，模型调用在 JVM 进程内 → **不注入 NEWAPI_*/pi env**。
- `SandboxStore` 是 SPI（uid 分配语义对齐 pi_linux_uid_seq START 100000）；
  DB/JPA 实现落在 M1-11 的 app-server；单测用 InMemorySandboxStore。
- sandboxes 表无 status 列，容器实况以 docker inspect 为真源（ensureRunning：
  null→run 重建 / paused→unpause / 其它非 running→start）。
- 文件工具（read/write/edit）不经容器，Java 进程直接读写（M1-12 做 HTTP 路由时对齐权限）。
- pids 巡检失败限频照搬源代码语义（时间制：30×告警冷却期 = 5h，源注释"~30min"与码不符，以码为准）。

**Why:** 这些偏差是后续 M1-11/12/14 与 E2E 验收的对齐基线，忘掉会造成双端不一致。

**How to apply:** 涉及沙箱/uid/容器的后续任务先读本条；Linux 生产关注两条实证：
(1) 数值 uid chown 必须用 `Files.setAttribute(path,"unix:uid",(int)uid)`（Integer→lchown，
  不能走 UserPrincipalLookupService——它查 /etc/passwd 名字，数值 uid 无条目会失败）；
(2) `jshell` 实证 Windows `relativize` 相同路径返回 nameCount=1 但空串的"空路径"，
  判根要用 `getNameCount()==0 || toString().isEmpty()` 双条件。

已知缺口：开发机无 docker → 全部为 fake-CLI 单测；真机集成（uid 隔离 Permission denied、
容器自愈、pids 巡检真探针）留待 docker 可用环境，属 M1-14 验收门。关联 [[m1-poc-runbook]]。
