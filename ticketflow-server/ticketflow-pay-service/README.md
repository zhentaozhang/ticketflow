# ticketflow-pay-service

支付服务：统一支付 / 回调通知 / 退款 / 交易对账。策略模式封装支付宝（默认）与微信支付 Native 扫码。

## 本地启动

### 前置依赖

| 组件 | 地址 | 作用 |
|------|------|------|
| MySQL 5.7+ | `127.0.0.1:3306` | 分片库 `ticketflow_pay_0/1`，DDL 见仓库根 `sql/cloud/ticketflow_pay_0.sql`、`ticketflow_pay_1.sql` |
| Nacos | `127.0.0.1:8848` | 服务注册发现（`spring.cloud.nacos.discovery`） |
| Redis | `127.0.0.1:6379` | 框架依赖（当前服务代码未直接使用） |

数据源与分片规则由 `src/main/resources/shardingsphere-pay-<profile>.yaml` 控制，
该文件被 gitignore（本地配置不入库），首次 clone 需自行创建（`shardingsphere-pay-local.yaml`，参考其他服务的同文件或
`classpath` 提示）。连接默认 `root/root`，无密码校验，请按本机修改。

### 环境变量

所有支付密钥通过环境变量注入，**不提交到仓库**。缺配置时服务可启动，但支付请求会失败。

| 变量 | 默认值 | 必需 | 说明 |
|------|--------|------|------|
| `JASYPT_ENCRYPTOR_PASSWORD` | 无 | 是（项目全局） | jasypt 解密口令 |
| `ALIPAY_APP_ID` | 无 | 联调支付时 | 支付宝开放平台应用 ID |
| `ALIPAY_SELLER_ID` | 无 | 联调支付时 | 商户 PID |
| `ALIPAY_MERCHANT_PRIVATE_KEY` | 无 | 联调支付时 | PKCS8 格式 RSA2 商户私钥 |
| `ALIPAY_PUBLIC_KEY` | 无 | 联调支付时 | 支付宝公钥 |
| `ALIPAY_CONTENT_KEY` | 无 | 联调支付时 | 接口内容加密密钥 |
| `WXPAY_ENABLED` | `false` | 否 | `true` 时注册微信支付 Bean（构造即联网拉取平台证书） |
| `WXPAY_APP_ID` | 空 | wxpay.enabled=true 时段 | 微信支付 AppID |
| `WXPAY_MCH_ID` | 空 | wxpay.enabled=true 时段 | 微信支付商户号 |
| `WXPAY_MERCHANT_SERIAL_NO` | 空 | wxpay.enabled=true 时段 | 商户 API 证书序列号 |
| `WXPAY_MERCHANT_PRIVATE_KEY_PATH` | 空 | wxpay.enabled=true 时段 | apiclient_key.pem 路径 |
| `WXPAY_API_V3_KEY` | 空 | wxpay.enabled=true 时段 | APIv3 密钥 |

> 说明：
> - `wxpay.*` 占位符带空默认值（`${WXPAY_APP_ID:}`）：`WXPAY_ENABLED=false` 时零配置启动，配置值绑定为空串而非占位符字面量。
> - `alipay.*` 占位符**不设空默认值**：`DefaultAlipayClient` 构造对空私钥会抛异常，当前设计依赖完整的支付宝配置（缺项时服务可启动、调用时报签名/参数错误）。
> - 网关不路由 `/pay/**`，需在部署层（nginx/端口）保证 pay-service 仅服务间 Feign 可达。

## 接口一览（Feign 内部调用，不直接暴露前端）

| 路径 | 说明 |
|------|------|
| `POST /pay/common/pay` | 统一下单，返回支付链接 / code_url |
| `POST /pay/notify` | 支付渠道异步回调（feign wrapper，支付宝/微信按 channel 分发） |
| `POST /pay/trade/check` | 交易状态查询，以渠道为准同步账单 |
| `POST /pay/refund` | 退款（校验账单已支付 + 金额不超支付额） |
| `POST /pay/detail` | 账单详情 |

## 微信支付联调

1. 配置微信商户 `WXPAY_*` 全部环境变量，`WXPAY_ENABLED=true`，重启。
2. 微信支付后台配置回调地址：`http://<网关或order服务域名>/ticketflow/order/order/wx/notify`（对应 `OrderProperties.wxPayNotifyUrl`）。
3. 前端对 `code_url` 渲染二维码；用户扫码支付成功后微信回调 → order-service `wxNotify` → pay-service 验签/解密/幂等。
4. 回调应答：微信要求明文 `SUCCESS`/`FAIL`（`Constant.WX_NOTIFY_SUCCESS_RESULT`），支付宝为 `success`/`failure`。

## 测试

```bash
mvn -pl ticketflow-server/ticketflow-pay-service -am test
```

覆盖：`WxPayStrategyHandlerTest`（验签/重放防护/数据校验）、`AlipayStrategyHandlerTest`、
`PayServiceTest`（commonPay/notify/tradeCheck/refund/detail 状态机与边界）。