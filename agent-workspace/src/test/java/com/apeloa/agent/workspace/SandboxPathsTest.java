package com.apeloa.agent.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SandboxPathsTest {

    @TempDir
    Path tmp;

    @Test
    void 用户目录布局() {
        SandboxPaths p = new SandboxPaths(tmp.resolve("sb"));
        assertThat(p.userWorkspaceDir("u1")).isEqualTo(tmp.resolve("sb").resolve("u1").resolve("workspace"));
        assertThat(p.userHomeDir("u1")).isEqualTo(tmp.resolve("sb").resolve("u1").resolve("home"));
        assertThat(p.userRootDir("u1")).isEqualTo(tmp.resolve("sb").resolve("u1"));
        assertThat(p.tmpByUidDir(100000L).toString())
                .endsWith("tmp-by-uid" + java.io.File.separator + "100000");
        assertThat(p.containerUserDir("u1", "workspace")).isEqualTo("/data/u1/workspace");
        assertThat(p.containerUserDir("u1", "home")).isEqualTo("/data/u1/home");
        assertThat(p.containerTmpByUidDir(100000L)).isEqualTo("/data/tmp-by-uid/100000");
    }

    @Test
    void toContainerPath挂载直映射() {
        SandboxPaths p = new SandboxPaths(tmp.resolve("sb"));
        assertThat(p.toContainerPath(p.userWorkspaceDir("u1").resolve("notes.txt")))
                .isEqualTo("/data/u1/workspace/notes.txt");
        assertThat(p.toContainerPath(p.sandboxRoot())).isEqualTo("/data");
    }

    @Test
    void toContainerPath越界抛错() {
        SandboxPaths p = new SandboxPaths(tmp.resolve("sb"));
        assertThatThrownBy(() -> p.toContainerPath(tmp.resolve("elsewhere")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不在 sandboxRoot");
    }

    @Test
    void ensureDir递归创建() throws IOException {
        Path deep = tmp.resolve("a").resolve("b").resolve("c");
        SandboxPaths.ensureDir(deep);
        assertThat(Files.isDirectory(deep)).isTrue();
    }
}
