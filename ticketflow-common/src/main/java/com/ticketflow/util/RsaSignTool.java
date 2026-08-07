package com.ticketflow.util;

import cn.hutool.crypto.asymmetric.SignAlgorithm;
import com.alibaba.fastjson.JSON;
import com.ticketflow.enums.BaseCode;
import com.ticketflow.exception.TicketFlowFrameException;
import lombok.extern.slf4j.Slf4j;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RSA 签名/验签工具。
 * 用于 RequestValidationFilter 中校验请求签名的合法性。
 * 签名算法：SHA256withRSA，对请求参数按字典序拼接后签名
 */
@Slf4j
public class RsaSignTool {
    
    private final static String SIGN_TYPE = "RSA";
    
    private final static String CHARSET = "utf-8";
    
    
    
    /**
     * 签名
     */
    public static String rsaSign256(Map<String, String> params, String privateKey) {
        String content = buildParam(params);
        return rsaSign256(content,privateKey);       
    }
    
    /**
     * 签名
     */
    public static String rsaSign256(String content, String privateKey) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKey);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(SIGN_TYPE);
            Signature si = Signature.getInstance(SignAlgorithm.SHA256withRSA.getValue());
            si.initSign(keyFactory.generatePrivate(keySpec));
            si.update(content.getBytes(CHARSET));
            byte[] sign = si.sign();
            return Base64.getEncoder().encodeToString(sign);
        } catch (Exception e) {
            log.error("sign256 error",e);
            throw new TicketFlowFrameException(BaseCode.GENERATE_RSA_SIGN_ERROR);
        }
    }
    
    
    /**
     * 构建参数字符串
     * @param params
     * @return
     */
    private static String buildParam(Map<String, String> params) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size() - 1; i++) {
            String key = keys.get(i);
            Object rawValue = params.get(key);
            String value = rawValue == null ? null : String.valueOf(rawValue);
            sb.append(buildKeyValue(key, value, false));
            sb.append("&");
        }
        
        String tailKey = keys.get(keys.size() - 1);
        Object rawTailValue = params.get(tailKey);
        String tailValue = rawTailValue == null ? null : String.valueOf(rawTailValue);
        sb.append(buildKeyValue(tailKey, tailValue, false));
        
        return sb.toString();
    }
    
    /**
     * 验证签名
     * */
    public static boolean verifyRsaSign256(Map<String, String> params, String publicKey){
        try {
            Object rawSign = params.get("sign");
            String sign = rawSign == null ? null : String.valueOf(rawSign);
            String content = getSignCheckContent(params);
            return verifyRsaSign256(content.getBytes(CHARSET), sign, publicKey);
        }catch (Exception e) {
            log.error("verifyRsaSign256 error",e);
            throw new TicketFlowFrameException(BaseCode.RSA_SIGN_ERROR);
        }
    }
    
    public static boolean verifyRsaSign256(byte[] dataBytes, String sign, String publicKey) throws NoSuchAlgorithmException, InvalidKeySpecException, InvalidKeyException, SignatureException {
        
        byte[] signByte = Base64.getDecoder().decode(sign);
        byte[] encodedKey = Base64.getDecoder().decode(publicKey);
        Signature signature = Signature.getInstance(SignAlgorithm.SHA256withRSA.getValue());
        KeyFactory keyFac = KeyFactory.getInstance(SIGN_TYPE);
        PublicKey puk = keyFac.generatePublic(new X509EncodedKeySpec(encodedKey));
        signature.initVerify(puk);
        signature.update(dataBytes);
        return signature.verify(signByte);
        
    }
    
    /**
     * 拼接键值对
     *
     * @param key
     * @param value
     * @param isEncode
     * @return
     */
    private static String buildKeyValue(String key, String value, boolean isEncode) {
        StringBuilder sb = new StringBuilder();
        sb.append(key);
        sb.append("=");
        if (isEncode) {
            try {
                sb.append(URLEncoder.encode(value, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                sb.append(value);
            }
        } else {
            sb.append(value);
        }
        return sb.toString();
    }
    
    /**
     * 获取签名检查的内容
     * @param params
     * @return
     */
    private static String getSignCheckContent(Map<String, String> params) {
        if (params == null) {
            return null;
        }
        params.remove("sign");
        params.remove("files");
        
        return buildParam(params);
    }
    
    /**
     * rsa签名私钥
     * */
    public static String signPrivateKey = "***REMOVED***==";
    /**
     * rsa签名公钥
     * */
    public static String signPublicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAobdxSnIbWAEuBnI29mxDnwFwPr3FUmY01dyWku7KjkhWwxg1dqNZy7j5LXa+Qco1LQPkjgSbsxCpt6lrnlavIxgJfRYi4ntE4lpx663vecz0EXtTxPC76kJ5PNZhQGL9ymVskxW+isuDuglp69sxiNUDOSTQPiZvwIcA8Vl4zdclczYVot33QT5d1moyP4SPllsKrPVZtxiNHtyHzdXA8cnnI6FmySiGdars0ZvhXIn3I0Ggxe9vkB7Z4pNQWjSs25r1ZiRsqb4vV+OdMD51CTb4Tpf7dAlH23UnoobHqNljn/mfaeTUFLkXShYCEkU+ssXfe+OpYql5KcZuDTCGoQIDAQAB";
    
    /**
     * rsa数据加密私钥
     * */
    public static String dataPublicKey = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAirLDI4SPxLXAjk+CMJWrdREnQjJJQgEd7RAw+ZCPZKBFfkoPa5YjcYQzqtc4RPOszBZhPmGr732WLA0O2U0WFnPG6vva9x7pYQot4u5IoncRl7kBb89d1XdR5DZxKovQyDM91CkLikq8h0sBVTkfX2Jz34LmYd8TPQ4BSHUDE5h+f42WkUYG9PCaXvPg+yv4+1AwJeXI/wW181h1JQ5cmogFXIHEFOxS/wwtnoijwmRv/3nKhdyYZbpC2V7F2xq9jWuTBL01Oj3sRhbykHDW2aK2oJ53U5vqlaC6XsheCabMqeqjDPCa8rUjp10pWy7LneYxVigVuONOmlvt56ja7QIDAQAB";
    
    /**
     * rsa数据解密私钥
     * */
    public static String dataPrivateKey = "***REMOVED***=";
    
    
    public static void main(String[] args) {
        //v1加密版本
        parameterTransferV1();
        //v2加密版本
        //parameterTransferV2();
    }
    
    public static void parameterTransferV1() {
        Map<String, String> map = new HashMap<>(8);
        //基础参数
        map.put("code", "0001");
        //业务参数
        map.put("businessBody", "{\"id\":\"1111\",\"sleepTime\":10}");
        //签名
        String sign = RsaSignTool.rsaSign256(map, signPrivateKey);
        System.out.println("签名:" + sign);
        map.put("sign", sign);
        //验签
        boolean result = RsaSignTool.verifyRsaSign256(map, signPublicKey);
        System.out.println("签名结果:" + result);
    }
    
    public static void parameterTransferV2() {
        Map<String, String> map = new HashMap<>(8);
        //基础参数
        map.put("code","0001");
        
        //参数加密后再签名
        Map<String, Object> businessMap = new HashMap<>(8);
        businessMap.put("id","1111");
        businessMap.put("sleepTime",10);
        
        //将业务参数进行加密
        String encrypt = RsaTool.encrypt(JSON.toJSONString(businessMap), dataPublicKey);
        System.out.println("参数加密后:" + encrypt);
        
        String decrypt = RsaTool.decrypt(encrypt, dataPrivateKey);
        System.out.println("参数解密后:" + decrypt);
        
        //将未加密的业务参数和基础参数进行拼接
        map.put("businessBody", JSON.toJSONString(businessMap));
        //rsa生成签名
        String sign = RsaSignTool.rsaSign256(map, signPrivateKey);
        System.out.println("签名:" + sign);
        map.put("sign",sign);
        //rsa进行验签
        boolean result = RsaSignTool.verifyRsaSign256(map, signPublicKey);
        System.out.println("签名结果:" + result);
    }
}
