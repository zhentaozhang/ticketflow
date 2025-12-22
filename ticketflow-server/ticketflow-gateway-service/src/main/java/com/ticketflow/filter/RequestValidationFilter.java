package com.ticketflow.filter;


import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
import com.baidu.fsg.uid.UidGenerator;
import com.ticketflow.conf.RequestTemporaryWrapper;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.ArgumentError;
import com.ticketflow.exception.ArgumentException;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.pro.limit.RateLimiter;
import com.ticketflow.pro.limit.RateLimiterProperty;
import com.ticketflow.property.GatewayProperty;
import com.ticketflow.service.ApiRestrictService;
import com.ticketflow.service.ChannelDataService;
import com.ticketflow.service.TokenService;
import com.ticketflow.threadlocal.BaseParameterHolder;
import com.ticketflow.util.RsaSignTool;
import com.ticketflow.util.RsaTool;
import com.ticketflow.util.StringUtil;
import com.ticketflow.vo.GetChannelDataVo;
import com.ticketflow.vo.UserVo;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.rewrite.CachedBodyOutputMessage;
import org.springframework.cloud.gateway.support.BodyInserterContext;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;
import org.springframework.web.reactive.function.BodyInserter;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
import static com.ticketflow.constant.Constant.TRACE_ID;
import static com.ticketflow.constant.GatewayConstant.BUSINESS_BODY;
import static com.ticketflow.constant.GatewayConstant.CODE;
import static com.ticketflow.constant.GatewayConstant.ENCRYPT;
import static com.ticketflow.constant.GatewayConstant.NO_VERIFY;
import static com.ticketflow.constant.GatewayConstant.REQUEST_BODY;
import static com.ticketflow.constant.GatewayConstant.TOKEN;
import static com.ticketflow.constant.GatewayConstant.USER_ID;
import static com.ticketflow.constant.GatewayConstant.V2;
import static com.ticketflow.constant.GatewayConstant.VERIFY_VALUE;

/**
 * Gateway 请求入口过滤器（order=-2，最高优先级）。
 * 职责链：RateLimiter（自适应信号量熔断）→ Token 验证（RSA 签名→渠道→JWT）→
 * API 限流（滑动窗口 Lua）→ 透传 header（traceId/userId/code）→ 下游微服务
 * <p>
 * 请求路径：
 * 1. rateLimiterProperty.rateSwitch 开启时，优先通过 RateLimiter 争取 Semaphore 许可
 * 2. JSON 请求：readBody() 异步捕获 body → doExecute() 校验签名/渠道/token → 注入 header
 * 3. 非 JSON 请求：直接透传，仅注入 traceId/gray/noVerify
 * <p>
 * 注意：链路中抛出 TicketFlowFrameException 会被全局 ErrorHandler 捕获并返回统一 JSON 错误响应
 */

@Component
@Slf4j
public class RequestValidationFilter implements GlobalFilter, Ordered {

    @Autowired
    private ServerCodecConfigurer serverCodecConfigurer;

    @Autowired
    private ChannelDataService channelDataService;

    @Autowired
    private ApiRestrictService apiRestrictService;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private GatewayProperty gatewayProperty;

    @Autowired
    private UidGenerator uidGenerator;

    @Autowired
    private RateLimiterProperty rateLimiterProperty;

