package com.laoshiren.agent.skills.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.exception.SkillNotFoundException;
import com.laoshiren.agent.skills.exception.SkillRevisionConflictException;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.skills.service.impl.PersonalSkillServiceImpl;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonalSkillManagementServiceTest {
    enum Operation { EDIT, ENABLE, DISABLE, DELETE }

    private static final SkillScope OWNER = new SkillScope(10L, 100L);
    private static ValidatorFactory validators;
    private PersonalSkillMapper mapper;
    private IPersonalSkillService service;

    @BeforeAll
    static void configure() {
        validators = Validation.buildDefaultValidatorFactory();
        new MybatisConfiguration().addMapper(PersonalSkillMapper.class);
    }

    @AfterAll
    static void closeValidators() {
        validators.close();
    }

    @BeforeEach
    void setUp() {
        mapper = mock(PersonalSkillMapper.class);
        service = new PersonalSkillServiceImpl(mapper, validators.getValidator());
    }

    @Test
    void paginatesUsingSameScopeAndStatusForCountAndItems() {
        var query = new SkillListQuery(2, 5, PersonalSkillStatus.ENABLED);
        var row = new PersonalSkill();
        when(mapper.countScoped(OWNER, query)).thenReturn(6L);
        when(mapper.listScoped(OWNER, query, 5L)).thenReturn(List.of(row));

        var page = service.list(OWNER, query);

        assertThat(page.page()).isEqualTo(2);
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.total()).isEqualTo(6);
        assertThat(page.items()).containsExactly(row);
        verify(mapper).countScoped(OWNER, query);
        verify(mapper).listScoped(OWNER, query, 5L);
    }

    @Test
    void emptyPageRetainsScopedTotalAndLargeOffsetsDoNotOverflow() {
        var query = new SkillListQuery(Integer.MAX_VALUE, 100, null);
        when(mapper.countScoped(OWNER, query)).thenReturn(3L);
        when(mapper.listScoped(OWNER, query, 214748364600L)).thenReturn(List.of());

        var page = service.list(OWNER, query);

        assertThat(page.items()).isEmpty();
        assertThat(page.total()).isEqualTo(3);
        verify(mapper).listScoped(OWNER, query, 214748364600L);
    }

    @Test
    void updatesContentWithExpectedVersionAndNormalizesMissingFrontmatter() {
        var request = edit(7L);
        var updated = new PersonalSkill();
        updated.setRevision(8L);
        when(mapper.updateContentScoped(OWNER, 123L, request, Map.of())).thenReturn(1);
        when(mapper.selectOne(any())).thenReturn(updated);

        assertThat(service.update(OWNER, 123L, request)).isSameAs(updated);
        verify(mapper).updateContentScoped(OWNER, 123L, request, Map.of());
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ENABLE", "DISABLE"})
    void changesOnlyRequestedStatusUsingExpectedVersion(Operation operation) {
        var status = operation == Operation.ENABLE ? PersonalSkillStatus.ENABLED : PersonalSkillStatus.DISABLED;
        var updated = new PersonalSkill();
        updated.setStatus(status);
        updated.setRevision(8L);
        when(mapper.updateStatusScoped(OWNER, 123L, 7L, status)).thenReturn(1);
        when(mapper.selectOne(any())).thenReturn(updated);

        assertThat(mutate(operation, OWNER, 7L)).isSameAs(updated);
        verify(mapper).updateStatusScoped(OWNER, 123L, 7L, status);
    }

    @Test
    void deletesOnlyWithExpectedVersion() {
        when(mapper.deleteScoped(OWNER, 123L, 7L)).thenReturn(1);

        service.delete(OWNER, 123L, 7L);

        verify(mapper).deleteScoped(OWNER, 123L, 7L);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void reportsConflictIfConditionalMutationFailsForVisibleSkill(Operation operation) {
        when(mapper.selectOne(any())).thenReturn(new PersonalSkill());

        assertThatThrownBy(() -> mutate(operation, OWNER, 7L))
                .isInstanceOf(SkillRevisionConflictException.class);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void reportsNotFoundForMissingOrInvisibleRecordsInsteadOfRevealingVersion(Operation operation) {
        assertThatThrownBy(() -> mutate(operation, new SkillScope(10L, 200L), 7L))
                .isInstanceOf(SkillNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void rejectsInvalidIdentityAndVersionBeforeMapperAccess(Operation operation) {
        assertThatThrownBy(() -> mutate(operation, null, 7L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mutate(operation, OWNER, 0L))
                .isInstanceOfAny(IllegalArgumentException.class, ConstraintViolationException.class);
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource({"0, 20", "-1, 20", "1, 0", "1, 101"})
    void rejectsInvalidPaginationAtServiceBoundary(int page, int size) {
        assertThatThrownBy(() -> service.list(OWNER, new SkillListQuery(page, size, null)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {-1L, 0L})
    void rejectsMissingOrInvalidEditRevision(Long revision) {
        assertThatThrownBy(() -> service.update(OWNER, 123L, edit(revision)))
                .isInstanceOf(ConstraintViolationException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void validatesEditContentAndReservedFrontmatterAtServiceBoundary() {
        assertThatThrownBy(() -> service.update(OWNER, 123L,
                new UpdateSkill(7L, "Bad--Name", " ", "", null)))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> service.update(OWNER, 123L,
                new UpdateSkill(7L, "good-name", "描述", "正文", Map.of("name", "duplicate"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(OWNER, 0L, edit(7L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(OWNER, 123L, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(OWNER, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    private Object mutate(Operation operation, SkillScope scope, long revision) {
        return switch (operation) {
            case EDIT -> service.update(scope, 123L, edit(revision));
            case ENABLE -> service.enable(scope, 123L, revision);
            case DISABLE -> service.disable(scope, 123L, revision);
            case DELETE -> {
                service.delete(scope, 123L, revision);
                yield null;
            }
        };
    }

    private static UpdateSkill edit(Long revision) {
        return new UpdateSkill(revision, "new-name", "新描述", "新正文", null);
    }
}
