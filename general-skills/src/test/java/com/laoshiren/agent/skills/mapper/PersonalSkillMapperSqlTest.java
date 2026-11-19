package com.laoshiren.agent.skills.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.UpdateSkill;
import com.laoshiren.agent.typehandler.JsonbTypeHandler;
import java.util.Map;
import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalSkillMapperSqlTest {
    private static MybatisConfiguration configuration;
    private static final SkillScope SCOPE = new SkillScope(10L, 100L);

    @BeforeAll
    static void configure() {
        configuration = new MybatisConfiguration();
        configuration.addMapper(PersonalSkillMapper.class);
    }

    @ParameterizedTest
    @EnumSource(PersonalSkillStatus.class)
    @NullSource
    void countAndPageAlwaysHaveIdenticalOwnerAndStatusFilters(PersonalSkillStatus status) {
        var query = new SkillListQuery(2, 20, status);
        var params = Map.of("scope", SCOPE, "query", query, "offset", query.offset());
        BoundSql count = sql("countScoped", params);
        BoundSql page = sql("listScoped", params);
        String filter = "WHERE tenant_id = ? AND owner_user_id = ?";
        if (status != null) filter += " AND status = ?";

        assertThat(normalized(count)).isEqualTo("SELECT count(*) FROM public.personal_skill " + filter);
        assertThat(normalized(page)).isEqualTo("SELECT * FROM public.personal_skill " + filter
                + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?");
        if (status == null) {
            assertBindings(count, params, 10L, 100L);
            assertBindings(page, params, 10L, 100L, 20, 20L);
        } else {
            assertBindings(count, params, 10L, 100L, status.getValue());
            assertBindings(page, params, 10L, 100L, status.getValue(), 20, 20L);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"listScoped", "listEnabledScoped", "findEnabledByNameScoped"})
    void readsUseEntityJsonbResultMapping(String method) {
        var resultMap = configuration.getMappedStatement(PersonalSkillMapper.class.getName() + "." + method)
                .getResultMaps().get(0);
        assertThat(resultMap.getResultMappings()).anySatisfy(mapping -> {
            assertThat(mapping.getProperty()).isEqualTo("extraFrontmatter");
            assertThat(mapping.getTypeHandler()).isInstanceOf(JsonbTypeHandler.class);
        });
    }

    @ParameterizedTest
    @CsvSource({"10, 100", "10, 200", "20, 100", "20, 200"})
    void repositoryQueriesAlwaysFilterOwnerAndEnabledStatus(long tenantId, long userId) {
        var params = Map.of("scope", new SkillScope(tenantId, userId), "name", "review' OR 1=1 --");
        BoundSql list = sql("listEnabledScoped", params);
        BoundSql load = sql("findEnabledByNameScoped", params);
        String filter = "WHERE tenant_id = ? AND owner_user_id = ? AND status = 'enabled'";

        assertThat(normalized(list)).isEqualTo("SELECT * FROM public.personal_skill " + filter + " ORDER BY name ASC");
        assertThat(normalized(load)).isEqualTo("SELECT * FROM public.personal_skill " + filter + " AND name = ?");
        assertBindings(list, params, tenantId, userId);
        assertBindings(load, params, tenantId, userId, "review' OR 1=1 --");
    }

    @ParameterizedTest
    @CsvSource({"10, 100", "10, 200", "20, 100"})
    void editSqlAtomicallyChecksScopeAndRevisionAndPreservesOwnership(long tenantId, long userId) {
        var content = new UpdateSkill(7L, "new-name", "新描述", "新正文", Map.of("tags", "java"));
        var params = Map.of("scope", new SkillScope(tenantId, userId), "skillId", 123L,
                "content", content, "frontmatter", content.extraFrontmatter());
        BoundSql sql = sql("updateContentScoped", params);
        assertThat(normalized(sql)).isEqualTo("UPDATE public.personal_skill SET name = ?, description = ?, "
                + "instructions_md = ?, extra_frontmatter = ?, revision = revision + 1, updated_at = clock_timestamp() "
                + "WHERE id = ? AND tenant_id = ? AND owner_user_id = ? AND revision = ?");
        assertBindings(sql, params, "new-name", "新描述", "新正文", content.extraFrontmatter(),
                123L, tenantId, userId, 7L);
        assertThat(sql.getParameterMappings().get(3).getTypeHandler()).isInstanceOf(JsonbTypeHandler.class);
    }

    @ParameterizedTest
    @EnumSource(value = PersonalSkillStatus.class, names = {"ENABLED", "DISABLED"})
    void statusChangeSqlAtomicallyChecksScopeAndRevision(PersonalSkillStatus status) {
        var params = Map.of("scope", SCOPE, "skillId", 123L, "revision", 7L, "status", status);
        BoundSql sql = sql("updateStatusScoped", params);
        assertThat(normalized(sql)).isEqualTo("UPDATE public.personal_skill SET status = ?, "
                + "revision = revision + 1, updated_at = clock_timestamp() "
                + "WHERE id = ? AND tenant_id = ? AND owner_user_id = ? AND revision = ?");
        assertBindings(sql, params, status.getValue(), 123L, 10L, 100L, 7L);
    }

    @Test
    void deleteSqlCannotDeleteANewerOrOutOfScopeRecord() {
        var params = Map.of("scope", SCOPE, "skillId", 123L, "revision", 7L);
        BoundSql sql = sql("deleteScoped", params);
        assertThat(normalized(sql)).isEqualTo("DELETE FROM public.personal_skill "
                + "WHERE id = ? AND tenant_id = ? AND owner_user_id = ? AND revision = ?");
        assertBindings(sql, params, 123L, 10L, 100L, 7L);
    }

    private static BoundSql sql(String method, Map<String, ?> parameters) {
        return configuration.getMappedStatement(PersonalSkillMapper.class.getName() + "." + method)
                .getBoundSql(parameters);
    }

    private static String normalized(BoundSql sql) {
        return sql.getSql().replaceAll("\\s+", " ").trim();
    }

    private static void assertBindings(BoundSql sql, Map<String, ?> params, Object... expected) {
        var meta = configuration.newMetaObject(params);
        assertThat(sql.getParameterMappings().stream().map(p -> meta.getValue(p.getProperty())).toList())
                .containsExactly(expected);
    }
}
