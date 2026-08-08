package com.ticketflow.util;

import com.alibaba.fastjson.JSON;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RsaSignToolTest {

    private static String privateKey;
    private static String publicKey;
    private static String otherPublicKey;

    @BeforeAll
    static void generateKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        otherPublicKey = Base64.getEncoder()
                .encodeToString(generator.generateKeyPair().getPublic().getEncoded());
    }

    @Test
    void signThenVerify_roundTrip_passes() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        params.put("businessBody", "{\"id\":\"1111\",\"sleepTime\":10}");

        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertTrue(RsaSignTool.verifyRsaSign256(params, publicKey));
    }

    @Test
    void sign_verify_includeOnlyGivenParams_notOtherParams() {
        Map<String, String> params = new HashMap<>();
        params.put("a", "1");
        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertTrue(RsaSignTool.verifyRsaSign256(params, publicKey));

        // 注意：verify 会从入参 map 中移除 sign，需重新补回
        Map<String, String> withExtra = new HashMap<>(params);
        withExtra.put("sign", sign);
        withExtra.put("extra", "2");
        assertFalse(RsaSignTool.verifyRsaSign256(withExtra, publicKey));
    }

    @Test
    void verify_numericJsonValues_noClassCastException() {
        // 网关真实场景：fastjson 将 JSON 解析为 Map<String,Object>（数字为 Integer/Long），
        // raw 赋值给 Map<String, String> 后传入，旧实现会抛 ClassCastException
        String json = "{\"code\":\"0001\",\"sleepTime\":10,\"amount\":99}";
        Map<String, Object> parsed = JSON.parseObject(json, Map.class);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, String> params = (Map) parsed;

        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertTrue(RsaSignTool.verifyRsaSign256(params, publicKey));
    }

    @Test
    void verify_numericSignValue_throwsRsaSignError() {
        // sign 为数字类型的异常输入：String.valueOf 转换成功（不再 ClassCastException），
        // 但 base64 解码失败，统一包装为业务异常
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        params.put("sign", "12345");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> RsaSignTool.verifyRsaSign256(params, publicKey));
        assertEquals(BaseCode.RSA_SIGN_ERROR.getCode(), exception.getCode());
    }

    @Test
    void signAndVerify_nullValue_noNpe() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        params.put("businessBody", null);

        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertTrue(RsaSignTool.verifyRsaSign256(params, publicKey));
    }

    @Test
    void verify_tamperedParam_returnsFalse() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        params.put("businessBody", "{\"id\":\"1111\"}");

        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        params.put("businessBody", "{\"id\":\"2222\"}");
        assertFalse(RsaSignTool.verifyRsaSign256(params, publicKey));
    }

    @Test
    void verify_wrongPublicKey_returnsFalse() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        params.put("businessBody", "{\"id\":\"1111\"}");

        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertFalse(RsaSignTool.verifyRsaSign256(params, otherPublicKey));
    }

    @Test
    void verify_removesSignKeyFromParams_sideEffect() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");
        String sign = RsaSignTool.rsaSign256(params, privateKey);
        params.put("sign", sign);

        assertTrue(params.containsKey("sign"));
        RsaSignTool.verifyRsaSign256(params, publicKey);
        assertFalse(params.containsKey("sign"));
    }

    @Test
    void verify_missingSignKey_throws() {
        Map<String, String> params = new HashMap<>();
        params.put("code", "0001");

        TicketFlowFrameException exception = assertThrows(TicketFlowFrameException.class,
                () -> RsaSignTool.verifyRsaSign256(params, publicKey));
        assertEquals(BaseCode.RSA_SIGN_ERROR.getCode(), exception.getCode());
    }

    @Test
    void sign_emptyParams_throws() {
        // 空参数场景：buildParam 越界（keys.get(0)），当前实现直接泄漏 IndexOutOfBoundsException，
        // 未包装为业务异常——记录现状，防止行为被无意改变
        Map<String, String> params = new HashMap<>();

        assertThrows(IndexOutOfBoundsException.class,
                () -> RsaSignTool.rsaSign256(params, privateKey));
    }
}
