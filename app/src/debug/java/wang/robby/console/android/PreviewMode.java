package wang.robby.console.android;
import android.content.Intent;
import org.json.JSONObject;
/** Debug-only isolated visual fixture. No requests and no real credentials. */
final class PreviewMode {
 private static boolean signedOut, realUpdate;
 static boolean isBuild(){return true;}
 static boolean loggedIn(){return !signedOut;}
 static void configure(android.app.Activity activity,Intent intent){signedOut=intent.getBooleanExtra("preview_signed_out",false);realUpdate=intent.getBooleanExtra("preview_real_update",false);}
 /**
  * Opt-in device check of the real GitHub path: the preview pretends to be 0.3.0, so it finds and downloads the
  * newest release and verifies its SHA-256; the package check then stops it, because the preview is another package.
  */
 static String realUpdateAs(){return realUpdate?"0.3.0":null;}
 /** Synthetic newer release so the update card can be checked without contacting GitHub. */
 static AppUpdate.Release update(){return new AppUpdate.Release("9.9.9","界面预览更新","合成的新版本，只用于检查更新卡片和系统安装流程。","2026-10-16","robby-console-android-9.9.9.apk","",245760,"");}
 /** Synthetic 运行状态 round covering every label the page can show; example.com hosts only. */
 private static JSONObject status(String time)throws Exception{return new JSONObject("""
  {"schemaVersion":2,"generatedAt":"NOW","cacheSeconds":60,"cacheHit":true,"partial":false,
   "sourceStatus":{"sites":{"available":true,"stale":false,"legacyContract":false,"staleAgeSeconds":0,"fetchedAt":"NOW"},"upstreams":[]},
   "summary":{"publicOnline":3,"publicKnown":4,"publicTotal":4,"privateOnline":3,"privateKnown":4,"privateTotal":5,"upstreamOperational":1,"upstreamTotal":2},
   "sites":[
    {"hostname":"home.example.com","label":"演示主页","surface":"public_page","expectation":"public_reachable","tracker":"active","online":true,"reachable":true,"status":200,"latencyMs":182,"dns":{"resolved":true,"recordType":"address"}},
    {"hostname":"blog.example.com","label":"演示博客","surface":"public_page","expectation":"public_reachable","tracker":"active","online":true,"reachable":true,"status":200,"latencyMs":246,"dns":{"resolved":true,"recordType":"cname"}},
    {"hostname":"lab.example.com","label":"演示实验站","surface":"public_page","expectation":"public_reachable","tracker":"active","online":false,"reachable":false,"status":null,"latencyMs":null,"dns":{"resolved":false,"recordType":null}},
    {"hostname":"share.example.com","label":"演示分享页","surface":"public_page","expectation":"public_reachable","tracker":"excluded","online":true,"reachable":true,"status":301,"latencyMs":97,"dns":{"resolved":true,"recordType":"address"}},
    {"hostname":"admin.example.com","label":"演示管理端","surface":"private_entry","expectation":"auth_gateway","tracker":"excluded","online":true,"reachable":true,"status":302,"latencyMs":88,"dns":{"resolved":true,"recordType":"address"}},
    {"hostname":"code.example.com","label":"演示源码","surface":"private_entry","expectation":"auth_gateway","tracker":"excluded","online":false,"reachable":true,"status":200,"latencyMs":131,"dns":{"resolved":true,"recordType":"address"}},
    {"hostname":"stats.example.com","label":"演示统计","surface":"private_entry","expectation":"service_reachable","tracker":"excluded","online":true,"reachable":true,"status":200,"latencyMs":154,"dns":{"resolved":true,"recordType":"cname"}},
    {"hostname":"img.example.com","label":"演示图床","surface":"service","expectation":"service_reachable","tracker":"excluded","online":true,"reachable":true,"status":404,"latencyMs":61,"dns":{"resolved":true,"recordType":"address"}},
    {"hostname":"id.example.com","label":"演示身份门户","surface":"service","expectation":"service_reachable","tracker":"excluded","online":null,"reachable":null,"status":null,"latencyMs":null,"dns":{"resolved":null,"recordType":null}}
   ],
   "upstreams":[
    {"id":"openai-status","name":"OpenAI","available":true,"stale":false,"state":"operational","indicator":"none","updatedAt":"NOW","components":[{"name":"Codex in ChatGPT Desktop","status":"operational"},{"name":"Responses","status":"operational"}]},
    {"id":"github-status","name":"GitHub","available":true,"stale":false,"state":"degraded","indicator":"minor","updatedAt":"NOW","components":[{"name":"Git Operations","status":"operational"},{"name":"API Requests","status":"degraded_performance"},{"name":"Actions","status":"partial_outage"}]}
   ]}
  """.replace("NOW",time));}
 /** Synthetic Token 额度 snapshot in the server's fixed account order; example addresses only. */
 private static JSONObject tokens()throws Exception{
  java.time.Instant now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  java.util.function.LongFunction<String> at=h->now.plusSeconds(h*3600).toString();
  String json="""
  {"schemaVersion":1,"generatedAt":"NOW","staleAfterSeconds":120,"refreshAfterSeconds":30,"accounts":[
   {"id":"codex-1","provider":"OpenAI","product":"codex","label":"ChatGPT Codex","profile":{"name":null,"email":"demo-one@example.com"},"subscription":{"planName":"ChatGPT Pro 500","planMultiplier":25},"monthlyCost":{"basis":"official-price","amountMinor":50000,"currency":"USD","observedAt":null},"status":"connected","reason":"connected","checkedAt":"NOW","lastSuccessAt":"NOW","sourceCollectedAt":"NOW","source":"演示采集器",
    "windows":[{"id":"primary","label":"5 小时窗口","model":null,"quotaGroup":"default","remainingPercent":72,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R3","startsAt":"S2","unlimited":false},
               {"id":"weekly","label":"每周额度","model":null,"quotaGroup":"default","remainingPercent":64,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R12","startsAt":"S144","unlimited":false}],
    "usageRoute":null,"credits":{"remaining":120,"unlimited":false},"resetCredits":{"available":2,"applicable":1,"detailsAt":null,"cards":[{"id":"card-preview-1","status":"available","expiresAt":"R48","grantedAt":"S144"}]},"usageTrend":null},
   {"id":"codex-2","provider":"OpenAI","product":"codex","label":"ChatGPT Codex","profile":{"name":null,"email":"demo-two@example.com"},"subscription":{"planName":"Plus","planMultiplier":null},"monthlyCost":null,"status":"stale","reason":"stale","checkedAt":"NOW","lastSuccessAt":"S3","sourceCollectedAt":"S3","source":"演示采集器",
    "windows":[{"id":"weekly","label":"每周额度","model":null,"quotaGroup":"default","remainingPercent":18,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R48","startsAt":"S120","unlimited":false}],"usageRoute":null,"credits":null,"resetCredits":null,"usageTrend":null},
   {"id":"claude-1","provider":"Anthropic","product":"claude","label":"Claude","profile":{"name":"演示账号","email":null},"subscription":{"planName":"Claude Max","planMultiplier":5},"monthlyCost":{"basis":"receipt","amountMinor":2165,"currency":"USD","observedAt":null},"status":"connected","reason":"connected","checkedAt":"NOW","lastSuccessAt":"NOW","sourceCollectedAt":"NOW","source":"演示采集器",
    "windows":[{"id":"five_hour","label":"5 小时","model":null,"quotaGroup":"default","remainingPercent":72,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R3","startsAt":"S2","unlimited":false},
               {"id":"seven_day","label":"每周","model":null,"quotaGroup":null,"remainingPercent":30,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R48","startsAt":"S120","unlimited":false}],"usageRoute":null,"credits":null,"resetCredits":null,"usageTrend":null},
   {"id":"claude-2","provider":"Anthropic","product":"claude","label":"Claude","profile":null,"subscription":null,"monthlyCost":null,"status":"not_configured","reason":"not_configured","checkedAt":null,"lastSuccessAt":null,"sourceCollectedAt":null,"source":"未配置","windows":[],"usageRoute":null,"credits":null,"resetCredits":null,"usageTrend":null},
   {"id":"grok","provider":"xAI","product":"grok","label":"Grok Build","profile":null,"subscription":null,"monthlyCost":null,"status":"needs_auth","reason":"needs_auth","checkedAt":"NOW","lastSuccessAt":null,"sourceCollectedAt":null,"source":"演示采集器","windows":[],"usageRoute":null,"credits":null,"resetCredits":null,"usageTrend":null}
  ],"additionalAccounts":[
   {"id":"today-cn","provider":"TodayAI","product":"today-cn","label":"TodayAI 中国版","profile":{"name":"手机尾号 0000","email":null},"subscription":{"planName":"TodayAI Pro"},"monthlyCost":null,"status":"connected","reason":"connected","checkedAt":"NOW","lastSuccessAt":"NOW","sourceCollectedAt":"NOW","source":"演示采集器",
    "windows":[{"id":"usage","label":"额度","model":null,"quotaGroup":"default","remainingPercent":36,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R48","startsAt":"S120","unlimited":false}],"usageRoute":null,"credits":null,"resetCredits":{"available":0,"applicable":null,"detailsAt":"NOW","cards":[]},"usageTrend":null},
   {"id":"today-global","provider":"TodayAI","product":"today-global","label":"TodayAI 全球版","profile":{"name":null,"email":"demo-today@example.com"},"subscription":{"planName":"TodayAI Pro"},"monthlyCost":null,"status":"connected","reason":"connected","checkedAt":"NOW","lastSuccessAt":"NOW","sourceCollectedAt":"NOW","source":"演示采集器",
    "windows":[{"id":"usage","label":"额度","model":null,"quotaGroup":"default","remainingPercent":76,"remaining":null,"limit":null,"unit":"percent","resetsAt":"R48","startsAt":"S144","unlimited":false}],"usageRoute":null,"credits":null,"resetCredits":{"available":0,"applicable":null,"detailsAt":"NOW","cards":[]},"usageTrend":null}
  ]}""";
  JSONObject out=new JSONObject(json.replace("NOW",now.toString()).replace("R12",at.apply(12)).replace("R48",at.apply(48)).replace("R3",at.apply(3)).replace("R1",at.apply(1))
   .replace("S144",at.apply(-144)).replace("S120",at.apply(-120)).replace("S2",at.apply(-2)).replace("S3",at.apply(-3)).replace("S4",at.apply(-4)));
  // The first Codex account's last hour: a gentle decline, five-minute samples, as the collector reports it.
  org.json.JSONArray points=new org.json.JSONArray();for(int i=0;i<=12;i++)points.put(new JSONObject().put("at",now.minusSeconds((12-i)*300L).toString()).put("remaining",76-i*0.35-(i>8?1.2:0)));
  out.getJSONArray("accounts").getJSONObject(0).put("usageTrend",new JSONObject().put("status","ready").put("metric","quota").put("from",now.minusSeconds(3600).toString()).put("to",now.toString()).put("observedAt",now.toString()).put("balancePoints",points));
  return out;
 }
 static JSONObject data(String path)throws Exception{
 String time=java.time.Instant.now().toString();
 if(path.equals("overview"))return new JSONObject("{\"apiVersion\":\"2026-08-01\",\"summary\":{\"servicesOnline\":18,\"servicesKnown\":20,\"servicesTotal\":22,\"infrastructureInstances\":4,\"domainAssets\":12},\"status\":{\"schemaVersion\":2,\"summary\":{},\"sites\":[],\"upstreams\":[]},\"notices\":[{\"title\":\"演示域名即将到期\",\"severity\":\"warning\",\"detail\":\"合成提醒 · 请查看域名资产\"}]} ").put("generatedAt",time).put("status",status(time));
 if(path.equals("travel-ledger/current/projects"))return new JSONObject("{\"schemaVersion\":1,\"projects\":[{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"}]}").put("generatedAt",time);
 if(path.startsWith("travel-ledger/current/snapshot"))return new JSONObject("{\"schemaVersion\":1,\"project\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"},\"summary\":{},\"entries\":[],\"people\":[],\"page\":{\"hasMore\":false}}").put("generatedAt",time);
 if(path.equals("tokens/quotas"))return tokens();
 if(path.equals("market"))return new JSONObject("{\"apiVersion\":\"2026-07-01\",\"sourceStatus\":{},\"watchlist\":[{\"label\":\"合成行情 A\",\"price\":123.45,\"currency\":\"USD\",\"changePct\":1.23}],\"sectors\":[],\"benchmarks\":[],\"aiUpstream\":[],\"disclaimer\":{\"text\":\"完全合成的界面预览数据\"}}").put("generatedAt",time);
 throw new IllegalStateException("此预览不连接真实服务");
 }
}
