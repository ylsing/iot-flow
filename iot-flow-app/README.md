# 物联卡流量 App（安卓，免服务器）

一个纯本地运行的安卓 App：打开就自动查询，手指下拉或点刷新即重新取数。不走自建服务器，手机直接访问运营商平台接口。

## 安装

1. 把 `build/iot-flow.apk`（约 49 KB）发到手机（微信/QQ/数据线都行）。
2. 点安装；若提示“禁止安装未知应用”，在系统设置里给对应来源（文件管理器/浏览器/微信）打开“允许安装应用”。
3. 桌面出现“物联卡流量”，点开即用。

> 用的是调试签名（`build/debug.keystore`，alias `flow`，口令 `android`）。以后要覆盖升级，必须用同一个 keystore 签名，**不要删这个文件**（它不入 git，丢了只能卸载重装）。

## 使用

- **默认卡号为空**：首次打开会自动弹出设置面板，填卡号 + 手机号后四位并点“保存并刷新”即可。卡号和尾号只存在手机本地。
- **刷新**：页面下拉、点右上角刷新图标，或按返回键退出后再进（每次进入都会自动刷新）。
- **换卡**：右上角齿轮 → 填卡号 + 手机号后四位 → “保存并刷新”。卡号和尾号存在手机本地（SharedPreferences），不会上传到别处。
- **命令行/扫码带参打开**（可选）：`adb shell am start -n cn.mywulian.flow/.MainActivity --es card 90154379 --es verify 1299`，会把参数写进本地设置后直接刷新。
- 页面每 5 分钟还会静默刷新一次（App 在前台时）。

## 显示内容

- 剩余流量（大号数字，按 GB/TB 自适应）、总流量、已用流量、剩余百分比进度条
- 卡号 / 运营商（按 ICCID 识别移动·联通·电信）/ ICCID
- 到期时间、剩余可用天数、卡状态（正常/停机/已注销）
- **当前套餐**：套餐名/系列、总量、已用、已使用天数、生效→到期；同一有效期有多个套餐时自动叠加（如 108G + 3G保号 = 111 GB）
- **用量统计**：今日 / 近 7 天 / 近 30 天 / 累计
- **缴费记录**：最近几笔订单（日期、套餐名、金额、生效中/已到期）

## 数据从哪来

全部来自物联卡平台 `http://xj.mywuliankj.cn` 的三个接口（和网页版是同一套数据）：

| 用途 | 接口 |
| --- | --- |
| 登录取会话 | `POST /app/card/login`（form：`card`、`verify`=手机号后四位） |
| 卡信息 | `GET /app/client/card/get?card=…` → `used`（已用 MB）、`free`（剩余 MB）、`expirationTime`、`state`、`iccid` |
| 每日用量 | `GET /app/client/card/daily/dosage/get/list?card=…&page=1&size=100` → `[{time:20261103, used:980.13}]`（单位 MB） |
| 订单/套餐 | `GET /app/client/order/get/list?page=1&size=12` → `setMealName`（套餐名）、`flowReal`（总量 MB）、`paymentAmount`（金额）、`effectiveTime`/`expirationTime`（有效期） |

**关于每日用量**：平台只返回“有流量的日子”，App 取最近 45 天记录，缺的日子里补 0 后再汇总出今日/近 7 天/近 30 天。

**关于当前套餐**：平台同一有效期内可叠加多笔套餐（口径叫“无限叠加”），卡上的总流量就是各套餐之和；
App 取有效期覆盖今天的全部订单求和显示，并列出每笔构成的标签。

## 工程结构与编译

```
app/src/main/AndroidManifest.xml          包名 cn.mywulian.flow，允许明文 http
app/src/main/java/cn/mywulian/flow/
  MainActivity.java                       WebView 壳 + JS 桥（pref/setPref/refresh/appVersion）
  FlowClient.java                         纯 Java 抓取+解析（org.json + HttpURLConnection），无 Android 依赖
app/src/main/assets/index.html            界面（单文件，深色主题）
app/src/main/res/mipmap-*/ic_launcher.png 图标
build_apk.sh / build_pack.py              手工打包链（无 Gradle）
build/debug.keystore                      签名文件（不入库，务必保管）
```

编译（本机已装 SDK，无 Gradle）：

```bash
cd iot-flow-app
bash build_apk.sh          # 产出 build/iot-flow.apk
```

链路：`aapt2 compile/link` → `javac(Android API 34)` → `d8` → `zipalign` → `apksigner`（debug keystore）。

`FlowClient` 不依赖 Android，可单独在桌面 JVM 上回归真实接口：

```bash
javac -cp json.jar -d out app/src/main/java/cn/mywulian/flow/FlowClient.java FlowTest.java
java  -cp "out;json.jar" FlowTest 90154379 1299
```

## adb 安装（小米/HyperOS 用户）

若 `adb install` 报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，是系统开关没开，不是 APK 问题：
设置 → 更多设置 → 开发者选项 → 打开「USB 安装」（灰的话需登录小米账号 + 插 SIM 卡）。
也可以直接把 APK 传到手机，用文件管理器点开安装。

## 已知限制

- 平台的“登录需要验证”是硬门槛：卡号 + 手机号后四位缺一不可；后四位改了要重新在设置里填。
- 接口是 http（平台未上 https），App 已开 `usesCleartextTraffic`。
- 到期时间显示到日；剩余天数为平台 `expirationTime` 与当前时间之差。
- 只能在手机有网时用；断网会提示“网络不通”。
