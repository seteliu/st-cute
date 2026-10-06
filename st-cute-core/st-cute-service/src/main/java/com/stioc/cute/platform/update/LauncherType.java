package com.stioc.cute.platform.update;

/**
 * 应用启动底座类型（运行方式判定）
 * <p>
 * 底座（桌面壳 / 官方启动脚本）在拉起 Java 进程时通过环境变量显式声明身份，
 * Java 侧据 {@link #fromEnv()} 判定当前运行方式；两个环境变量均缺失时视为手动运行
 * （终端 / IDE 直接 java -jar、旧版无版本脚本），不支持自动升级。
 * </p>
 * <p>
 * 刻意不用父进程嗅探（ProcessHandle.parent 链路判定）：宿主进程形态在真实环境过于多样
 * （IDE 套 gradle、服务器 bash/sshd/systemd/docker-init 等），跨平台行为不一致；
 * 底座自报家门的环境变量契约是唯一零歧义的判定方式。
 * </p>
 */
public enum LauncherType {

    /** 桌面壳运行（st-cute.exe 托管，含凭证机制与进程观测） */
    DESKTOP("桌面端"),

    /** 官方启动脚本运行（st-cute.cmd / st-cute.sh 循环接力） */
    SCRIPT("官方脚本"),

    /** 手动运行（终端 / IDE 直接 java -jar 或旧版脚本，无接力者，不支持自动升级） */
    MANUAL("手动运行");

    /** 底座类型环境变量名 */
    public static final String ENV_TYPE = "ST_CUTE_LAUNCHER_TYPE";

    /** 底座版本环境变量名 */
    public static final String ENV_VERSION = "ST_CUTE_LAUNCHER_VERSION";

    private final String displayName;

    LauncherType(String displayName) {
        this.displayName = displayName;
    }

    /** 前端展示用名称 */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 依据环境变量判定当前底座类型
     * <p>取值非法或变量缺失时一律归为 {@link #MANUAL}（保守降级：宁可少开升级能力，不可误判）</p>
     */
    public static LauncherType fromEnv() {
        String type = System.getenv(ENV_TYPE);
        if (type == null) {
            return MANUAL;
        }
        switch (type.trim()) {
            case "DESKTOP":
                return DESKTOP;
            case "SCRIPT":
                return SCRIPT;
            default:
                return MANUAL;
        }
    }

    /**
     * 读取底座自报的版本号（{@link #ENV_VERSION}）
     *
     * @return 底座版本号；未设置时返回 null
     */
    public static String launcherVersionFromEnv() {
        String version = System.getenv(ENV_VERSION);
        if (version == null) {
            return null;
        }
        String trimmed = version.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
