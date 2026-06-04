package org.example.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.example.service.session.ChatMessage;

import java.util.List;

@Mapper
public interface ConversationMessageMapper {

    @Insert("""
            INSERT IGNORE INTO conversation_message(session_id, msg_id, role, content, msg_index, created_at)
            VALUES(#{sessionId}, #{msg.msgId}, #{msg.role}, #{msg.content}, #{msgIndex}, NOW())
            """)
    int insert(@Param("sessionId") String sessionId,
               @Param("msg") ChatMessage msg,
               @Param("msgIndex") int msgIndex);

    @Select("""
            SELECT id, session_id, msg_id, role, content, msg_index, created_at
            FROM conversation_message
            WHERE session_id = #{sessionId}
            ORDER BY msg_index ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    @Results(id = "convMsgResultMap", value = {
            @Result(property = "msgId", column = "msg_id"),
            @Result(property = "msgIndex", column = "msg_index"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<ChatMessage> findPageBySessionId(@Param("sessionId") String sessionId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    @Select("""
            SELECT id, session_id, msg_id, role, content, msg_index, created_at
            FROM conversation_message
            WHERE session_id = #{sessionId} AND msg_index > #{afterIndex}
            ORDER BY msg_index ASC
            LIMIT #{limit}
            """)
    @ResultMap("convMsgResultMap")
    List<ChatMessage> findRecentBySessionId(@Param("sessionId") String sessionId,
                                            @Param("afterIndex") int afterIndex,
                                            @Param("limit") int limit);

    @Select("""
            SELECT COALESCE(MAX(msg_index), 0)
            FROM conversation_message
            WHERE session_id = #{sessionId}
            """)
    int getMaxIndex(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM conversation_message WHERE session_id = #{sessionId}")
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
