package com.stioc.cute.mcp.access;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpStatusVo {
    private String name;
    private String status;
    private String type;
    private List<McpToolVo> tools;
}
