package com.ticketflow.filter;

import com.alibaba.fastjson.JSON;
import com.ticketflow.conf.RequestTemporaryWrapper;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.ArgumentException;
import com.ticketflow.exception.TicketFlowFrameException;
import com.ticketflow.property.GatewayProperty;
import com.ticketflow.service.ApiRestrictService;
import com.ticketflow.service.ChannelDataService;
import com.ticketflow.service.TokenService;
import com.ticketflow.util.RsaSignTool;
import com.ticketflow.vo.GetChannelDataVo;
import com.ticketflow.vo.UserVo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ServerWebExchange;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static com.ticketflow.constant.GatewayConstant.BUSINESS_BODY;
import static com.ticketflow.constant.GatewayConstant.CODE;
import static com.ticketflow.constant.GatewayConstant.NO_VERIFY;
import static com.ticketflow.constant.GatewayConstant.REQUEST_BODY;
import static com.ticketflow.constant.GatewayConstant.TOKEN;
import static com.ticketflow.constant.GatewayConstant.USER_ID;
import static com.ticketflow.constant.GatewayConstant.VERIFY_VALUE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RequestValidationFilterTest {

    private static final String SECRET = "test-token-secret";

    private static String privateKey;
    private static String publicKey;

    private final RequestValidationFilter filter = new RequestValidationFilter();

    @Mock
    private ChannelDataService channelDataService;

    @Mock
    private ApiRestrictService apiRestrictService;

    @Mock
    private TokenService tokenService;

    private GatewayProperty property;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());
        publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @BeforeEach
    void setUp() {
        property = new GatewayProperty();
        property.setCheckTokenPaths(new String[0]);
        property.setCheckSkipParmeterPaths(new String[0]);
        property.setUserIdPaths(new String[0]);
        ReflectionTestUtils.setField(filter, "channelDataService", channelDataService);
        ReflectionTestUtils.setField(filter, "apiRestrictService", apiRestrictService);
        ReflectionTestUtils.setField(filter, "tokenService", tokenService);
        ReflectionTestUtils.setField(filter, "gatewayProperty", property);
    }

    @Test
    void checkParameterShouldBeDisabledByNoVerifyValue() {
        assertFalse(filter.checkParameter("{}", VERIFY_VALUE));
    }

    @Test
    void checkParameterShouldBeDisabledForEmptyBody() {
        assertFalse(filter.checkParameter("", null));
        assertTrue(filter.checkParameter("{}", null));
    }

    @Test
    void skipCheckTokenShouldHonorConfiguredPaths() {
        property.setCheckTokenPaths(new String[]{"/user/**"});

        assertFalse(filter.skipCheckToken("/user/login"));
        assertTrue(filter.skipCheckToken("/program/list"));
    }

    @Test
    void skipCheckParameterShouldHonorConfiguredPaths() {
        property.setCheckSkipParmeterPaths(new String[]{"/program/**"});

        assertTrue(filter.skipCheckParameter("/program/list"));
        assertFalse(filter.skipCheckParameter("/user/login"));
    }

    @Test
    void signatureOnlyModeShouldRejectNoVerifyRequest() {
        property.setAllowNormalAccess(false);
        HttpHeaders headers = new HttpHeaders();
        headers.add(NO_VERIFY, VERIFY_VALUE);
        ServerWebExchange exchange = mockExchange("/user/test", headers);
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> filter.execute(wrapper, "{}", exchange));

        assertEquals(BaseCode.ONLY_SIGNATURE_ACCESS_IS_ALLOWED.getCode(), exception.getCode());
    }

    @Test
    void validSignatureChainShouldExtractCodeUserIdAndBody() {
        property.setCheckTokenPaths(new String[]{"/user/**"});
        stubChannelData();
        UserVo user = new UserVo();
        user.setId("user-1");
        when(tokenService.getUser("mock-token", "2", SECRET)).thenReturn(user);

        Map<String, Object> body = new HashMap<>();
        body.put(CODE, "2");
        body.put(BUSINESS_BODY, "hello");
        body.put("orderId", 123);
        body.put("sign", RsaSignTool.rsaSign256((Map) body, privateKey));

        HttpHeaders headers = new HttpHeaders();
        headers.add(TOKEN, "mock-token");
        ServerWebExchange exchange = mockExchange("/user/test", headers);
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        String result = filter.execute(wrapper, JSON.toJSONString(body), exchange);

        assertEquals("hello", result);
        assertEquals("2", wrapper.getMap().get(CODE));
        assertEquals("user-1", wrapper.getMap().get(USER_ID));
        verify(apiRestrictService).apiRestrict("user-1", "/user/test", exchange.getRequest());
    }

    @Test
    void tamperedBodyShouldFailSignatureVerification() {
        property.setCheckTokenPaths(new String[]{"/user/**"});
        stubChannelData();

        Map<String, Object> signedBody = new HashMap<>();
        signedBody.put(CODE, "2");
        signedBody.put(BUSINESS_BODY, "hello");
        String sign = RsaSignTool.rsaSign256((Map) signedBody, privateKey);
        signedBody.put("sign", sign);

        Map<String, Object> tamperedBody = new HashMap<>(signedBody);
        tamperedBody.put(BUSINESS_BODY, "tampered");

        HttpHeaders headers = new HttpHeaders();
        headers.add(TOKEN, "mock-token");
        ServerWebExchange exchange = mockExchange("/user/test", headers);
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> filter.execute(wrapper, JSON.toJSONString(tamperedBody), exchange));

        assertEquals(BaseCode.RSA_SIGN_ERROR.getCode(), exception.getCode());
    }

    @Test
    void missingTokenOnProtectedPathShouldThrowArgumentEmpty() {
        property.setCheckTokenPaths(new String[]{"/user/**"});
        stubChannelData();

        Map<String, Object> body = new HashMap<>();
        body.put(CODE, "2");
        body.put(BUSINESS_BODY, "hello");
        body.put("sign", RsaSignTool.rsaSign256((Map) body, privateKey));

        ServerWebExchange exchange = mockExchange("/user/test", new HttpHeaders());
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        assertThrows(ArgumentException.class, () -> filter.execute(wrapper, JSON.toJSONString(body), exchange));
    }

    @Test
    void userIdPathShouldFetchUserWhenTokenCheckSkipped() {
        property.setUserIdPaths(new String[]{"/user/**"});
        stubChannelData();
        UserVo user = new UserVo();
        user.setId("user-1");
        when(tokenService.getUser("mock-token", "2", SECRET)).thenReturn(user);

        Map<String, Object> body = new HashMap<>();
        body.put(CODE, "2");
        body.put(BUSINESS_BODY, "hello");
        body.put("sign", RsaSignTool.rsaSign256((Map) body, privateKey));

        HttpHeaders headers = new HttpHeaders();
        headers.add(TOKEN, "mock-token");
        ServerWebExchange exchange = mockExchange("/user/test", headers);
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        String result = filter.execute(wrapper, JSON.toJSONString(body), exchange);

        assertEquals("hello", result);
        assertEquals("user-1", wrapper.getMap().get(USER_ID));
        verify(tokenService).getUser("mock-token", "2", SECRET);
    }

    @Test
    void nonJsonBodyWithoutSignatureShouldStillRunApiRestrict() {
        ServerWebExchange exchange = mockExchange("/program/list", new HttpHeaders());
        RequestTemporaryWrapper wrapper = new RequestTemporaryWrapper();

        String result = filter.execute(wrapper, "", exchange);

        assertEquals("", result);
        assertNull(wrapper.getMap().get(REQUEST_BODY));
        verify(apiRestrictService).apiRestrict(null, "/program/list", exchange.getRequest());
    }

    private void stubChannelData() {
        GetChannelDataVo channel = new GetChannelDataVo();
        channel.setCode("2");
        channel.setSignPublicKey(publicKey);
        channel.setTokenSecret(SECRET);
        when(channelDataService.getChannelDataByCode("2")).thenReturn(channel);
    }

    private ServerWebExchange mockExchange(String path, HttpHeaders headers) {
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(exchange.getRequest()).thenReturn(request);
        when(request.getPath()).thenReturn(RequestPath.parse(URI.create(path), ""));
        when(request.getHeaders()).thenReturn(headers);
        return exchange;
    }
}
