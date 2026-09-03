package com.apeloa.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * M1-1 验收：Spring 上下文加载冒烟。test profile 用 H2 内存 + 关闭 Flyway，
 * 证明 Spring Boot + AgentScope auto-config 装配无异常。
 */
@SpringBootTest
@ActiveProfiles("test")
class ContextLoadTest {

    @Test
    void contextLoads() {
        // 仅验证上下文启动无异常
    }
}
