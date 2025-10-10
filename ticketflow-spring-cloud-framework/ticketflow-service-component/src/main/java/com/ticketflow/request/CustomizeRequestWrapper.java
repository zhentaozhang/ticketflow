package com.ticketflow.request;

import com.ticketflow.util.StringUtil;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读取的 Request 包装器。
 * 缓存请求体到 byte[]，配合 RequestWrapperFilter 使用，
 * 支持多次读取（如鉴权 + 业务参数解析）
 */
public class CustomizeRequestWrapper extends HttpServletRequestWrapper {
    
    private final String requestBody;
        
    
    public CustomizeRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        requestBody = StringUtil.inputStreamConvertString(request.getInputStream());
    }
    
    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream byteArrayInputStream = 
                new ByteArrayInputStream(requestBody.getBytes(StandardCharsets.UTF_8));
        return new ServletInputStream() {
            @Override
            public int read() {
                return byteArrayInputStream.read();
            }
            
            @Override
            public boolean isFinished() {
                return false;
            }
            
            @Override
            public boolean isReady() {
                return false;
            }
            
            @Override
            public void setReadListener(ReadListener listener) {
                
            }
        };
    }
    
    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(this.getInputStream()));
    }
    
    public String getRequestBody() {
        return this.requestBody;
    }
}
