package cn.mywulian.flow;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 纯 Java 抓取 + 解析（不依赖任何 Android API，可在桌面 JVM 上直接回归测试）。
 * 接口：
 *   POST /app/card/login                                  form: card, verify（手机号后四位）
 *   GET  /app/client/card/get?card=…                      used / free / expirationTime / state / iccid / payNumber
 *   GET  /app/client/card/daily/dosage/get/list?card=…    日用量 [{time:20261103, used:980.13}]（MB）
 *   GET  /app/client/order/get/list?page=&size=           缴费/订购记录（套餐名、金额、生效时间）
 */
public final class FlowClient {

    public static final String BASE = "http://xj.mywuliankj.cn";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 日用量一次取多少天（要覆盖“近 30 天”统计 + 今天） */
    private static final int DAILY_FETCH_DAYS = 45;
    /** 缴费记录取最近几条 */
    private static final int ORDER_FETCH = 12;

    private FlowClient() {
    }

    /** 返回前端契约 JSON；失败时返回 {"error": "..."}。 */
    public static String fetchJson(String card, String verify, int days) {
        try {
            return build(card, verify, days).toString();
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = t.getClass().getSimpleName();
            try {
                JSONObject err = new JSONObject();
                err.put("error", msg);
                return err.toString();
            } catch (Exception e) {
                return "{\"error\":\"unknown\"}";
            }
        }
    }

    private static JSONObject build(String card, String verify, int days) throws Exception {
        Map<String, String> cookies = new HashMap<String, String>();

        JSONObject login = new JSONObject(http("POST", "/app/card/login",
                "card=" + enc(card) + "&verify=" + enc(verify), cookies));
        int lc = login.optInt("code", -1);
        if (lc != 0) {
            JSONObject err = new JSONObject();
            err.put("error", login.optString("msg", "登录失败（code=" + lc + "）"));
            err.put("code", lc);
            return err;
        }

        JSONObject cardResp = new JSONObject(http("GET", "/app/client/card/get?card=" + enc(card), null, cookies));
        if (cardResp.optInt("code", -1) != 0) {
            throw new Exception(cardResp.optString("msg", "卡信息接口失败"));
        }
        JSONObject c = cardResp.getJSONObject("data");

        List<JSONObject> daily = new ArrayList<JSONObject>();
        try {
            JSONObject d = new JSONObject(http("GET",
                    "/app/client/card/daily/dosage/get/list?card=" + enc(card) + "&page=1&size=" + DAILY_FETCH_DAYS,
                    null, cookies));
            if (d.optInt("code", -1) == 0) {
                JSONObject data = d.optJSONObject("data");
                JSONArray list = data == null ? null : data.optJSONArray("list");
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject it = list.optJSONObject(i);
                    if (it != null) daily.add(it);
                }
            }
        } catch (Exception ignore) {
            // 日用量拿不到不影响主数据
        }

        double usedMB = c.optDouble("used", 0);
        boolean hasFree = c.has("free") && !c.isNull("free");
        double freeMB = hasFree ? c.optDouble("free", 0) : -1;
        String iccid = c.optString("iccid", "");
        String expireDate = normDate(c.optString("expirationTime", ""));

        JSONObject out = new JSONObject();
        out.put("cardNo", c.optString("card", card));
        out.put("iccid", iccid);
        out.put("phone", c.optString("phone", ""));
        out.put("operator", operatorFromIccid(iccid));
        out.put("totalMB", freeMB >= 0 ? (Object) (usedMB + freeMB) : JSONObject.NULL);
        out.put("usedMB", usedMB);
        out.put("leftMB", freeMB >= 0 ? (Object) freeMB : JSONObject.NULL);
        out.put("expireDate", expireDate);
        out.put("status", normState(c.optInt("state", 0)));
        out.put("dailyMB", padDaily(daily, days));
        out.put("stats", buildStats(daily));
        out.put("orders", fetchOrders(cookies, out));
        out.put("payCount", c.optInt("payNumber", 0));
        out.put("updatedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new java.util.Date()));
        return out;
    }

