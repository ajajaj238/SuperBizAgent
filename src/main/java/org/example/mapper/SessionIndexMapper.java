package org.example.mapper;

import org.apache.ibatis.annotations.*;
import org.example.entity.SessionIndex;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SessionIndexMapper {

    @Select("""
            SELECT id, user_id, session_id, title, status, message_count, summary, created_at, updated_at
            FROM session_index
            WHERE session_id = #{sessionId}
            LIMIT 1
            """)
    @Results(id = "sessionIndexResultMap", value = {
            @Result(property = "userId", column = "user_id"),
            @Result(property = "sessionId", column = "session_id"),
            @Result(property = "messageCount", column = "message_count"),
            @Result(property = "createdAt", column = "created_at"),
            @Result(property = "updatedAt", column = "updated_at")
    })
    Optional<SessionIndex> findBySessionId(@Param("sessionId") String sessionId);

    @Select("""
            SELECT id, user_id, session_id, title, status, message_count, summary, created_at, updated_at
            FROM session_index
            WHERE user_id = #{userId}
            ORDER BY updated_at DESC
            """)
    @ResultMap("sessionIndexResultMap")
    List<SessionIndex> findByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id, session_id, title, status, message_count, summary, created_at, updated_at
            FROM session_index
            WHERE status = 1 AND message_count > 0
            ORDER BY updated_at DESC
            """)
    @ResultMap("sessionIndexResultMap")
    List<SessionIndex> findActiveSessions();

    @Select("""
            SELECT DISTINCT user_id FROM session_index
            WHERE status = 1
            """)
    List<Long> findDistinctUserIds();

    @Insert("""
            INSERT INTO session_index(user_id, session_id, title, status, message_count, summary, created_at, updated_at)
            VALUES(#{userId}, #{sessionId}, #{title}, #{status}, #{messageCount}, #{summary}, NOW(), NOW())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(SessionIndex sessionIndex);

    @Update("""
            UPDATE session_index
            SET title = #{title},
                status = #{status},
                message_count = #{messageCount},
                summary = #{summary},
                updated_at = NOW()
            WHERE id = #{id}
            """)
    int update(SessionIndex sessionIndex);
}
