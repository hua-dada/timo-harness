package com.apeloa.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * apeloa-agent-java 主应用入口。
 *
 * <p>Spring Boot 4.0.4 + AgentScope 2.0.2 进程内 Agent 模型。
 * WebMVC + SSE（非 WebFlux，ADR #1）；JDK 21 虚拟线程承载 SSE 长连接。
 */
@SpringBootApplication
public class ApeloaAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApeloaAgentApplication.class, args);
    }
}
