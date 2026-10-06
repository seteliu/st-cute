package com.stioc.cute.controller;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.update.UpdateCheckDto;
import com.stioc.cute.platform.update.UpdateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 应用更新控制器
 * <p>
 * 安全约定：本控制器全部接口均不进入 WebSecurityFilter 白名单，走正常登录鉴权——
 * 更新检查与后续的升级执行均属管理面操作，不应暴露给未登录调用方。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/update")
public class UpdateController {

    @Resource
    private UpdateService updateService;

    /**
     * 更新检查：判定当前运行方式、比对远端 latest.json 并返回结论
     *
     * @param refresh true 表示绕过服务端缓存强制回源（前端「重新检查」入口）
     */
    @GetMapping("/check")
    public Result<UpdateCheckDto> check(@RequestParam(defaultValue = "false") boolean refresh) {
        return Result.success(updateService.check(refresh));
    }

    /**
     * 执行升级：下载 → sha256 校验 → 广播重启预告 → 优雅停机以退出码 88 结束，
     * 换包与重启由接力者（官方脚本 / 桌面壳）完成
     * <p>
     * 前置条件不满足与并发占用同步返回业务错误；受理成功即返回目标版本号，
     * 后续进度经 WebSocket（S2C_UPDATE_PROGRESS / S2C_UPDATE_RESTARTING）推送
     * </p>
     */
    @PostMapping("/apply")
    public Result<String> apply() {
        return Result.success(updateService.apply());
    }

    /**
     * 升级流程状态查询（前端进入关于页时探测是否有进行中的升级，用于恢复进度展示）
     */
    @GetMapping("/status")
    public Result<Boolean> status() {
        return Result.success(updateService.isUpgrading());
    }
}
