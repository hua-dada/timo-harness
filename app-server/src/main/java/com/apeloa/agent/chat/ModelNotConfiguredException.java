package com.apeloa.agent.chat;

/**
 * 未装配模型：{@code agentscope.model.provider} 缺失时无 Model bean，无法建 Agent。
 * web 层映射 503 {@code {"error":"模型未配置"}}，与「服务器已就绪但能力不可用」语义一致。
 */
public class ModelNotConfiguredException extends RuntimeException {

    public ModelNotConfiguredException() {
        super("模型未配置");
    }
}