    @Autowired
    private RateLimiter rateLimiter;


    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        if (rateLimiterProperty.getRateSwitch()) {
            try {
                rateLimiter.acquire();
            } catch (InterruptedException e) {
                log.error("interrupted error", e);
                throw new TicketFlowFrameException(BaseCode.THREAD_INTERRUPTED);
            }
            return doFilter(exchange, chain)
                    .doFinally(signalType -> rateLimiter.release());
        } else {
            return doFilter(exchange, chain);
        }
    }

    public Mono<Void> doFilter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String traceId = request.getHeaders().getFirst(TRACE_ID);
        String gray = request.getHeaders().getFirst(GRAY_PARAMETER);
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        if (StringUtil.isEmpty(traceId)) {
            traceId = String.valueOf(uidGenerator.getUid());
        }
        MDC.put(TRACE_ID, traceId);
        Map<String, String> headMap = new HashMap<>(8);
        headMap.put(TRACE_ID, traceId);
        headMap.put(GRAY_PARAMETER, gray);
        if (StringUtil.isNotEmpty(noVerify)) {
            headMap.put(NO_VERIFY, noVerify);
        }
        BaseParameterHolder.setParameter(TRACE_ID, traceId);
        BaseParameterHolder.setParameter(GRAY_PARAMETER, gray);
        MediaType contentType = request.getHeaders().getContentType();
        //application json请求
        if (Objects.nonNull(contentType) && contentType.toString().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE.toLowerCase())) {
            return readBody(exchange, chain, headMap);
        } else {
            Map<String, String> map = doExecute("", exchange);
            map.remove(REQUEST_BODY);
            map.putAll(headMap);
            request.mutate().headers(httpHeaders -> {
                map.forEach(httpHeaders::set);
            });
            return chain.filter(exchange);
        }
    }

    private Mono<Void> readBody(ServerWebExchange exchange, GatewayFilterChain chain, Map<String, String> headMap) {
        log.info("current thread readBody : {}", Thread.currentThread().getName());
        RequestTemporaryWrapper requestTemporaryWrapper = new RequestTemporaryWrapper();

        // WebFlux 反应式 body 读取：bodyToMono → execute() 验签/解密/注入参数 → 缓存 body 供下游读取
        ServerRequest serverRequest = ServerRequest.create(exchange, serverCodecConfigurer.getReaders());
        Mono<String> modifiedBody = serverRequest
                .bodyToMono(String.class)
                .flatMap(originalBody -> Mono.just(execute(requestTemporaryWrapper, originalBody, exchange)))
                .switchIfEmpty(Mono.defer(() -> Mono.just(execute(requestTemporaryWrapper, "", exchange))));

        // 将处理后的 body 写入 CachedBodyOutputMessage（必须移除 Content-Length 让 WebFlux 重新计算 chunked 或新长度）
        BodyInserter bodyInserter = BodyInserters.fromPublisher(modifiedBody, String.class);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(exchange.getRequest().getHeaders());
        headers.remove(HttpHeaders.CONTENT_LENGTH);

        CachedBodyOutputMessage outputMessage = new CachedBodyOutputMessage(exchange, headers);
        return bodyInserter
                .insert(outputMessage, new BodyInserterContext())
                .then(Mono.defer(() -> chain.filter(
                        exchange.mutate().request(decorateHead(exchange, headers, outputMessage, requestTemporaryWrapper, headMap)).build()
                )))
                .onErrorResume((Function<Throwable, Mono<Void>>) throwable -> Mono.error(throwable));
    }

    public String execute(RequestTemporaryWrapper requestTemporaryWrapper, String requestBody, ServerWebExchange exchange) {
        //进行业务验证，并将相关参数放入map
        Map<String, String> map = doExecute(requestBody, exchange);
        String body = map.get(REQUEST_BODY);
        map.remove(REQUEST_BODY);
        requestTemporaryWrapper.setMap(map);
        return body;
    }

    private Map<String, String> doExecute(String originalBody, ServerWebExchange exchange) {
        ServerHttpRequest request = exchange.getRequest();
        String requestBody = originalBody;
        Map<String, String> bodyContent = new HashMap<>(32);
        if (StringUtil.isNotEmpty(originalBody)) {
            bodyContent = JSON.parseObject(originalBody, Map.class);
        }
        String code = null;
        String token;
        String userId = null;
        String url = request.getPath().value();
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        boolean allowNormalAccess = gatewayProperty.isAllowNormalAccess();
        // 非允许普通访问模式下，noVerify=verify_value 的请求被拒绝（仅签名访问模式）
        if ((!allowNormalAccess) && (VERIFY_VALUE.equals(noVerify))) {
            throw new TicketFlowFrameException(BaseCode.ONLY_SIGNATURE_ACCESS_IS_ALLOWED);
        }
        // 需要签名验证且 URL 不在跳过列表：执行加密解密 + RSA 验签 + Token 校验
        if (checkParameter(originalBody, noVerify) && !skipCheckParameter(url)) {

            String encrypt = request.getHeaders().getFirst(ENCRYPT);
            code = bodyContent.get(CODE);
            token = request.getHeaders().getFirst(TOKEN);

            // 根据渠道 code 获取渠道配置（含公钥、密钥、tokenSecret）
            GetChannelDataVo channelDataVo = channelDataService.getChannelDataByCode(code);

            // 加密字段解密（encrypt=V2 时，businessBody 使用 dataSecretKey RSA 解密）
            if (StringUtil.isNotEmpty(encrypt) && V2.equals(encrypt)) {
                String decrypt = RsaTool.decrypt(bodyContent.get(BUSINESS_BODY), channelDataVo.getDataSecretKey());
                bodyContent.put(BUSINESS_BODY, decrypt);
            }
            // RSA 签名验证（防止请求被篡改）
            boolean checkFlag = RsaSignTool.verifyRsaSign256(bodyContent, channelDataVo.getSignPublicKey());
            if (!checkFlag) {
                throw new TicketFlowFrameException(BaseCode.RSA_SIGN_ERROR);
            }

            // Token 检查：URL 在 checkTokenPaths 中则必须携带 token
            boolean skipCheckTokenResult = skipCheckToken(url);
            if (!skipCheckTokenResult && StringUtil.isEmpty(token)) {
                ArgumentError argumentError = new ArgumentError();
                argumentError.setArgumentName(token);
                argumentError.setMessage("token参数为空");
                List<ArgumentError> argumentErrorList = new ArrayList<>();
                argumentErrorList.add(argumentError);
                throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(), argumentErrorList);
            }

            // 需要 token 时，解析 JWT 获取 userId
            if (!skipCheckTokenResult) {
                UserVo userVo = tokenService.getUser(token, code, channelDataVo.getTokenSecret());
                userId = userVo.getId();
            }

            // 部分接口需要 userId 但 token 检查被跳过时，额外尝试通过 token 获取（getUserIdPaths 白名单）
            if (StringUtil.isEmpty(userId) && checkNeedUserId(url) && StringUtil.isNotEmpty(token)) {
                UserVo userVo = tokenService.getUser(token, code, channelDataVo.getTokenSecret());
                userId = userVo.getId();
            }

            requestBody = bodyContent.get(BUSINESS_BODY);
        }
        // 所有请求统一执行 API 限流（按 IP+userID+URL 滑动窗口）
        apiRestrictService.apiRestrict(userId, url, request);
        // 返回需要注入到下游请求头中的参数
        Map<String, String> map = new HashMap<>(4);
        map.put(REQUEST_BODY, requestBody);
        if (StringUtil.isNotEmpty(code)) {
            map.put(CODE, code);
        }
        if (StringUtil.isNotEmpty(userId)) {
            map.put(USER_ID, userId);
        }
        return map;
    }

    /**
     * 将网关层request请求头中的重要参数传递给后续的微服务中
     */
    private ServerHttpRequestDecorator decorateHead(ServerWebExchange exchange, HttpHeaders headers, CachedBodyOutputMessage outputMessage, RequestTemporaryWrapper requestTemporaryWrapper, Map<String, String> headMap) {
        return new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override
            public HttpHeaders getHeaders() {
                log.info("current thread getHeaders: {}", Thread.currentThread().getName());
                long contentLength = headers.getContentLength();
                HttpHeaders newHeaders = new HttpHeaders();
                newHeaders.putAll(headers);
                Map<String, String> map = requestTemporaryWrapper.getMap();
                if (CollectionUtil.isNotEmpty(map)) {
                    newHeaders.setAll(map);
                }
                if (CollectionUtil.isNotEmpty(headMap)) {
                    newHeaders.setAll(headMap);
                }
                if (contentLength > 0) {
                    newHeaders.setContentLength(contentLength);
                } else {
                    newHeaders.set(HttpHeaders.TRANSFER_ENCODING, "chunked");
                }
                if (CollectionUtil.isNotEmpty(headMap) && StringUtil.isNotEmpty(headMap.get(TRACE_ID))) {
                    MDC.put(TRACE_ID, headMap.get(TRACE_ID));
                }
                return newHeaders;
            }

            @Override
            public Flux<DataBuffer> getBody() {
                return outputMessage.getBody();
            }
        };
    }

    @Override
    public int getOrder() {
        return -2;
    }

    public boolean skipCheckToken(String url) {
        for (String skipCheckTokenPath : gatewayProperty.getCheckTokenPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return false;
            }
        }
        return true;
    }

    public boolean skipCheckParameter(String url) {
        for (String skipCheckTokenPath : gatewayProperty.getCheckSkipParmeterPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(skipCheckTokenPath, url)) {
                return true;
            }
        }
        return false;
    }

    public boolean checkParameter(String originalBody, String noVerify) {
        return (!(VERIFY_VALUE.equals(noVerify))) && StringUtil.isNotEmpty(originalBody);
    }

    private boolean checkNeedUserId(String url) {
        for (String userIdPath : gatewayProperty.getUserIdPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(userIdPath, url)) {
                return true;
            }
        }
        return false;
    }
}
