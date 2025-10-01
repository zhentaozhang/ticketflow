package com.ticketflow.exception;

import lombok.Data;

/**
 * 参数校验错误详情。
 * 与 ArgumentException 配合使用，标记哪个字段（argumentName）出了什么错（message）
 */
@Data
public class ArgumentError {
	
	private String argumentName;
	
	private String message;
}
