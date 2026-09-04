package com.agent.timo.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.agent.timo.core.bash.BashTool;
import com.agent.timo.core.bash.LocalCommandExecutor;
import io.agentscope.core.tool.Toolkit;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 实证 @Tool 方法经 Toolkit.registerTool(Object) 反射注册成功（不触模型）。 */
class ToolkitRegistrationTest {

    @TempDir
    Path ws;

    @Test
    void 四个coding工具都能注册进Toolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new ReadTool(ws));
        toolkit.registerTool(new WriteTool(ws));
        toolkit.registerTool(new EditTool(ws));
        toolkit.registerTool(new BashTool(ws, new LocalCommandExecutor()));

        assertThat(toolkit.getToolNames())
                .contains("read_file", "list_dir", "write_file", "edit_file", "bash");
        assertThat(toolkit.getToolSchemas())
                .extracting(io.agentscope.core.model.ToolSchema::getName)
                .contains("read_file", "write_file", "edit_file", "bash");
    }
}
