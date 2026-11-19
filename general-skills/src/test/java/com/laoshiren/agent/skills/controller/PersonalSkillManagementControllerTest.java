package com.laoshiren.agent.skills.controller;

import com.laoshiren.agent.skills.repository.PersonalSkillRepositoryFactory;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.exception.SkillRevisionConflictException;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.result.PersonalSkillPage;
import com.laoshiren.agent.skills.service.IPersonalSkillService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonalSkillManagementControllerTest {
    enum Operation { EDIT, ENABLE, DISABLE, DELETE }

    private static final String URL = "/api/personal-skills";
    private static final SkillScope OWNER = new SkillScope(10L, 100L);
    private IPersonalSkillService service;
    private MockMvc http;

    @BeforeEach
    void setUp() {
        service = mock(IPersonalSkillService.class);
        http = MockMvcBuilders.standaloneSetup(
                        new PersonalSkillController(service, new PersonalSkillRepositoryFactory(service)))
                .setControllerAdvice(new PersonalSkillExceptionHandler()).build();
    }

    @Test
    void listsWithDefaultPaginationAndHeaderScope() throws Exception {
        var query = new SkillListQuery(1, 20, null);
        when(service.list(OWNER, query)).thenReturn(new PersonalSkillPage(1, 20, 0, List.of()));

        http.perform(asOwner(get(URL).param("tenantId", "99").param("ownerUserId", "999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1)).andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());

        verify(service).list(OWNER, query);
    }

    @Test
    void listsWithStatusFilterAndRequestedPagination() throws Exception {
        var query = new SkillListQuery(2, 5, PersonalSkillStatus.ENABLED);
        when(service.list(OWNER, query)).thenReturn(new PersonalSkillPage(2, 5, 6,
                List.of(row(PersonalSkillStatus.ENABLED))));

        http.perform(asOwner(get(URL).param("page", "2").param("size", "5").param("status", "ENABLED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.items[0].id").value(123))
                .andExpect(jsonPath("$.items[0].status").value("ENABLED"));

        verify(service).list(OWNER, query);
    }

    @ParameterizedTest
    @CsvSource({"page, 0", "page, -1", "size, 0", "size, 101", "page, abc", "status, UNKNOWN"})
    void rejectsInvalidListQueries(String parameter, String value) throws Exception {
        http.perform(asOwner(get(URL).param(parameter, value))).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void updatesContentUsingBodyRevisionAndHeaderScope() throws Exception {
        var update = new UpdateSkill(7L, "new-name", "新描述", "新正文", null);
        when(service.update(OWNER, 123L, update)).thenReturn(row(PersonalSkillStatus.ENABLED));

        http.perform(asOwner(mutation(Operation.EDIT, "7")).param("revision", "99"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(8))
                .andExpect(jsonPath("$.status").value("ENABLED"));

        verify(service).update(OWNER, 123L, update);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ENABLE", "DISABLE"})
    void updatesStatusAndReturnsNewRevision(Operation operation) throws Exception {
        var expected = operation == Operation.ENABLE ? PersonalSkillStatus.ENABLED : PersonalSkillStatus.DISABLED;
        when(service.enable(OWNER, 123L, 7L)).thenReturn(row(PersonalSkillStatus.ENABLED));
        when(service.disable(OWNER, 123L, 7L)).thenReturn(row(PersonalSkillStatus.DISABLED));

        http.perform(asOwner(mutation(operation, "7")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value(expected.name()))
                .andExpect(jsonPath("$.revision").value(8));

        if (operation == Operation.ENABLE) verify(service).enable(OWNER, 123L, 7L);
        else verify(service).disable(OWNER, 123L, 7L);
    }

    @Test
    void deletesWithQueryRevisionAndReturnsNoContent() throws Exception {
        http.perform(asOwner(mutation(Operation.DELETE, "7")))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).delete(OWNER, 123L, 7L);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void rejectsMissingNonPositiveOrMalformedRevision(Operation operation) throws Exception {
        for (String revision : new String[]{null, "0", "-1", "\"abc\""}) {
            http.perform(asOwner(mutation(operation, revision))).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    void rejectsIncompleteEditAndMissingListIdentity() throws Exception {
        http.perform(asOwner(put(URL + "/123").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":7,\"name\":\"new-name\"}")))
                .andExpect(status().isBadRequest());
        http.perform(get(URL).header("X-Tenant-Id", "10")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void requiresIdentityForEveryMutation(Operation operation) throws Exception {
        http.perform(mutation(operation, "7").header("X-Tenant-Id", "10"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void returnsConflictForOldVersionsAndNotFoundForInvisibleRecords(Operation operation) throws Exception {
        var conflict = new SkillRevisionConflictException();
        var missing = new SkillNotFoundException();
        switch (operation) {
            case EDIT -> when(service.update(any(), anyLong(), any())).thenThrow(conflict, missing);
            case ENABLE -> when(service.enable(any(), anyLong(), anyLong())).thenThrow(conflict, missing);
            case DISABLE -> when(service.disable(any(), anyLong(), anyLong())).thenThrow(conflict, missing);
            case DELETE -> doThrow(conflict, missing).when(service).delete(any(), anyLong(), anyLong());
        }

        http.perform(asOwner(mutation(operation, "7")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.status").value(409));
        http.perform(mutation(operation, "7").header("X-Tenant-Id", "20").header("X-User-Id", "200"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.detail").value("技能不存在"));
    }

    private static MockHttpServletRequestBuilder mutation(Operation operation, String revision) {
        if (operation == Operation.DELETE) {
            var request = delete(URL + "/123");
            return revision == null ? request : request.param("revision", revision);
        }
        String revisionField = revision == null ? "" : "\"revision\":" + revision;
        if (operation == Operation.EDIT) {
            String body = "{" + (revisionField.isEmpty() ? "" : revisionField + ",")
                    + "\"name\":\"new-name\",\"description\":\"新描述\",\"instructionsMd\":\"新正文\"}";
            return put(URL + "/123").contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String action = operation == Operation.ENABLE ? "enable" : "disable";
        return post(URL + "/123/" + action).contentType(MediaType.APPLICATION_JSON)
                .content("{" + revisionField + "}");
    }

    private static MockHttpServletRequestBuilder asOwner(MockHttpServletRequestBuilder request) {
        return request.header("X-Tenant-Id", "10").header("X-User-Id", "100");
    }

    private static PersonalSkill row(PersonalSkillStatus status) {
        var row = new PersonalSkill();
        row.setId(123L);
        row.setTenantId(10L);
        row.setOwnerUserId(100L);
        row.setStatus(status);
        row.setRevision(8L);
        return row;
    }
}
