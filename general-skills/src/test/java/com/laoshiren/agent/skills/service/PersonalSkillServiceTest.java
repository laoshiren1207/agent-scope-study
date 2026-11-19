package com.laoshiren.agent.skills.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.param.CreateSkillDraft;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.service.impl.PersonalSkillServiceImpl;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PersonalSkillServiceTest {
    private static final SkillScope OWNER = new SkillScope(10L, 100L);
    private static final CreateSkillDraft DRAFT = new CreateSkillDraft(
            "spring-code-review", "检查 Spring 代码", "# 审查\n\n检查事务边界。\n", null);
    private static ValidatorFactory validators;
    private static MybatisConfiguration configuration;

    private PersonalSkillMapper mapper;
    private IPersonalSkillService service;

    @BeforeAll
    static void configure() {
        validators = Validation.buildDefaultValidatorFactory();
        configuration = new MybatisConfiguration();
        configuration.addMapper(PersonalSkillMapper.class);
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
    void createsDraftForCurrentOwnerAndReturnsDatabaseGeneratedFields() {
        PersonalSkill persisted = new PersonalSkill();
        when(mapper.insert(any(PersonalSkill.class))).thenAnswer(invocation -> {
            PersonalSkill row = invocation.getArgument(0);
            assertThat(row.getId()).isNull();
            assertThat(row.getTenantId()).isEqualTo(10L);
            assertThat(row.getOwnerUserId()).isEqualTo(100L);
            assertThat(row.getName()).isEqualTo(DRAFT.name());
            assertThat(row.getDescription()).isEqualTo(DRAFT.description());
            assertThat(row.getInstructionsMd()).isEqualTo(DRAFT.instructionsMd());
            assertThat(row.getExtraFrontmatter()).isEmpty();
            assertThat(row.getStatus()).isEqualTo(PersonalSkillStatus.DRAFT);
            assertThat(row.getRevision()).isEqualTo(1L);
            assertThat(row.getCreatedAt()).isNull();
            assertThat(row.getUpdatedAt()).isNull();
            row.setId(123L);
            org.springframework.beans.BeanUtils.copyProperties(row, persisted);
            persisted.setCreatedAt(OffsetDateTime.parse("2026-11-18T08:00:00Z"));
            persisted.setUpdatedAt(persisted.getCreatedAt());
            return 1;
        });
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            assertScopedSql(invocation.getArgument(0), 123L, 10L, 100L);
            return persisted;
        });

        PersonalSkill result = service.createDraft(OWNER, DRAFT);

        assertThat(result).isSameAs(persisted);
        assertThat(result.getId()).isEqualTo(123L);
        assertThat(result.getCreatedAt()).isEqualTo("2026-11-18T08:00:00Z");
        assertThat(result.getUpdatedAt()).isEqualTo(result.getCreatedAt());
    }

    @Test
    void preservesExtendedFrontmatterIncludingJsonNullValues() {
        Map<String, Object> frontmatter = new HashMap<>();
        frontmatter.put("tags", List.of("java", "spring"));
        frontmatter.put("metadata", Map.of("version", 1));
        frontmatter.put("optional", null);
        when(mapper.insert(any(PersonalSkill.class))).thenAnswer(invocation -> {
            PersonalSkill row = invocation.getArgument(0);
            assertThat(row.getExtraFrontmatter()).isEqualTo(frontmatter).isNotSameAs(frontmatter);
            row.setId(123L);
            return 1;
        });
        when(mapper.selectOne(any())).thenReturn(new PersonalSkill());

        service.createDraft(OWNER, new CreateSkillDraft(
                DRAFT.name(), DRAFT.description(), DRAFT.instructionsMd(), frontmatter));
    }

    @ParameterizedTest
    @CsvSource({"10, 100", "10, 200", "20, 100", "20, 200"})
    void detailSqlAlwaysBindsIdTenantAndCurrentUser(long tenantId, long userId) {
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            assertScopedSql(invocation.getArgument(0), 123L, tenantId, userId);
            return null;
        });

        assertThat(service.getDetail(new SkillScope(tenantId, userId), 123L)).isEmpty();
    }

    @Test
    void returnsVisibleSkillWithoutFilteringOutDrafts() {
        PersonalSkill persisted = new PersonalSkill();
        persisted.setStatus(PersonalSkillStatus.DRAFT);
        when(mapper.selectOne(any())).thenReturn(persisted);

        assertThat(service.getDetail(OWNER, 123L)).containsSame(persisted);
    }

    @ParameterizedTest
    @MethodSource("invalidDrafts")
    void rejectsInvalidContentBeforeAccessingDatabase(CreateSkillDraft draft, String field) {
        assertThatThrownBy(() -> service.createDraft(OWNER, draft))
                .isInstanceOfSatisfying(ConstraintViolationException.class,
                        exception -> assertThat(exception.getConstraintViolations())
                                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                                        .isEqualTo(field)));
        verifyNoInteractions(mapper);
    }

    static Stream<Arguments> invalidDrafts() {
        return Stream.of(
                Arguments.of(new CreateSkillDraft(null, "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft(" ", "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("UpperCase", "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("two--hyphens", "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("-leading", "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("trailing-", "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("a".repeat(65), "描述", "正文", null), "name"),
                Arguments.of(new CreateSkillDraft("valid", null, "正文", null), "description"),
                Arguments.of(new CreateSkillDraft("valid", " \t\n", "正文", null), "description"),
                Arguments.of(new CreateSkillDraft("valid", "a".repeat(1025), "正文", null), "description"),
                Arguments.of(new CreateSkillDraft("valid", "描述", null, null), "instructionsMd"),
                Arguments.of(new CreateSkillDraft("valid", "描述", " \t\n", null), "instructionsMd"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "description"})
    void rejectsReservedFrontmatterKeys(String key) {
        var draft = new CreateSkillDraft(DRAFT.name(), DRAFT.description(), DRAFT.instructionsMd(),
                Map.of(key, "重复字段"));

        assertThatThrownBy(() -> service.createDraft(OWNER, draft))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("扩展头部");
        verifyNoInteractions(mapper);
    }

    @ParameterizedTest
    @CsvSource({"0, 100", "-1, 100", "10, 0", "10, -1"})
    void rejectsInvalidScope(long tenantId, long userId) {
        assertThatThrownBy(() -> new SkillScope(tenantId, userId))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsMissingScopeOrDraftAndInvalidSkillId() {
        assertThatThrownBy(() -> service.createDraft(null, DRAFT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createDraft(OWNER, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getDetail(null, 123L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getDetail(OWNER, 0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getDetail(OWNER, -1L)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void propagatesDuplicateNameWithoutOverwritingExistingSkill() {
        when(mapper.insert(any(PersonalSkill.class))).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.createDraft(OWNER, DRAFT)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void failsWhenInsertDoesNotWriteRow() {
        when(mapper.insert(any(PersonalSkill.class))).thenReturn(0);

        assertThatThrownBy(() -> service.createDraft(OWNER, DRAFT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("创建技能草稿失败");
    }

    @Test
    void failsWhenGeneratedIdIsMissing() {
        when(mapper.insert(any(PersonalSkill.class))).thenReturn(1);

        assertThatThrownBy(() -> service.createDraft(OWNER, DRAFT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("创建技能草稿失败");
    }

    @Test
    void failsWhenInsertedDraftCannotBeReadBack() {
        when(mapper.insert(any(PersonalSkill.class))).thenAnswer(invocation -> {
            PersonalSkill row = invocation.getArgument(0);
            row.setId(123L);
            return 1;
        });

        assertThatThrownBy(() -> service.createDraft(OWNER, DRAFT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("无法读取");
    }

    private static void assertScopedSql(Wrapper<PersonalSkill> query, long id, long tenantId, long userId) {
        Map<String, Object> parameters = Map.of("ew", query);
        BoundSql sql = configuration.getMappedStatement(PersonalSkillMapper.class.getName() + ".selectList")
                .getBoundSql(parameters);
        assertThat(sql.getSql().replaceAll("\\s+", " ").trim())
                .endsWith("WHERE (id = ? AND tenant_id = ? AND owner_user_id = ?)");
        var parameterObject = configuration.newMetaObject(parameters);
        assertThat(sql.getParameterMappings().stream()
                .map(mapping -> parameterObject.getValue(mapping.getProperty())).toList())
                .containsExactly(id, tenantId, userId);
    }
}
