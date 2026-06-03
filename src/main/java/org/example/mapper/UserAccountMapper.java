package org.example.mapper;

import org.apache.ibatis.annotations.*;
import org.example.entity.UserAccount;

import java.util.Optional;

@Mapper
public interface UserAccountMapper {

    @Select("""
            SELECT id, username, password_hash, display_name, role,
                   department, phone, email, status, last_login, created_at, updated_at
            FROM agent_user
            WHERE username = #{username}
            LIMIT 1
            """)
    @Results(id = "userAccountResultMap", value = {
            @Result(property = "passwordHash", column = "password_hash"),
            @Result(property = "displayName", column = "display_name"),
            @Result(property = "lastLogin", column = "last_login"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<UserAccount> findByUsername(@Param("username") String username);

    @Select("""
            SELECT id, username, password_hash, display_name, role,
                   department, phone, email, status, last_login, created_at, updated_at
            FROM agent_user
            WHERE id = #{id}
            LIMIT 1
            """)
    @ResultMap("userAccountResultMap")
    Optional<UserAccount> findById(@Param("id") Long id);

    @Insert("""
            INSERT INTO agent_user(username, password_hash, display_name, role,
                                   department, phone, email, status, created_at, updated_at)
            VALUES(#{username}, #{passwordHash}, #{displayName}, #{role},
                   #{department}, #{phone}, #{email}, COALESCE(#{status}, 1), NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserAccount userAccount);

    @Update("""
            UPDATE agent_user
            SET last_login = NOW()
            WHERE id = #{id}
            """)
    int updateLastLogin(@Param("id") Long id);


    @Select("""
            SELECT count(*) from agent_user
""")
    int findUser();
}
