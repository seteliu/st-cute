package com.stioc.cute.project.access;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.time.LocalDateTime;

/**
 * 对应 SQLite 数据库中的 t_projects 项目表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(value = "t_project")
public class ProjectEntity {

    /**
     * 项目主键 ID
     */
    @Id(keyType = KeyType.Auto)
    private Long id;

    /**
     * 项目创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 项目名称
     */
    private String name;

    /**
     * 项目本地物理绝对路径
     */
    private String path;

    /**
     * 是否在界面上处于展开状态 (true: 展开, false: 折叠)
     */
    private Boolean expanded;

    /**
     * 是否为当前活跃会话项目 (true: 活跃, false: 非活跃)
     */
    private Boolean active;

}
