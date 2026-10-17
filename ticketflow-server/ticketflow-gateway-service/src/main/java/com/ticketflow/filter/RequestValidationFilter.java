package com.ticketflow.filter;


import cn.hutool.core.collection.CollectionUtil;
import com.alibaba.fastjson.JSON;
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
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import static com.ticketflow.constant.Constant.GRAY_PARAMETER;
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
 * API 限流（滑动窗口 Lua）→ 透传 header（gray/userId/code）→ 下游微服务
 * <p>
 * 请求路径：
 * 1. rateLimiterProperty.rateSwitch 开启时，优先通过 RateLimiter 争取 Semaphore 许可
 * 2. JSON 请求：readBody() 异步捕获 body → doExecute() 校验签名/渠道/token → 注入 header
 * 3. 非 JSON 请求：直接透传，仅透传 gray/noVerify
 * <p>
 * 注意：链路中抛出 TicketFlowFrameException 会被全局 ErrorHandler 捕获并返回统一 JSON 错误响应
 * <p>
 * 说明：链路追踪上下文（W3C traceparent）由 OpenTelemetry Java Agent 在网关入口自动生成并注入下游，
 * 不再自研 traceId Header 透传。
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
    private RateLimiterProperty rateLimiterProperty;

    @Autowired
    private RateLimiter rateLimiter;


    @Override
    public Mono<Void> filter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }
        if (rateLimiterProperty.getRateSwitch()) {
            rateLimiter.acquire();
            try {
                return doFilter(exchange, chain)
                        .doFinally(signalType -> rateLimiter.release());
            } catch (Throwable e) {
                // doFilter 可能同步抛出（如非 JSON 路径）时 doFinally 还没挂上，
                // 不释放会让许可泄漏、信号量被逐步耗尽直到网关整体拒绝服务
                rateLimiter.release();
                throw e;
            }
        } else {
            return doFilter(exchange, chain);
        }
    }

    public Mono<Void> doFilter(final ServerWebExchange exchange, final GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String gray = request.getHeaders().getFirst(GRAY_PARAMETER);
        String noVerify = request.getHeaders().getFirst(NO_VERIFY);
        Map<String, String> headMap = new HashMap<>(8);
        headMap.put(GRAY_PARAMETER, gray);
        if (StringUtil.isNotEmpty(noVerify)) {
            headMap.put(NO_VERIFY, noVerify);
        }
        BaseParameterHolder.setParameter(GRAY_PARAMETER, gray);
        MediaType contentType = request.getHeaders().getContentType();
        //application json请求
        if (Objects.nonNull(contentType) && contentType.toString().toLowerCase().contains(MediaType.APPLICATION_JSON_VALUE.toLowerCase())) {
            return readBody(exchange, chain, headMap);
        } else {
            // doExecute 内含同步 RSA/Redis/Feign，放到弹性线程池执行，避免阻塞 WebFlux 事件循环
            return Mono.fromCallable(() -> {
                        Map<String, String> map = doExecute("", exchange);
                        map.remove(REQUEST_BODY);
                        map.putAll(headMap);
                        return map;
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(map -> {
                        request.mutate().headers(httpHeaders -> {
                            // 先剥离外部客户端可能伪造的内部身份头，再由网关注入验签得到的权威值，
                            // 避免下游 BaseParameterFilter 把伪造的 userId/code 当成登录用户
                            httpHeaders.remove(USER_ID);
                            httpHeaders.remove(CODE);
                            map.forEach(httpHeaders::set);
                        });
                        return chain.filter(exchange);
                    });
        }
    }

    private Mono<Void> readBody(ServerWebExchange exchange, GatewayFilterChain chain, Map<String, String> headMap) {
        if (log.isDebugEnabled()) {
            log.debug("current thread readBody : {}", Thread.currentThread().getName());
        }
        RequestTemporaryWrapper requestTemporaryWrapper = new RequestTemporaryWrapper();

        // WebFlux 反应式 body 读取：bodyToMono → execute() 验签/解密/注入参数 → 缓存 body 供下游读取
        ServerRequest serverRequest = ServerRequest.create(exchange, serverCodecConfigurer.getReaders());
        // execute() 内含同步 RSA/Redis/Feign（ChannelDataService 还会 future.get），
        // 必须切到弹性线程池，否则整段校验会在事件循环线程上阻塞
        Mono<String> modifiedBody = serverRequest
                .bodyToMono(String.class)
                .flatMap(originalBody -> Mono.fromCallable(() -> execute(requestTemporaryWrapper, originalBody, exchange))
                        .subscribeOn(Schedulers.boundedElastic()))
                .switchIfEmpty(Mono.defer(() -> Mono.fromCallable(() -> execute(requestTemporaryWrapper, "", exchange))
                        .subscribeOn(Schedulers.boundedElastic())));

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
        boolean needCheckTokenResult = needCheckToken(url);
        String encrypt = request.getHeaders() != null ? request.getHeaders().getFirst(ENCRYPT) : null;
        code = bodyContent.get(CODE);
        if (StringUtil.isEmpty(code) && request.getHeaders() != null) {
            code = request.getHeaders().getFirst(CODE);
        }
        if (StringUtil.isEmpty(code) && request.getQueryParams() != null) {
            code = request.getQueryParams().getFirst(CODE);
        }
        token = request.getHeaders() != null ? request.getHeaders().getFirst(TOKEN) : null;

        // 1. 参数与签名校验：当请求有 Body 且不在跳过列表时，执行解密与 RSA 验签
        if (checkParameter(originalBody, noVerify) && !skipCheckParameter(url)) {
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

            requestBody = bodyContent.get(BUSINESS_BODY);
        }

        // 2. Token 校验：独立于 Body 是否存在。只要 URL 属于受保护路径（needCheckTokenResult），必须校验 Token
        if (needCheckTokenResult) {
            if (StringUtil.isEmpty(token)) {
                ArgumentError argumentError = new ArgumentError();
                argumentError.setArgumentName(TOKEN);
                argumentError.setMessage("token参数为空");
                List<ArgumentError> argumentErrorList = new ArrayList<>();
                argumentErrorList.add(argumentError);
                throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(), argumentErrorList);
            }
            if (StringUtil.isEmpty(code)) {
                ArgumentError argumentError = new ArgumentError();
                argumentError.setArgumentName(CODE);
                argumentError.setMessage("code参数为空");
                List<ArgumentError> argumentErrorList = new ArrayList<>();
                argumentErrorList.add(argumentError);
                throw new ArgumentException(BaseCode.ARGUMENT_EMPTY.getCode(), argumentErrorList);
            }
            GetChannelDataVo channelDataVo = channelDataService.getChannelDataByCode(code);
            UserVo userVo = tokenService.getUser(token, code, channelDataVo.getTokenSecret());
            userId = userVo.getId();
        }

        // 3. 部分公开接口额外尝试通过 token 提取 userId（若携带了 token）
        if (StringUtil.isEmpty(userId) && checkNeedUserId(url) && StringUtil.isNotEmpty(token) && StringUtil.isNotEmpty(code)) {
            GetChannelDataVo channelDataVo = channelDataService.getChannelDataByCode(code);
            UserVo userVo = tokenService.getUser(token, code, channelDataVo.getTokenSecret());
            userId = userVo.getId();
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
                long contentLength = headers.getContentLength();
                HttpHeaders newHeaders = new HttpHeaders();
                newHeaders.putAll(headers);
                // 剥离外部客户端可能伪造的内部身份头，仅保留网关注入的权威 userId/code（setAll 覆盖）
                newHeaders.remove(USER_ID);
                newHeaders.remove(CODE);
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

    public boolean needCheckToken(String url) {
        for (String checkTokenPath : gatewayProperty.getCheckTokenPaths()) {
            PathMatcher matcher = new AntPathMatcher();
            if (matcher.match(checkTokenPath, url)) {
                return true;
            }
        }
        return false;
    }

    public boolean skipCheckToken(String url) {
        return !needCheckToken(url);
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
