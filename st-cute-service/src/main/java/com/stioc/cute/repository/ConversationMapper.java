package com.stioc.cute.repository;

import com.mybatisflex.core.BaseMapper;
import com.stioc.cute.conversation.access.ConversationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    @Update("update t_conversation set loop_running = 0 where loop_running = 1")
    void resetAllLoopRunning();
}
