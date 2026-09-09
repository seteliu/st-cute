package com.stioc.cute.repository;

import com.mybatisflex.core.BaseMapper;
import com.stioc.cute.conversation.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

/**
 * 会话表 Mapper（宿主持久化层，绑定实体 ConversationEntity）
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    @Update("update t_conversation set loop_running = 0 where loop_running = 1")
    void resetAllLoopRunning();
}
