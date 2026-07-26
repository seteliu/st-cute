package com.stioc.cute.repository;

import com.mybatisflex.core.BaseMapper;
import com.stioc.cute.message.access.MessageEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {
}
