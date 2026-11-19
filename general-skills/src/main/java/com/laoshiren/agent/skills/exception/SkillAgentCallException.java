package com.laoshiren.agent.skills.exception;

import com.laoshiren.agent.skills.result.SkillAgentCallResult;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 外部响应使用固定错误信息，避免泄漏模型端点、凭证或供应商响应。 */
@Getter
public class SkillAgentCallException extends RuntimeException {
    private final HttpStatus status;
    private final SkillAgentCallResult call;

    public SkillAgentCallException(HttpStatus status, String message, SkillAgentCallResult call, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.call = call;
    }
}
