package com.laoshiren.agent.persistent.service;

/**
 * 模型相关操作失败的业务异常，用于向量化、向量结果校验及问答流程。
 *
 * <p>message 提供可对外展示的失败说明，cause 保留底层异常；由 API 异常处理器映射为 HTTP 502。
 */
public class ModelCallException extends RuntimeException {
    /**
     * 创建包含对外失败说明及原始原因的异常。
     *
     * @param message 可对外展示且不包含凭证等敏感信息的失败说明
     * @param cause 触发本次失败的底层异常
     */
    public ModelCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
