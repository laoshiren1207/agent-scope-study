package com.laoshiren.agent.skills.agent;

import com.laoshiren.agent.skills.exception.SkillAgentCallException;
import com.laoshiren.agent.skills.param.SkillAgentCall;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.result.SkillAgentCallResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.FileSystemUtils;
import reactor.core.Exceptions;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillAgentService {
    private final PersonalSkillRepositoryFactory repositories;
    private final SkillAgentModelProvider models;
    private final SkillAgentProperties properties;
    private final Validator validator;

    /** MVC 请求线程等待一次调用完成；每次使用独立 Agent、身份、工作目录和记录。 */
    public SkillAgentCallResult call(SkillScope scope, SkillAgentCall request) {
        Assert.notNull(scope, "当前租户和用户范围不能为空");
        Assert.notNull(request, "调用参数不能为空");
        // 1. 校验参数
        Set<ConstraintViolation<SkillAgentCall>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        // 2. 每次请求独立实例。基于实际工具参数和结果记录加载，仓库预读不算正文加载。
        String callId = UUID.randomUUID().toString();
        SkillCallTrace trace = new SkillCallTrace(callId);
        // 3. 模型
        Model model;
        try {
            model = models.getModel();
        } catch (RuntimeException exception) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "模型尚未就绪，请检查 SKILLS_MODEL_BASE_URL、SKILLS_MODEL_NAME、SKILLS_MODEL_API_KEY 配置", "MODEL_UNAVAILABLE", trace, exception);
        }
        // 4. 临时工作path
        Path workDir = null;
        try (AgentSkillRepository repository = repositories.forScope(scope)) {
            // 它是为“单次调用的技能快照和调用记录”增加的一层
            SkillCallRepository snapshot;
            try {
                snapshot = new SkillCallRepository(repository, trace);
            } catch (RuntimeException exception) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, "读取技能仓库失败，请稍后重试 ","REPOSITORY_UNAVAILABLE", trace, exception);
            }
            workDir = Files.createTempDirectory("personal-skill-call-");
            Duration timeout = Duration.ofSeconds(properties.getTimeoutSeconds());
            RuntimeContext context = RuntimeContext.builder()
                    .sessionId(callId)
                    .userId("tenant-" + scope.tenantId() + "-user-" + scope.userId())
                    .build();
            try (ReActAgent agent = ReActAgent.builder()
                    .name("personal-skill-assistant")
                    .sysPrompt("""
                            你是一个中文助手。先查看可用技能目录；若有适用技能，必须调用
                            load_skill_through_path，使用目录中的完整 skillId 和路径 SKILL.md 加载正文，
                            再依据已加载的指令回答。没有适用技能时正常回答，不要声称使用了技能。
                            """)
                    .model(model)
                    .skillRepository(snapshot)
                    .skillWorkDir(workDir)
                    .middleware(trace)
                    .maxIters(properties.getMaxIters())
                    .modelExecutionConfig(ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build())
                    .build()) {
                // trace.onAgent 已统一记录事件，此处只等待完成，避免重复计数和拼接工具输出。
                agent.streamEvents(request.message(), context)
                        .then()
                        .timeout(timeout)
                        .block();
                if (!trace.hasAnswer()) {
                    throw failure(HttpStatus.BAD_GATEWAY, "Agent 未返回有效回答", "FAILED", trace, null);
                }
                return trace.result(null);
            }
        } catch (SkillAgentCallException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (Exceptions.unwrap(exception) instanceof TimeoutException) {
                throw failure(HttpStatus.GATEWAY_TIMEOUT, "Agent 调用超时", "TIMED_OUT", trace, exception);
            }
            throw failure(HttpStatus.BAD_GATEWAY, "Agent 调用失败，请检查模型服务或稍后重试", "FAILED", trace, exception);
        } catch (IOException exception) {
            throw failure(HttpStatus.INTERNAL_SERVER_ERROR, "无法创建 Agent 工作目录", "FAILED", trace, exception);
        } finally {
            if (workDir != null) {
                try {
                    FileSystemUtils.deleteRecursively(workDir);
                } catch (IOException exception) {
                    log.warn("Agent 调用 {} 的临时目录清理失败", callId);
                }
            }
        }
    }

    private static SkillAgentCallException failure(HttpStatus status, String message, String callStatus,
                                                   SkillCallTrace trace, Throwable cause) {
        return new SkillAgentCallException(status, message, trace.result(callStatus), cause);
    }
}
