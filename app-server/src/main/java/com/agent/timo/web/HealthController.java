package com.agent.timo.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查路由。M1 骨架仅提供 /api/health；会话/文件/插件等路由随各里程碑接入。
 */
@RestController
public class HealthController {

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "app", "timo-agent-java", "version", "0.1.0-SNAPSHOT");
    }
}
