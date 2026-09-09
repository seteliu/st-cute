package com.stioc.cute.repository;

import com.mybatisflex.core.BaseMapper;
import com.stioc.cute.message.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息表 Mapper（宿主持久化层，绑定实体 MessageEntity）
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
