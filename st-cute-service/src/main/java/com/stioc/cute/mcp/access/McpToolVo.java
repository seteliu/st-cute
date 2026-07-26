package com.stioc.cute.mcp.access;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class McpToolVo {
    private String name;
    private String description;
    private String schema;
}
