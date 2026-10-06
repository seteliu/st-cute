package com.stioc.cute.platform.update;

import lombok.Data;

/**
 * 升级元数据（latest.json）数据模型
 * <p>
 * latest.json 手动维护并托管于更新源（默认 meta.61jun.com/st-cute/latest.json），
 * 模板见 st-cute-service/src/main/resources/update/latest.template.json。字段均为发布侧人工填写，
 * 服务端仅做透传与比对，不在此层做合法性校验（缺失字段按对应分支降级）。
 * </p>
 */
@Data
public class UpdateMeta {

    /** 最新 jar 包版本号（如 0.3.0） */
    private String jarVersion;

    /** 最新 jar 包下载地址（zip 形式的 base 包） */
    private String jarUrl;

    /** jar 包 sha256 校验值（发布时以 sha256sum 计算，允许 sha256: 前缀） */
    private String jarSha256;

    /** 更新说明（多行文本） */
    private String notes;

    /** 自动升级所需的最小底座版本（官方脚本与桌面壳统一口径） */
    private String minBaseVersion;

    /** 旧底座提示文案（底座版本过低时前端展示） */
    private String oldBaseTip;

    /** 发布页地址（旧底座场景引导用户手动下载完整安装包） */
    private String releaseUrl;
}
