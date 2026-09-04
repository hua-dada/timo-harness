package com.agent.timo.poc;

import com.agent.timo.core.bash.BashTool;
import com.agent.timo.core.bash.LocalCommandExecutor;
import com.agent.timo.core.tools.EditTool;
import com.agent.timo.core.tools.ReadTool;
import com.agent.timo.core.tools.WriteTool;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * poc profile：把 agent-core 的 coding 工具注册进 AgentScope 自动装配的 Toolkit，
 * 实证 {@code @Tool} 反射注册 + SafePath 约束在真实模型链路里成立（M1-7/8 集成验证）。
 *
 * <p>用 {@link BeanPostProcessor} 在 Toolkit bean 初始化后立刻注册：ReActAgent bean 依赖
 * Toolkit 构造，若在其构造之后再注册（普通 @Bean 注入 Toolkit 的方式），Agent 看不到工具
 * （2026-09-02 实测：模型回复"没有提供工具"）。BPP 保证注册发生在 Agent 构造之前。
 *
 * <p>workspace 用项目内 {@code poc-workspace/}。BashTool 一并注册但 PoC prompt 不主动触发；
 * 危险命令走 ASK 挂起，等 M1-6 HITL 桥才有应答通道。
 */
@Configuration(proxyBeanMethods = false)
@Profile("poc")
public class PocToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(PocToolsConfig.class);

    /** static：BPP 须早于普通 bean 初始化，避免配置类自身被提前实例化。 */
    @Bean
    static PocToolkitCustomizer pocToolkitCustomizer() {
        return new PocToolkitCustomizer();
    }

    static class PocToolkitCustomizer implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof Toolkit toolkit) {
                Path workspace;
                try {
                    workspace = Path.of("poc-workspace").toAbsolutePath().normalize();
                    Files.createDirectories(workspace);
                } catch (Exception e) {
                    throw new IllegalStateException("无法创建 poc-workspace", e);
                }
                toolkit.registerTool(new ReadTool(workspace));
                toolkit.registerTool(new WriteTool(workspace));
                toolkit.registerTool(new EditTool(workspace));
                toolkit.registerTool(new BashTool(workspace, new LocalCommandExecutor()));
                log.info("PoC 工具已注册到 Toolkit（workspace={}）：{}", workspace, toolkit.getToolNames());
            }
            return bean;
        }
    }
}
