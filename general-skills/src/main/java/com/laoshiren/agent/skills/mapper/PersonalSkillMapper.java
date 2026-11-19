package com.laoshiren.agent.skills.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.laoshiren.agent.skills.entity.PersonalSkill;
import com.laoshiren.agent.skills.enums.PersonalSkillStatus;
import com.laoshiren.agent.skills.param.SkillListQuery;
import com.laoshiren.agent.skills.param.SkillScope;
import com.laoshiren.agent.skills.param.UpdateSkill;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 个人技能持久化，业务调用通过 Service 限定租户和用户范围。 */
@Mapper
public interface PersonalSkillMapper extends BaseMapper<PersonalSkill> {
    @Select("""
            <script>
            SELECT count(*) FROM public.personal_skill
            WHERE tenant_id = #{scope.tenantId} AND owner_user_id = #{scope.userId}
            <if test="query.status != null">AND status = #{query.status.value}</if>
            </script>
            """)
    long countScoped(@Param("scope") SkillScope scope, @Param("query") SkillListQuery query);

    @Select("""
            <script>
            SELECT * FROM public.personal_skill
            WHERE tenant_id = #{scope.tenantId} AND owner_user_id = #{scope.userId}
            <if test="query.status != null">AND status = #{query.status.value}</if>
            ORDER BY updated_at DESC, id DESC
            LIMIT #{query.size} OFFSET #{offset}
            </script>
            """)
    @ResultMap("mybatis-plus_PersonalSkill")
    List<PersonalSkill> listScoped(@Param("scope") SkillScope scope, @Param("query") SkillListQuery query,
                                   @Param("offset") long offset);

    /** 框架仓库读取全部已启用技能，不使用管理列表的分页限制。 */
    @Select("""
            SELECT * FROM public.personal_skill
            WHERE tenant_id = #{scope.tenantId} AND owner_user_id = #{scope.userId}
                AND status = 'enabled'
            ORDER BY name ASC
            """)
    @ResultMap("mybatis-plus_PersonalSkill")
    List<PersonalSkill> listEnabledScoped(@Param("scope") SkillScope scope);

    @Select("""
            SELECT * FROM public.personal_skill
            WHERE tenant_id = #{scope.tenantId} AND owner_user_id = #{scope.userId}
                AND status = 'enabled' AND name = #{name}
            """)
    @ResultMap("mybatis-plus_PersonalSkill")
    PersonalSkill findEnabledByNameScoped(@Param("scope") SkillScope scope, @Param("name") String name);

    /** 在一条 UPDATE 内完成归属、版本校验和版本递增，避免先查后改的竞态。 */
    @Update("""
            UPDATE public.personal_skill
            SET name = #{content.name}, description = #{content.description},
                instructions_md = #{content.instructionsMd},
                extra_frontmatter = #{frontmatter,typeHandler=com.laoshiren.agent.typehandler.JsonbTypeHandler},
                revision = revision + 1, updated_at = clock_timestamp()
            WHERE id = #{skillId} AND tenant_id = #{scope.tenantId}
                AND owner_user_id = #{scope.userId} AND revision = #{content.revision}
            """)
    int updateContentScoped(@Param("scope") SkillScope scope, @Param("skillId") long skillId,
                            @Param("content") UpdateSkill content,
                            @Param("frontmatter") Map<String, Object> frontmatter);

    @Update("""
            UPDATE public.personal_skill
            SET status = #{status.value}, revision = revision + 1, updated_at = clock_timestamp()
            WHERE id = #{skillId} AND tenant_id = #{scope.tenantId}
                AND owner_user_id = #{scope.userId} AND revision = #{revision}
            """)
    int updateStatusScoped(@Param("scope") SkillScope scope, @Param("skillId") long skillId,
                           @Param("revision") long revision, @Param("status") PersonalSkillStatus status);

    @Delete("""
            DELETE FROM public.personal_skill
            WHERE id = #{skillId} AND tenant_id = #{scope.tenantId}
                AND owner_user_id = #{scope.userId} AND revision = #{revision}
            """)
    int deleteScoped(@Param("scope") SkillScope scope, @Param("skillId") long skillId,
                     @Param("revision") long revision);
}
