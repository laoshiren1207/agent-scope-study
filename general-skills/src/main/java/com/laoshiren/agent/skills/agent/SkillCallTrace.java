package com.laoshiren.agent.skills.agent;

import com.laoshiren.agent.skills.result.SkillAgentCallResult;
import com.laoshiren.agent.skills.result.SkillAgentCallResult.DiscoveredSkill;
import com.laoshiren.agent.skills.result.SkillAgentCallResult.SkillLoad;
import com.laoshiren.agent.skills.result.SkillAgentCallResult.Step;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.skill.AgentSkill;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * 记录一次 Agent 调用中的技能发现、正文加载和最终回答过程，每次请求使用独立实例。
 *
 * <p>技能出现在仓库目录中只表示可用；是否加载正文需要根据实际工具参数和返回内容判断。
 * 响应式回调可能在不同线程执行，因此状态更新和结果快照统一使用当前实例的锁。
 * 事件只由 {@link #onAgent} 统一记录，调用方不能再次订阅记录同一事件。
 * 日志仅输出关联标识、状态和统计值，不输出技能正文、回答或原始异常信息。
 */
@Slf4j
public class SkillCallTrace implements MiddlewareBase {

    // 框架内置的技能文件加载工具，仅跟踪该工具的调用。
    private static final String LOAD_TOOL = "load_skill_through_path";

    // 整次 Agent 调用的唯一标识
    private final String callId;
    // 展示时间使用墙上时钟；耗时使用单调时钟，避免系统校时影响时长。
    private final Instant startedAt = Instant.now();
    private final long startedNanos = System.nanoTime();

    // 本次提供给框架的技能目录，按 skillId 索引
    private final Map<String, AgentSkill> discovered = new LinkedHashMap<>();

    // 所有技能加载尝试，包括成功、失败和未结束的
    private final List<LoadAttempt> loads = new ArrayList<>();

    // 尚未结束的加载尝试，按 toolCallId 索引
    private final Map<String, LoadAttempt> pending = new LinkedHashMap<>();
    // 按记录顺序排列的过程步骤
    private final List<Step> steps = new ArrayList<>();

    // 收到模型调用开始事件的次数
    private int modelCalls;
    // 最终回答文本
    private String answer;
    // 是否收到超过最大迭代次数的事件
    private boolean exceeded;

    /**
     * 创建本次 Agent 调用的跟踪器。
     *
     * @param callId 本次调用的唯一标识，用于关联返回结果
     */
    public SkillCallTrace(String callId) {
        this.callId = callId;
    }

    /**
     * 记录框架本次发现的技能目录，不将目录读取视为技能正文加载。
     *
     * @param skills 当前调用范围内可供 Agent 使用的技能
     */
    synchronized void discover(List<AgentSkill> skills) {
        // 使用最新发现的目录替换旧目录，步骤历史仍然保留。
        discovered.clear();
        for (AgentSkill skill : skills) {
            discovered.put(skill.getSkillId(), skill);
        }
        step("SKILLS_DISCOVERED", null);
        log.info("技能目录已发现 callId={} skillCount={}", callId, discovered.size());
    }

    /**
     * 统一观察整次 Agent 调用的事件流。调用服务只消费该流，不再重复调用 record。
     *
     * @param agent   当前执行的 Agent
     * @param context 当前调用的运行上下文
     * @param input   本次 Agent 调用的输入
     * @param next    后续中间件或 Agent 执行逻辑
     * @return 保留原始事件、错误和取消语义的事件流
     */
    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {

        // 订阅时才启动观察；下游抛出的同步异常也会转成流中的错误信号。
        return Flux.defer(() -> {
                    log.info("Agent 事件跟踪开始 callId={}", callId);
                    return next.apply(input);
                })
                .doOnNext(this::record)
                // 取消不等于工具失败：未收到工具终态的尝试仍以 UNKNOWN 返回。
                .doOnCancel(() -> log.warn("Agent 事件观察已取消 callId={}", callId))
                .doOnError(error -> log.warn("Agent 事件流异常 callId={} errorType={}", callId, error.getClass().getSimpleName()));
    }

    /**
     * 在工具执行前记录技能加载请求，再将原始输入交给后续执行链。
     *
     * @param agent   当前执行的 Agent
     * @param context 当前调用的运行上下文
     * @param input   本轮待执行的工具调用
     * @param next    后续中间件或工具执行逻辑
     * @return 后续执行链产生的事件流
     */
    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext context, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        // 延迟到订阅时记录，避免仅构建事件流就产生加载请求记录。
        return Flux.defer(() -> {
            synchronized (this) {
                for (ToolUseBlock tool : input.toolCalls()) {
                    // 这里只登记技能文件的加载请求，不执行工具，也不提前认定正文已加载。
                    if (!LOAD_TOOL.equals(tool.getName())) {
                        continue;
                    }
                    Map<String, Object> inputMap = tool.getInput();
                    // 在实际执行前建立关联，确保后续结果事件能找到对应尝试。
                    LoadAttempt attempt = new LoadAttempt(tool.getId(),
                            string(inputMap.get("skillId")),
                            string(inputMap.get("path")));
                    loads.add(attempt);
                    pending.put(tool.getId(), attempt);
                    step("SKILL_LOAD_REQUESTED", tool.getId());
                    log.info("技能加载请求 callId={} toolCallId={} skillId={} path={}",
                            callId, logValue(attempt.toolCallId), logValue(attempt.skillId), logValue(attempt.path));
                }
            }
            // 在锁外继续执行；跟踪器只观察调用，不修改工具输入。
            return next.apply(input);
        });
    }

    /**
     * 消费 Agent 事件，更新模型调用次数、技能加载结果和最终回答。
     * 与这些阶段无关的事件不参与记录。
     *
     * @param event Agent 执行过程中发布的事件
     */
    synchronized void record(AgentEvent event) {
        if (event == null) {
            return;
        }
        // 按框架事件枚举分派，使用 Java 17 支持的 switch 语法。
        switch (event.getType()) {
            case MODEL_CALL_START -> {
                // 以模型调用开始事件计数，同一次 Agent 调用可能包含多轮模型推理。
                modelCalls++;
                step("MODEL_CALL", null);
            }
            case TOOL_RESULT_TEXT_DELTA -> {
                ToolResultTextDeltaEvent delta = (ToolResultTextDeltaEvent) event;
                if (!LOAD_TOOL.equals(delta.getToolCallName())) {
                    return;
                }
                // 按工具调用 ID 拼接输出，避免多个加载请求的结果混在一起。
                LoadAttempt attempt = pending.get(delta.getToolCallId());
                if (attempt != null && delta.getDelta() != null) {
                    attempt.output.append(delta.getDelta());
                }
            }
            case TOOL_RESULT_END -> {
                ToolResultEndEvent end = (ToolResultEndEvent) event;
                if (!LOAD_TOOL.equals(end.getToolCallName())) {
                    return;
                }
                // 先验证终态，再移出待处理集合；无效结束事件不能截断后续输出和真正的终态。
                LoadAttempt attempt = pending.get(end.getToolCallId());
                ToolResultState endState = end.getState();
                if (attempt == null) {
                    // 未登记或已结束的调用不再追加步骤，也不改变已有的结束时间。
                    log.debug("忽略无待处理记录的技能结束事件 callId={} toolCallId={}", callId, logValue(end.getToolCallId()));
                    return;
                }
                if (endState == null || endState == ToolResultState.RUNNING) {
                    log.warn("忽略无有效终态的技能结束事件 callId={} toolCallId={} state={}", callId, logValue(end.getToolCallId()), endState);
                    return;
                }
                // finish 统一写入实际终态和结束时间；loads 继续保留历史记录。
                attempt.finish(endState);
                pending.remove(end.getToolCallId());
                step("SKILL_LOAD_FINISHED", end.getToolCallId());
                log.info("技能加载结束 callId={} toolCallId={} skillId={} state={} outputChars={} elapsedMs={}",
                        callId, logValue(attempt.toolCallId), logValue(attempt.skillId), endState,
                        attempt.output.length(), elapsedMs(attempt.startedNanos, attempt.endedNanos));
                if (endState != ToolResultState.SUCCESS) {
                    log.warn("技能加载未成功 callId={} toolCallId={} state={}",
                            callId, logValue(attempt.toolCallId), endState);
                }
            }
            case EXCEED_MAX_ITERS -> {
                // 单独标记迭代上限，生成结果时据此区分完成状态。
                exceeded = true;
                step("MAX_ITERS", null);
                log.warn("Agent 达到迭代上限 callId={} modelCalls={}", callId, modelCalls);
            }
            case AGENT_RESULT -> {
                AgentResultEvent result = (AgentResultEvent) event;
                // 提取最终结果的文本，不收集模型思考过程或中间文本片段。
                answer = result.getResult().getTextContent();
                step("ANSWER", null);
                log.info("Agent 最终回答已记录 callId={} answerChars={}", callId, answer == null ? 0 : answer.length());
            }
            default -> {
                // 忽略与技能调用跟踪无关的事件。
            }
        }
    }

    /**
     * 判断是否已收到非空白的最终回答，不据此判断调用完成状态或技能使用情况。
     *
     * @return 最终回答存在且包含非空白字符时为 true
     */
    synchronized boolean hasAnswer() {
        return answer != null && !answer.isBlank();
    }

    /**
     * 基于当前记录生成调用结果快照，并核实每次尝试是否实际加载了技能指令。
     * 本方法不结束工具执行；尚未观察到终态的尝试保持 UNKNOWN，结束时间为 null。
     *
     * @param failureStatus 外部指定的失败状态；为 null 时根据迭代上限标记推导状态
     * @return 包含回答、技能目录、加载详情和有序步骤的结果快照
     */
    synchronized SkillAgentCallResult result(String failureStatus) {
        // 所有未结束的加载尝试与整次调用共用快照时刻，避免子项耗时超过整体观察时长。
        long snapshotNanos = System.nanoTime();
        Instant finishedAt = Instant.now();

        // 显式失败状态优先，其次为达到迭代上限，否则标记为完成。
        String status = failureStatus != null ? failureStatus : exceeded ? "MAX_ITERS" : "COMPLETED";
        // 目录仅包含技能元信息，实际返回的正文保留在对应加载记录中。
        List<DiscoveredSkill> catalog = discovered.values().stream()
                .map(s -> new DiscoveredSkill(s.getSkillId(), s.getName(), s.getDescription()))
                .toList();

        List<SkillLoad> records = loads.stream()
                .map(attempt -> {
                    AgentSkill skill = discovered.get(attempt.skillId);
                    String output = attempt.output.toString();
                    boolean terminalObserved = attempt.terminalStateObserved;
                    // 整次调用失败或超时，不能据此推测某个工具的实际执行状态。
                    String responseState = terminalObserved ? attempt.state.name() : "UNKNOWN";
                    String observationEndReason = terminalObserved ? null : status;
                    // 必须成功加载已发现技能的 SKILL.md，且输出包含该技能正文，才算指令已加载。
                    boolean instructionsLoaded = terminalObserved
                            && attempt.state == ToolResultState.SUCCESS
                            && skill != null
                            && "SKILL.md".equals(attempt.path)
                            && output.contains(skill.getSkillContent());

                    return new SkillLoad(
                            attempt.toolCallId,
                            attempt.skillId,
                            skill == null ? null : skill.getName(),
                            attempt.path,
                            responseState,
                            instructionsLoaded,
                            output,
                            terminalObserved,
                            observationEndReason,
                            attempt.timingAt(snapshotNanos));
                }).toList();
        long elapsed = elapsedMs(startedNanos, snapshotNanos);
        log.info("Agent 跟踪快照 callId={} status={} modelCalls={} skillCount={} loadCount={} pendingCount={} elapsedMs={}",
                callId, status, modelCalls, catalog.size(), records.size(), pending.size(), elapsed);
        for (SkillLoad record : records) {
            log.info("技能加载核验 callId={} toolCallId={} skillId={} state={} instructionsLoaded={} observationEndReason={}",
                    callId, logValue(record.toolCallId()), logValue(record.skillId()), record.state(),
                    record.instructionsLoaded(), record.observationEndReason());
        }
        if (!"COMPLETED".equals(status) || !pending.isEmpty()) {
            log.warn("Agent 跟踪未完整完成 callId={} status={} pendingCount={}", callId, status, pending.size());
        }
        // 输出已转为字符串，各列表均生成不可修改的快照，隔离后续事件更新。
        return new SkillAgentCallResult(callId, status, answer, modelCalls, catalog, records, List.copyOf(steps),
                new SkillAgentCallResult.Timing(
                        startedAt,
                        finishedAt,
                        elapsed));
    }

    /**
     * 追加一个过程步骤，序号从 1 开始；调用方必须已持有当前实例的锁。
     *
     * @param type       步骤类型
     * @param toolCallId 关联的工具调用 ID，非工具步骤传入 null
     */
    private void step(String type, String toolCallId) {
        long nowNanos = System.nanoTime();
        Step step = new Step(steps.size() + 1, type, toolCallId, Instant.now(), elapsedMs(startedNanos, nowNanos));
        steps.add(step);
        log.debug("Agent 跟踪步骤 callId={} sequence={} type={} toolCallId={} elapsedMs={}",
                callId, step.sequence(), type, logValue(toolCallId), step.elapsedMs());
    }

    /**
     * 从工具参数中提取字符串，不对其他类型进行隐式转换。
     *
     * @param value 工具参数的原始值
     * @return 字符串值；为 null 或类型不匹配时返回 null
     */
    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * 将模型生成的标识和路径压缩为有长度上限的单行日志值，避免伪造日志行。
     * 原始参数仍保留在调用结果中。
     *
     * @param value 原始标识或路径，可为 null
     * @return 最多 160 个原始字符的单行文本，超长值追加省略号
     */
    private static String logValue(String value) {
        if (value == null) return null;
        String prefix = value.substring(0, Math.min(value.length(), 160));
        String singleLine = prefix.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", "_");
        return value.length() > 160 ? singleLine + "..." : singleLine;
    }

    /**
     * 使用同一单调时钟的两个读数计算毫秒耗时；不足一毫秒的部分向下取整。
     *
     * @param startNanos 开始时刻的 System.nanoTime() 读数
     * @param endNanos   结束或快照时刻的 System.nanoTime() 读数
     * @return 经过的整毫秒数
     */
    private static long elapsedMs(long startNanos, long endNanos) {
        return TimeUnit.NANOSECONDS.toMillis(endNanos - startNanos);
    }

    /**
     * 单次技能加载的内部可变记录，由跟踪器在实例锁保护下累积结果。
     */
    private static final class LoadAttempt {
        final String toolCallId;
        final String skillId;
        final String path;

        // 按事件到达顺序累积工具返回的文本片段。
        final StringBuilder output = new StringBuilder();
        // RUNNING 仅为内部初始值；未收到有效终态时，对外统一返回 UNKNOWN。
        ToolResultState state = ToolResultState.RUNNING;

        // 是否实际收到包含终态的 ToolResultEndEvent
        boolean terminalStateObserved;

        final Instant startedAt = Instant.now();
        final long startedNanos = System.nanoTime();

        Instant endedAt;
        long endedNanos;

        /**
         * 根据实际工具调用参数创建加载尝试。
         *
         * @param toolCallId 工具调用的唯一标识
         * @param skillId    请求加载的技能 ID，参数缺失或类型错误时为 null
         * @param path       请求加载的技能内文件路径，参数缺失或类型错误时为 null
         */
        LoadAttempt(String toolCallId, String skillId, String path) {
            this.toolCallId = toolCallId;
            this.skillId = skillId;
            this.path = path;
        }

        /**
         * 保存工具终态和结束时间；调用方须持有跟踪器锁并已验证 endState 为有效终态。
         *
         * @param endState 工具实际报告的终态
         */
        void finish(ToolResultState endState) {
            state = endState;
            terminalStateObserved = true;
            endedNanos = System.nanoTime();
            endedAt = Instant.now();
        }

        /**
         * 已结束的尝试使用固定结束时间，未结束的尝试统计到本次快照时刻。
         *
         * @param snapshotNanos 整次调用结果统一使用的单调时钟读数
         * @return 加载耗时；未收到有效终态时 endedAt 为 null
         */
        SkillAgentCallResult.Timing timingAt(long snapshotNanos) {
            long until = terminalStateObserved ? endedNanos : snapshotNanos;
            return new SkillAgentCallResult.Timing(
                    startedAt,
                    endedAt,
                    elapsedMs(startedNanos, until));
        }
    }
}
