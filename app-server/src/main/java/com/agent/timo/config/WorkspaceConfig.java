package com.agent.timo.config;

import com.agent.timo.workspace.SandboxPaths;
import com.agent.timo.workspace.files.WorkspaceFileService;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 沙箱路径与文件服务装配（M1-12）。
 *
 * <p>沙箱根取 {@code app.sandbox.root}（application.yml 里由 {@code SANDBOX_ROOT} 环境变量注入，
 * 沿用源项目的 env 名），相对路径按进程工作目录展开。每用户目录 {@code <root>/<userId>/workspace}
 * 按需创建；属主与 0700 权限由 {@code SecureDirs} 在 {@code SandboxManager.acquire} 时收口（M1-9）。
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfig.class);

    @Bean
    SandboxPaths sandboxPaths(@Value("${app.sandbox.root:sandboxes}") String root) {
        Path resolved = Path.of(root).toAbsolutePath().normalize();
        log.info("沙箱根目录：{}", resolved);
        return new SandboxPaths(resolved);
    }

    @Bean
    WorkspaceFileService workspaceFileService(SandboxPaths sandboxPaths) {
        return new WorkspaceFileService(sandboxPaths);
    }
}
