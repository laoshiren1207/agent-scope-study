package com.laoshiren.agent.skills.agent;

import com.laoshiren.agent.skills.controller.PersonalSkillExceptionHandler;
import com.laoshiren.agent.skills.controller.SkillAgentController;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.answer;
import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.model;
import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.skill;
import static com.laoshiren.agent.skills.agent.SkillAgentServiceTest.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillAgentControllerTest {
    private static final String URL = "/api/skill-agent/calls";
    private static ValidatorFactory validators;
    private IPersonalSkillService skills;
    private SkillAgentModelProvider models;
    private SkillAgentService service;
    private MockMvc http;

    @BeforeAll
    static void configure() { validators = Validation.buildDefaultValidatorFactory(); }

    @AfterAll
    static void closeValidators() { validators.close(); }

    @BeforeEach
    void setUp() {
        skills = mock(IPersonalSkillService.class);
        models = mock(SkillAgentModelProvider.class);
        var properties = new SkillAgentProperties();
        properties.setTimeoutSeconds(5);
        service = new SkillAgentService(new PersonalSkillRepositoryFactory(skills), models, properties,
                validators.getValidator());
        http = MockMvcBuilders.standaloneSetup(new SkillAgentController(service))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
    }

    @Test
    void returnsAnswerAndActualSkillTraceUsingHeaderIdentity() throws Exception {
        var scope = new SkillScope(10L, 100L);
        when(skills.listEnabled(scope)).thenReturn(List.of(skill("HTTP_SCOPE_CHECK：检查数据归属")));
        var calls = new AtomicInteger();
        when(models.getModel()).thenReturn(model(messages -> calls.getAndIncrement() == 0
                ? tool("review_personal-db-t10-u100", "SKILL.md") : answer("HTTP_SCOPE_CHECK：检查租户和用户")));

        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .param("tenantId", "20").param("ownerUserId", "200")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"请审查代码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.answer", containsString("HTTP_SCOPE_CHECK")))
                .andExpect(jsonPath("$.modelCalls").value(2))
                .andExpect(jsonPath("$.discoveredSkills[0].name").value("review"))
                .andExpect(jsonPath("$.skillLoads[0].skillId").value("review_personal-db-t10-u100"))
                .andExpect(jsonPath("$.skillLoads[0].path").value("SKILL.md"))
                .andExpect(jsonPath("$.skillLoads[0].state").value("SUCCESS"))
                .andExpect(jsonPath("$.skillLoads[0].instructionsLoaded").value(true))
                .andExpect(jsonPath("$.skillLoads[0].output", containsString("HTTP_SCOPE_CHECK：检查数据归属")));
        verify(skills).listEnabled(scope);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Tenant-Id", "X-User-Id"})
    void requiresBothIdentityHeaders(String onlyHeader) throws Exception {
        http.perform(post(URL).header(onlyHeader, "10").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"审查\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(skills, models);
    }

    @ParameterizedTest
    @CsvSource({"0, 100", "10, -1", "abc, 100", "10, 9223372036854775808"})
    void rejectsInvalidIdentity(String tenantId, String userId) throws Exception {
        http.perform(post(URL).header("X-Tenant-Id", tenantId).header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"审查\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(skills, models);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"message\":\" \"}", "null", "{"})
    void rejectsMissingOrMalformedMessage(String json) throws Exception {
        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").isNotEmpty());
        verifyNoInteractions(skills, models);
    }

    @Test
    void configurationFailureReturns503WithCallIdAndSafeMessage() throws Exception {
        when(models.getModel()).thenThrow(new IllegalStateException("private-api-key"));
        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"审查\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail", containsString("SKILLS_MODEL_BASE_URL")))
                .andExpect(jsonPath("$.detail", not(containsString("private-api-key"))))
                .andExpect(jsonPath("$.call.callId").isNotEmpty())
                .andExpect(jsonPath("$.call.status").value("MODEL_UNAVAILABLE"));
        verifyNoInteractions(skills);
    }

    @Test
    void providerFailureReturns502AndDoesNotExposeProviderResponse() throws Exception {
        when(skills.listEnabled(new SkillScope(10L, 100L))).thenReturn(List.of());
        when(models.getModel()).thenReturn(model(messages -> { throw new IllegalStateException("private-provider-body"); }));
        http.perform(post(URL).header("X-Tenant-Id", "10").header("X-User-Id", "100")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"审查\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.detail", not(containsString("private-provider-body"))))
                .andExpect(jsonPath("$.call.status").value("FAILED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod", "test"})
    void registersControllerOnlyForDevelopment(String profile) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles(profile);
            context.registerBean(SkillAgentService.class, () -> service);
            context.register(SkillAgentController.class);
            context.refresh();
            assertThat(context.getBeansOfType(SkillAgentController.class)).hasSize(profile.equals("dev") ? 1 : 0);
        }
    }
}
