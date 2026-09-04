/**
 * agent-plugins —— Plugin Registry 管理面（上传/manifest 校验/状态机）+ 运行时装配
 * （MCP server 类动态装配、Skill 热载入）。secret 用信封加密（Jasypt），
 * 直接修掉源项目 plugin_user_configs.values 明文技术债。
 *
 * <p>骨架阶段：仅占位包。M2-1..4 落插件管理面与 MCP 运行时。
 */
package com.agent.timo.plugins;
