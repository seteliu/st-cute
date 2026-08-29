package com.stioc.cute.file.decode.impl;

import com.stioc.cute.file.decode.FileDecoder;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 表格解码器
 * 支持 .xlsx 与 .xls 格式，自动将各工作表（Sheet）转换为大模型友好的标准 Markdown 表格
 */
@Slf4j
@Component
public class ExcelFileDecoder implements FileDecoder {

    /**
     * 单个工作表最大导出数据行数限制，防止超大表格撑爆大模型上下文
     */
    private static final int MAX_ROWS_PER_SHEET = 300;

    /**
     * 单行最大列数限制
     */
    private static final int MAX_COLS_PER_ROW = 50;

    @Override
    public boolean supports(String extension, String mimeType) {
        if ("xlsx".equalsIgnoreCase(extension) || "xls".equalsIgnoreCase(extension)) {
            return true;
        }
        if (StringUtils.hasText(mimeType)) {
            return mimeType.contains("spreadsheetml") || "application/vnd.ms-excel".equalsIgnoreCase(mimeType);
        }
        return false;
    }

    @Override
    public String decode(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }

        DataFormatter formatter = new DataFormatter();
        StringBuilder sb = new StringBuilder();

        try (Workbook workbook = WorkbookFactory.create(file)) {
            int numberOfSheets = workbook.getNumberOfSheets();
            if (numberOfSheets == 0) {
                return "";
            }

            for (int s = 0; s < numberOfSheets; s++) {
                Sheet sheet = workbook.getSheetAt(s);
                String sheetName = sheet.getSheetName();
                String sheetMarkdown = renderSheetAsMarkdown(sheet, formatter);

                if (StringUtils.hasText(sheetMarkdown)) {
                    sb.append(String.format("### 工作表: %s\n\n", sheetName));
                    sb.append(sheetMarkdown).append("\n\n");
                }
            }
        }

        return sb.toString().trim();
    }

    /**
     * 将单个 Sheet 转换为 Markdown 表格
     */
    private String renderSheetAsMarkdown(Sheet sheet, DataFormatter formatter) {
        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();
        if (firstRowNum < 0 || lastRowNum < firstRowNum) {
            return "";
        }

        List<List<String>> matrix = new ArrayList<>();
        int maxCols = 0;
        int rowCount = 0;

        for (int r = firstRowNum; r <= lastRowNum && rowCount < MAX_ROWS_PER_SHEET; r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }

            short firstCellNum = row.getFirstCellNum();
            short lastCellNum = row.getLastCellNum();
            if (firstCellNum < 0 || lastCellNum < 0) {
                continue;
            }

            List<String> rowData = new ArrayList<>();
            boolean hasContent = false;
            int colLimit = Math.min((int) lastCellNum, MAX_COLS_PER_ROW);

            for (int c = 0; c < colLimit; c++) {
                Cell cell = row.getCell(c);
                String val = "";
                if (cell != null) {
                    val = formatter.formatCellValue(cell);
                    if (val != null) {
                        val = val.trim().replace("\n", " ").replace("|", "\\|");
                    } else {
                        val = "";
                    }
                }
                if (StringUtils.hasText(val)) {
                    hasContent = true;
                }
                rowData.add(val);
            }

            if (hasContent) {
                // 清理行尾连续空单元格
                while (!rowData.isEmpty() && !StringUtils.hasText(rowData.get(rowData.size() - 1))) {
                    rowData.remove(rowData.size() - 1);
                }
                if (rowData.size() > maxCols) {
                    maxCols = rowData.size();
                }
                matrix.add(rowData);
                rowCount++;
            }
        }

        if (matrix.isEmpty() || maxCols == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        // 第一行作为表头
        List<String> header = matrix.get(0);
        sb.append("|");
        for (int c = 0; c < maxCols; c++) {
            String val = c < header.size() ? header.get(c) : "";
            sb.append(" ").append(StringUtils.hasText(val) ? val : "列 " + (c + 1)).append(" |");
        }
        sb.append("\n|");
        for (int c = 0; c < maxCols; c++) {
            sb.append("---|");
        }
        sb.append("\n");

        // 输出剩余数据行
        for (int r = 1; r < matrix.size(); r++) {
            List<String> row = matrix.get(r);
            sb.append("|");
            for (int c = 0; c < maxCols; c++) {
                String val = c < row.size() ? row.get(c) : "";
                sb.append(" ").append(val).append(" |");
            }
            sb.append("\n");
        }

        if (lastRowNum - firstRowNum + 1 > MAX_ROWS_PER_SHEET) {
            sb.append(String.format("\n*(当前工作表总共 %d 行，已截取展示前 %d 行)*\n",
                    (lastRowNum - firstRowNum + 1), MAX_ROWS_PER_SHEET));
        }

        return sb.toString().trim();
    }
}
