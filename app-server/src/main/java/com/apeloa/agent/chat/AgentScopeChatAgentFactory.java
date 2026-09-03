package com.apeloa.agent.chat;

import com.apeloa.agent.chat.persist.DbAgentStateStore;
import com.apeloa.agent.core.bash.BashTool;
import com.apeloa.agent.core.bash.LocalCommandExecutor;
import com.apeloa.agent.core.tools.EditTool;
import com.apeloa.agent.core.tools.ReadTool;
import com.apeloa.agent.core.tools.WriteTool;
import com.apeloa.agent.workspace.SandboxPaths;
import com.apeloa.agent.workspace.files.WorkspaceFileService;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.Toolkit;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 生产实现：按会话组装 {@code ReActAgent}（工具绑定该用户的沙箱 workspace）。
 *
 * <p>工具必须在 build 之前注册进 Toolkit：Agent 构造时快照工具表，构造后再注册模型看不到
 * （M1-4 实测，见 {@code PocToolsConfig} 注释）。
 *
 * <p>Model bean 由 {@code agentscope.openai.*} 装配；未配时 {@link ObjectProvider} 拿不到，
 * 直接抛 {@link ModelNotConfiguredException}（web → 503），而不是建一个必然失败的 Agent。
 */
@Component
public class AgentScopeChatAgentFactory implements ChatAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentScopeChatAgentFactory.class);

    private final ObjectProvider<Model> modelProvider;
    private final WorkspaceFileService workspaceFiles;
    private final DbAgentStateStore stateStore;
    private final String sysPrompt;
    private final int maxIters;

    public AgentScopeChatAgentFactory(
            ObjectProvider<Model> modelProvider,
            WorkspaceFileService workspaceFiles,
            DbAgentStateStore stateStore,
            @Value("${app.chat.sys-prompt:你是 apeloa-agent 的编码助手，可用工具读写与执行工作区文件。}")
                    String sysPrompt,
            @Value("${app.chat.max-iters:20}") int maxIters) {
        this.modelProvider = modelProvider;
        this.workspaceFiles = workspaceFiles;
        this.stateStore = stateStore;
        this.sysPrompt = sysPrompt;
        this.maxIters = maxIters;
    }

    @Override
    public ChatAgent create(String userId, String sessionId) {
        Model model = modelProvider.getIfAvailable();
        if (model == null) {
            throw new ModelNotConfiguredException();
        }
        // workspaceOf 内含 isValidUserId 校验：非法身份在此即断，不会落到沙箱根之外。
        Path workspace = workspaceFiles.workspaceOf(userId);
        try {
            SandboxPaths.ensureDir(workspace);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建用户 workspace：" + workspace, e);
        }

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadTool(workspace));
        toolkit.registerTool(new WriteTool(workspace));
        toolkit.registerTool(new EditTool(workspace));
        toolkit.registerTool(new BashTool(workspace, new LocalCommandExecutor()));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("apeloa-agent")
                        .sysPrompt(sysPrompt)
                        .model(model)
                        .toolkit(toolkit)
                        .maxIters(maxIters)
                        // M1-11：上下文随 (userId, sessionId) 落 agent_states，框架每次 call
                        // 开头重载、结尾覆盖写——驱逐/重启后的记忆恢复全部由框架完成。
                        .stateStore(stateStore)
                        .defaultSessionId(sessionId)
                        .build();
        log.info("会话 Agent 已就绪：session={} user={} workspace={} tools={}",
                sessionId, userId, workspace, toolkit.getToolNames());
        return new AgentScopeChatAgent(agent, userId, sessionId);
    }

    /**
     * {@code ReActAgent} + 本会话 {@code RuntimeContext} 的绑定。对话记忆随 (userId, sessionId)
     * 存在框架的 AgentState 里，故每次 run 都必须带同一个 context，否则续跑读不到挂起的工具调用。
     */
    record AgentScopeChatAgent(ReActAgent agent, String userId, String sessionId)
            implements ChatAgent {

        @Override
        public reactor.core.publisher.Flux<AgentEvent> stream(Msg message) {
            return agent.streamEvents(message, context());
        }

        @Override
        public void interrupt() {
            agent.interrupt(userId, sessionId);
        }

        /** entries 落盘的权威源：框架当前已提交的上下文（防御性副本，见 AgentState.getContext）。 */
        @Override
        public List<Msg> committedContext() {
            AgentState state = agent.getAgentState(userId, sessionId);
            return state == null ? List.of() : state.getContext();
        }

        private RuntimeContext context() {
            return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
        }
    }
}