    /**
     * 今日 / 近 7 天 / 近 30 天用量（MB）。平台只回有流量的日子，缺的按 0 计。
     */
    static JSONObject buildStats(List<JSONObject> records) throws Exception {
        Map<String, Double> byDate = byDate(records);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar cal = Calendar.getInstance();

        double today = 0, d7 = 0, d30 = 0;
        for (int i = 0; i < 30; i++) {
            Calendar c2 = (Calendar) cal.clone();
            c2.add(Calendar.DAY_OF_MONTH, -i);
            String key = fmt.format(c2.getTime());
            Double v = byDate.get(key);
            double mb = v == null ? 0 : v.doubleValue();
            if (i == 0) today = mb;
            if (i < 7) d7 += mb;
            d30 += mb;
        }

        JSONObject s = new JSONObject();
        s.put("todayMB", round2(today));
        s.put("d7MB", round2(d7));
        s.put("d30MB", round2(d30));
        return s;
    }

    /** 最近几条缴费记录；顺带把本期套餐信息（名称/总量/生效日）填进 out.plan。 */
    static JSONArray fetchOrders(Map<String, String> cookies, JSONObject out) {
        JSONArray arr = new JSONArray();
        try {
            JSONObject r = new JSONObject(http("GET",
                    "/app/client/order/get/list?page=1&size=" + ORDER_FETCH, null, cookies));
            if (r.optInt("code", -1) != 0) return arr;
            JSONObject data = r.optJSONObject("data");
            JSONArray list = data == null ? null : data.optJSONArray("list");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null) continue;
                JSONObject item = new JSONObject();
                item.put("date", normDate(o.optString("createTime", "")));
                item.put("name", o.optString("setMealName", ""));
                double amount = o.optDouble("paymentAmount", o.optDouble("amount", 0));
                item.put("amount", new java.math.BigDecimal(Double.toString(amount))
                        .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
                String oend = normDate(o.optString("expirationTime", ""));
                String otoday = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
                boolean stillValid = oend.isEmpty() ? (o.optInt("effectiveState", 0) == 1) : (oend.compareTo(otoday) >= 0);
                item.put("active", stillValid);
                item.put("end", oend);
                arr.put(item);
            }

            // 当前套餐可能由多笔“无限叠加”组成：把有效期覆盖今天的订单一并计入
            if (listCount(list)) {
                String today = new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date());
                JSONObject plan = new JSONObject();
                JSONArray combo = new JSONArray();
                double sumMB = 0;
                String start = null, end = null, series = "";
                for (int i = 0; i < list.length(); i++) {
                    JSONObject o = list.optJSONObject(i);
                    if (o == null) continue;
                    String eff = normDate(o.optString("effectiveTime", ""));
                    String exp = normDate(o.optString("expirationTime", ""));
                    if (eff.isEmpty() || exp.isEmpty()) continue;
                    if (exp.compareTo(today) < 0) continue;   // 已过期
                    if (eff.compareTo(today) > 0) continue;   // 尚未生效
                    double mb = o.optDouble("flowReal", 0);
                    JSONObject it = new JSONObject();
                    it.put("name", o.optString("setMealName", ""));
                    it.put("totalMB", mb);
                    it.put("start", eff);
                    it.put("end", exp);
                    combo.put(it);
                    sumMB += mb;
                    if (start == null || eff.compareTo(start) < 0) start = eff;
                    if (end == null || exp.compareTo(end) > 0) end = exp;
                    if (series.isEmpty()) {
                        JSONObject cObj = o.optJSONObject("card");
                        JSONObject se = cObj == null ? null : cObj.optJSONObject("series");
                        if (se != null) series = se.optString("showName", "");
                    }
                }
                plan.put("combo", combo);
                plan.put("count", combo.length());
                plan.put("totalMB", sumMB > 0 ? (Object) sumMB : (Object) JSONObject.NULL);
                plan.put("start", start == null ? "" : start);
                plan.put("end", end == null ? "" : end);
                plan.put("series", series);
                plan.put("opened", combo.length() > 0);
                out.put("plan", plan);
            } else {
                out.put("plan", new JSONObject());
            }
        } catch (Exception ignore) {
            try {
                out.put("plan", new JSONObject());
            } catch (Exception ignore2) {
            }
        }
        return arr;
    }

    private static boolean listCount(JSONArray list) {
        return list != null && list.length() > 0;
    }

    // ---------------- HTTP ----------------

    private static String enc(String v) {
        try {
            return URLEncoder.encode(v == null ? "" : v, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String http(String method, String path, String form, Map<String, String> cookies) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        if (!cookies.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : cookies.entrySet()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            c.setRequestProperty("Cookie", sb.toString());
        }
        if (form != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            OutputStream os = c.getOutputStream();
            os.write(form.getBytes("UTF-8"));
            os.flush();
            os.close();
        }

        int code = c.getResponseCode();

        Map<String, List<String>> hf = c.getHeaderFields();
        if (hf != null) {
            for (Map.Entry<String, List<String>> e : hf.entrySet()) {
                if (e.getKey() == null || !"Set-Cookie".equalsIgnoreCase(e.getKey())) continue;
                for (String sc : e.getValue()) {
                    int semi = sc.indexOf(';');
                    String kv = semi > 0 ? sc.substring(0, semi) : sc;
                    int eq = kv.indexOf('=');
                    if (eq > 0) cookies.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }

        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
        }
        c.disconnect();
        String body = new String(bos.toByteArray(), "UTF-8");
        if (code >= 400) {
            String msg = null;
            try {
                JSONObject o = new JSONObject(body);
                msg = o.optString("msg", null);
            } catch (Exception ignore) {
            }
            throw new Exception(msg != null && !msg.isEmpty() ? msg : ("平台返回 HTTP " + code));
        }
        return body;
    }

    // ---------------- 解析工具 ----------------

    static String operatorFromIccid(String iccid) {
        String s = iccid == null ? "" : iccid;
        if (s.matches("^89860[024567].*")) return "中国移动";
        if (s.matches("^89860[135].*")) return "中国联通";
        if (s.matches("^89861[01].*")) return "中国电信";
        return "物联卡";
    }

    static String normState(int state) {
        if (state == 0) return "active";
        if (state == 1 || state == 2) return "suspended";
        if (state == 3 || state == 4) return "cancelled";
        return "active";
    }

    static String normDate(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (s.isEmpty()) return "";
        int sp = s.indexOf(' ');
        if (sp > 0) s = s.substring(0, sp);
        if (s.matches("\\d{8}")) {
            return s.substring(0, 4) + "-" + s.substring(4, 6) + "-" + s.substring(6, 8);
        }
        if (s.matches("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")) {
            String[] p = s.split("[-/.]");
            return p[0] + "-" + pad2(p[1]) + "-" + pad2(p[2]);
        }
        return s;
    }

    private static String pad2(String v) {
        return v.length() == 1 ? "0" + v : v;
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    /** 日用量记录 -> {日期: MB} */
    private static Map<String, Double> byDate(List<JSONObject> records) {
        Map<String, Double> map = new HashMap<String, Double>();
        for (JSONObject it : records) {
            JSONObject rec = it.optJSONObject("data") != null ? it.optJSONObject("data") : it;
            String key = normDate(String.valueOf(rec.opt("time")));
            Object used = rec.opt("used");
            if (key.isEmpty() || used == null || used == JSONObject.NULL) continue;
            try {
                map.put(key, Double.valueOf(String.valueOf(used)));
            } catch (NumberFormatException ignore) {
            }
        }
        return map;
    }

    /** 平台只回有流量的日子，这里补满最近 N 个自然日，缺失补 0。 */
    static JSONArray padDaily(List<JSONObject> records, int days) throws Exception {
        Map<String, Double> map = byDate(records);
        JSONArray arr = new JSONArray();
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        cal.add(Calendar.DAY_OF_MONTH, -(days - 1));
        for (int i = 0; i < days; i++) {
            String key = fmt.format(cal.getTime());
            JSONObject o = new JSONObject();
            o.put("date", key);
            o.put("mb", map.containsKey(key) ? map.get(key) : (Object) Double.valueOf(0));
            arr.put(o);
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
        return arr;
    }
}
