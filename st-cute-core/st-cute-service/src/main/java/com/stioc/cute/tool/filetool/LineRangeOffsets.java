package com.stioc.cute.tool.filetool;

/**
 * 行号范围对应的字符偏移量区间（1-indexed 对应偏移量）。
 *
 * @param startOffset 起始字符偏移量（包含）
 * @param endOffset   截止字符偏移量（排他上界，覆盖至结束行行尾换行符）
 */
public record LineRangeOffsets(int startOffset, int endOffset) {
}
