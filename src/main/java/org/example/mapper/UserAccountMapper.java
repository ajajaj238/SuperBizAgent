package org.example.mapper;

import org.apache.ibatis.annotations.*;
import org.example.entity.UserAccount;

import java.util.Optional;

@Mapper
public interface UserAccountMapper {

    @Select("""
            SELECT id, username, display_name, department, phone, email, created_at, updated_at
            FROM agent_user
            WHERE username = #{username}
            LIMIT 1
            """)
    @Results(id = "userAccountResultMap", value = {
            @Result(property = "displayName", column = "display_name"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<UserAccount> findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, display_name, department, phone, email, created_at, updated_at
            FROM agent_user
            WHERE id = #{id}
            LIMIT 1
            """)
    @ResultMap("userAccountResultMap")
    Optional<UserAccount> findById(@Param("id") Long id);

    @Insert("""
            INSERT INTO agent_user(username, display_name, department, phone, email, created_at, updated_at)
            VALUES(#{username}, #{displayName}, #{department}, #{phone}, #{email}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccount userAccount);
}
