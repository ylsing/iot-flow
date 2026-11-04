# 物联卡流量查看（IoT Flow）

把「每次打开运营商网页查流量」变成「打开 App 看一眼」。

针对平台 `xj.mywuliankj.cn`（中国移动物联卡）做的三件套：**安卓 App（免服务器）**、**本地网页服务**、**前端设计稿**。

```
iot-flow-app/       安卓 App：启动自动刷新、下拉/点按钮手动刷新，界面深色单页
iot-flow-service/   Node 本地服务：定时抓取 + HTTP 接口，给手机网页用
iot-flow-ui/        单文件网页设计稿（App 内界面的来源）
```

> 不想自己编译？到 [**Releases**](https://github.com/ylsing/iot-flow/releases) 直接下载 APK 装到手机上即可。

## 一、安卓 App（推荐）

免服务器，手机直接访问平台接口。

```bash
cd iot-flow-app
./build_apk.sh            # 产出 build/iot-flow.apk
adb install -r build/iot-flow.apk
```

- 首次打开会弹出设置：填**卡号** + **手机号后四位**（平台登录验证），只存在手机本地。
- 右上角齿轮可换卡；页面每 5 分钟静默刷新，下拉或点刷新立即可重新取数。
- 显示：剩余/总/已用流量、进度环、卡号、ICCID、运营商、到期日、剩余天数、卡状态、
  当前套餐（支持多套餐叠加）、用量统计（今日/近 7 天/近 30 天/累计）、缴费记录。

打包不依赖 Gradle：`aapt2 → javac → d8 → zipalign → apksigner`（脚本里封装好了）。

> 覆盖升级必须用同一个签名文件 `iot-flow-app/build/debug.keystore`（alias `flow`，口令 `android`），
> 该文件不入库，请自行保管；丢了就只能卸载重装。

## 二、本地网页服务

需要一台常开的电脑（同一局域网）。

```bash
cd iot-flow-service
cp config.example.json config.json    # 首次：填卡号 + 手机号后四位
node server.js                        # 或双击「后台启动.bat」
```

手机浏览器打开 `http://<电脑局域网IP>:8080/`，可"添加到主屏幕"。

| 接口 | 说明 |
| --- | --- |
| `GET /api/flow` | 页面数据（卡号/运营商/总量/已用/剩余/到期/状态/近 N 天） |
| `GET /api/refresh` | 立刻重新抓取 |
| `GET /api/health` | 服务与最近一次抓取状态 |

## 三、设计稿

`iot-flow-ui/index.html` 是单文件、零依赖的页面设计稿，App 的 `assets/index.html` 由它改造而来
（加了 JS 桥、设置面板、错误态）。

## 数据从哪来

全部来自物联卡平台，和官网网页版同一套数据：

| 用途 | 接口 |
| --- | --- |
| 登录取会话 | `POST /app/card/login`（form：`card`、`verify`=手机号后四位） |
| 卡信息 | `GET /app/client/card/get?card=…` → `used`/`free`（MB）、`expirationTime`、`state`、`iccid` |
| 每日用量 | `GET /app/client/card/daily/dosage/get/list?card=…&page=1&size=…` → `[{time:20261103, used:980.13}]` |
| 订单/套餐 | `GET /app/client/order/get/list?page=1&size=…` → 套餐名、流量、价格、生效与到期时间 |

同一有效期内可能有多笔套餐（平台口径「无限叠加」），流量直接累加，所以「当前套餐」按有效期
覆盖今天的订单求和显示。

## 说明

- 卡号与手机尾号只存本机（App 用 SharedPreferences，服务用 `config.json`），不会上传到第三方。
- 平台接口若改版，需同步调整 `fetch_flow.js` / `FlowClient.java` 里的路径与字段。

## 声明 / Disclaimer

本项目是**个人自用的小工具**，与任何运营商、物联卡平台及其代理商无关，非官方产品。

- **数据来源**：所有流量数据均来自第三方平台 `xj.mywuliankj.cn` 的公开网页接口，本项目只做登录与展示，
  不存储、不转售、不篡改任何数据；数据的准确性、时效性由该平台负责。
- **账号凭证**：卡号与手机号后四位只保存在你自己的设备上（App 用 SharedPreferences，服务用本地
  `config.json`，且该文件默认不入库），不会发送给本项目作者或任何第三方。
- **使用风险**：平台接口未来可能改版、限流或关闭，届时本项目可能失效；请自行评估对平台服务条款的影响，
  由此产生的一切后果由使用者自行承担。
- **无担保**：软件按「现状」提供，不附带任何明示或暗示的担保。作者不对因使用本软件造成的
  流量损失、数据错误、账号异常或其它损失负责。
- **许可**：代码以 [MIT License](LICENSE) 开源，可自由使用、修改、再分发（保留版权声明即可）；
  但请勿将其用于商业售卖或冒充电商平台官方渠道。
