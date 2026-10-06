package com.stioc.cute.platform.update;

import lombok.Data;

/**
 * 更新检查结果（GET /api/update/check 响应体）
 * <p>
 * status 取值与前端展示分支一一对应：
 * <ul>
 *   <li>CHECK_FAIL —— latest.json 拉取失败（网络异常 / 元数据非法），前端展示检查失败与重试入口</li>
 *   <li>UNSUPPORTED —— 手动运行（MANUAL），不支持自动升级，前端展示引导文案</li>
 *   <li>OLD_BASE —— 底座版本低于 minBaseVersion，前端展示旧底座提示与发布页跳转</li>
 *   <li>UPDATABLE —— 存在新版本且底座满足要求，前端展示更新按钮</li>
 *   <li>LATEST —— 已是最新版本</li>
 * </ul>
 * 判定优先级：拉取失败 → UNSUPPORTED → OLD_BASE → UPDATABLE / LATEST
 * </p>
 */
@Data
public class UpdateCheckDto {

    /** 底座类型标识（DESKTOP / SCRIPT / MANUAL） */
    private String launcherType;

    /** 底座类型展示名（桌面端 / 官方脚本 / 手动运行） */
    private String launcherTypeName;

    /** 底座自报版本号（MANUAL 或底座未上报时为 null） */
    private String launcherVersion;

    /** 当前 jar 版本号（Maven 注入的 st-cute.version） */
    private String currentVersion;

    /** 远端最新 jar 版本号（拉取失败时为 null） */
    private String latestVersion;

    /** 检查结论（CHECK_FAIL / UNSUPPORTED / OLD_BASE / UPDATABLE / LATEST） */
    private String status;

    /** 更新说明（透传 latest.json 的 notes） */
    private String notes;

    /** 旧底座提示文案（透传 latest.json 的 oldBaseTip） */
    private String oldBaseTip;

    /** 发布页地址（透传 latest.json 的 releaseUrl） */
    private String releaseUrl;

    /** 自动升级所需最小底座版本（透传 latest.json 的 minBaseVersion） */
    private String minBaseVersion;

    /** 上次升级失败原因（24h 内有效；浏览器离线错过的失败事件经文件状态化后捎带，无则为 null） */
    private String lastFailureReason;
}
