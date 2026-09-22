package com.stioc.cute.platform.config;

import com.stioc.cute.platform.common.Result;
import com.stioc.cute.platform.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({IllegalStateException.class, IllegalArgumentException.class})
    public Result<?> handleIllegalStateAndArgumentException(RuntimeException e) {
        log.warn("业务状态校验拦截: {}", e.getMessage());
        // 该类异常消息由代码显式抛出（如"供应商分组名称不能为空"），属面向用户的校验提示，
        // 但对已知携带内部细节的包装形态仍按 500 口径处理，避免路径/SQL 片段外泄
        if (isInternalDetailException(e)) {
            log.error("状态类异常携带内部实现细节，按内部错误处理", e);
            return Result.error(500, "系统内部错误，请查看服务端日志排查");
        }
        return Result.error(400, e.getMessage());
    }

    /**
     * 判定状态类异常是否携带内部实现细节。
     * <p>
     * {@code IllegalStateException} 常被用作「包装底层异常」的载体（如把 IOException / SQLException
     * 作为 cause 抛出），此时其 message 往往是路径、SQL 片段等内部信息。
     * 无 cause 者才视为代码显式抛出的用户校验提示。
     * </p>
     *
     * @param e 待判定异常
     * @return true 表示应降级为内部错误，不回传原始消息
     */
    private boolean isInternalDetailException(RuntimeException e) {
        return e.getCause() != null;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<?> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("静态资源不存在: {}", e.getResourcePath());
        return Result.error(404, "静态资源不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        // 内部细节只落日志，不回传客户端：异常消息可能包含路径、SQL 片段等内部实现信息
        log.error("系统未捕获异常", e);
        return Result.error(500, "系统内部错误，请查看服务端日志排查");
    }
}
