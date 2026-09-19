package com.stioc.cute.tool.filetool;

/**
 * 代码片段匹配定位结果封装。
 *
 * @param startOffset  匹配起始字符偏移量（相对当前搜索目标文本）
 * @param endOffset    匹配结束字符偏移量（相对当前搜索目标文本）
 * @param errorMessage 匹配失败或多重命中时的结构化错误文案（成功时为 null）
 */
public record MatchLocateResult(int startOffset, int endOffset, String errorMessage) {

    public boolean isSuccess() {
        return errorMessage == null && startOffset >= 0 && endOffset >= startOffset;
    }

    public static MatchLocateResult success(int start, int end) {
        return new MatchLocateResult(start, end, null);
    }

    public static MatchLocateResult error(String message) {
        return new MatchLocateResult(-1, -1, message);
    }
}
