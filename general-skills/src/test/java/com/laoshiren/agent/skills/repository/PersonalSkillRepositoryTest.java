package com.laoshiren.agent.skills.repository;

import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.mapper.PersonalSkillMapper;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.service.impl.PersonalSkillServiceImpl;
import io.agentscope.core.skill.SkillBox;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.tool.Toolkit;
import jakarta.validation.Validator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 使用真实仓库和业务服务，Mapper 的范围/启用条件另由 SQL 测试和 PostgreSQL 测试验证。 */
class PersonalSkillRepositoryTest {
    private static final SkillScope USER_A = new SkillScope(10L, 100L);
    private static final SkillScope USER_B = new SkillScope(10L, 200L);
    private static final SkillScope OTHER_TENANT = new SkillScope(20L, 100L);
    private PersonalSkillMapper mapper;
    private PersonalSkillServiceImpl service;
    private PersonalSkillRepositoryFactory repositories;
    private AgentSkillRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(PersonalSkillMapper.class);
        service = new PersonalSkillServiceImpl(mapper, mock(Validator.class));
        repositories = new PersonalSkillRepositoryFactory(service);
        repository = repositories.forScope(USER_A);
    }

    @Test
    void convertsDatabaseContentAndMetadataIntoUsableFrameworkSkill() {
        var row = skill("review", "# 审查\n\n    保留缩进\n");
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("tags", List.of("java", "spring"));
        extra.put("metadata", Map.of("version", 2));
        extra.put("optional", null);
        row.setExtraFrontmatter(extra);
        when(mapper.findEnabledByNameScoped(USER_A, "review")).thenReturn(row);

        var loaded = repository.getSkill("review");

        assertThat(loaded.getName()).isEqualTo(row.getName());
        assertThat(loaded.getDescription()).isEqualTo(row.getDescription());
        assertThat(loaded.getSkillContent()).isEqualTo(row.getInstructionsMd());
        assertThat(loaded.getMetadata()).containsAllEntriesOf(extra)
                .containsEntry("name", "review").containsEntry("description", "检查代码");
        assertThat(extra).doesNotContainKeys("name", "description");
        assertThat(loaded.getResources()).isEmpty();
        assertThat(loaded.getOriginDir()).isEmpty();
        assertThat(loaded.getSource()).isEqualTo(repository.getSource());
        assertThat(loaded.getSkillId()).isEqualTo("review_personal-db-t10-u100");

        var skillBox = new SkillBox(new Toolkit());
        skillBox.registration().skill(loaded).apply();
        assertThat(skillBox.getSkill(loaded.getSkillId()).getSkillContent()).isEqualTo(row.getInstructionsMd());
        assertThat(skillBox.getSkillPrompt()).contains("review", "检查代码", loaded.getSkillId());
    }

    @Test
    void authoritativeColumnsOverrideConflictingMetadata() {
        var row = skill("review", "正文");
        row.setExtraFrontmatter(Map.of("name", "forged", "description", "伪造描述"));
        when(mapper.findEnabledByNameScoped(USER_A, "review")).thenReturn(row);

        assertThat(repository.getSkill("review").getMetadata())
                .containsEntry("name", "review").containsEntry("description", "检查代码");
    }

    @Test
    void listsAllEnabledNamesAndSkillsWithoutManagementPagination() {
        var rows = IntStream.range(0, 105)
                .mapToObj(i -> skill("skill-" + String.format("%03d", i), "正文 " + i)).toList();
        when(mapper.listEnabledScoped(USER_A)).thenReturn(rows);

        assertThat(repository.getAllSkillNames()).hasSize(105)
                .containsExactlyElementsOf(rows.stream().map(PersonalSkill::getName).toList());
        assertThat(repository.getAllSkills()).hasSize(105)
                .extracting(s -> s.getSkillContent()).contains("正文 0", "正文 104");
    }

    @Test
    void eachRepositoryKeepsItsScopeAndSameNamesHaveDistinctFrameworkIds() {
        var a = skill("review", "A 的正文");
        var b = skill("review", "B 的正文");
        var other = skill("review", "其他租户的正文");
        when(mapper.findEnabledByNameScoped(USER_A, "review")).thenReturn(a);
        when(mapper.findEnabledByNameScoped(USER_B, "review")).thenReturn(b);
        when(mapper.findEnabledByNameScoped(OTHER_TENANT, "review")).thenReturn(other);
        var repositoryB = repositories.forScope(USER_B);
        var repositoryOther = repositories.forScope(OTHER_TENANT);

        var loadedB = repositoryB.getSkill("review");
        var loadedA = repository.getSkill("review");
        var loadedOther = repositoryOther.getSkill("review");

        assertThat(loadedA.getSkillContent()).isEqualTo("A 的正文");
        assertThat(loadedB.getSkillContent()).isEqualTo("B 的正文");
        assertThat(loadedOther.getSkillContent()).isEqualTo("其他租户的正文");
        assertThat(List.of(loadedA.getSkillId(), loadedB.getSkillId(), loadedOther.getSkillId()))
                .doesNotHaveDuplicates();
        assertThat(repositories.forScope(USER_A).getSource()).isEqualTo(repository.getSource());
    }

    @Test
    void reloadsEditedContentAndDoesNotCacheDisabledOrDeletedSkills() {
        when(mapper.findEnabledByNameScoped(USER_A, "review"))
                .thenReturn(skill("review", "旧正文"), skill("review", "新正文"), null);
        when(mapper.listEnabledScoped(USER_A))
                .thenReturn(List.of(skill("review", "旧正文")), List.of());

        assertThat(repository.getAllSkillNames()).containsExactly("review");
        assertThat(repository.getSkill("review").getSkillContent()).isEqualTo("旧正文");
        assertThat(repository.getSkill("review").getSkillContent()).isEqualTo("新正文");
        assertThat(repository.getSkill("review")).isNull();
        assertThat(repository.skillExists("review")).isFalse();
        assertThat(repository.getAllSkillNames()).isEmpty();
        assertThat(repository.getAllSkills()).isEmpty();
    }

    @Test
    void existenceUsesSameEnabledAndOwnerLookupAsLoading() {
        when(mapper.findEnabledByNameScoped(USER_A, "review")).thenReturn(skill("review", "正文"));
        assertThat(repository.skillExists("review")).isTrue();
        verify(mapper).findEnabledByNameScoped(USER_A, "review");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t"})
    void rejectsMissingNameBeforeQuery(String name) {
        assertThatThrownBy(() -> repository.getSkill(name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.skillExists(name)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void requiresExplicitIdentity() {
        assertThatThrownBy(() -> repositories.forScope(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listEnabled(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findEnabledByName(null, "review")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void staysReadOnlyEvenWithForceOrWriteableFlag() {
        assertThat(repository.isWriteable()).isFalse();
        assertThat(repository.getRepositoryInfo().isWritable()).isFalse();
        assertThat(repository.getRepositoryInfo().getType()).isEqualTo("postgresql");
        assertThat(repository.getRepositoryInfo().getLocation()).isEqualTo("personal_skill/tenant/10/user/100");
        repository.setWriteable(false);
        assertThatThrownBy(() -> repository.setWriteable(true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.save(List.of(), false)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.save(List.of(), true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.delete("review")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(repository.isWriteable()).isFalse();
        verifyNoInteractions(mapper);
    }

    private static PersonalSkill skill(String name, String body) {
        var row = new PersonalSkill();
        row.setId(123L);
        row.setTenantId(USER_A.tenantId());
        row.setOwnerUserId(USER_A.userId());
        row.setName(name);
        row.setDescription("检查代码");
        row.setInstructionsMd(body);
        row.setStatus(PersonalSkillStatus.ENABLED);
        row.setRevision(2L);
        return row;
    }
}
