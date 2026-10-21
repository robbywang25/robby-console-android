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
  // Elapsed Claude resets for the 重置日历, and an announced Tibo reset counting down on the Codex tiles.
  org.json.JSONArray history=new org.json.JSONArray();for(int i=1;i<=6;i++)history.put(new JSONObject().put("id","five_hour").put("label","5 小时").put("quotaGroup",JSONObject.NULL).put("model",JSONObject.NULL).put("resetsAt",now.minusSeconds(i*5*3600L).toString()));
  out.getJSONArray("accounts").getJSONObject(2).put("resetHistory",history);
  out.put("tiboReset",tiboPost("2111600000000000013",13,"reset-scheduled","官方预计太平洋时间今晚重置",now.minusSeconds(1800),now.plusSeconds(2*3600+1234)).put("state","scheduled"));
  return out;
 }
 private static JSONObject tiboPost(String id,int day,String kind,String title,java.time.Instant at,java.time.Instant deadline)throws Exception{
  return new JSONObject().put("id",id).put("day",day).put("kind",kind).put("title",title).put("publishedAt",at.toString()).put("deadline",deadline==null?JSONObject.NULL:deadline.toString())
   .put("conditional",false).put("excerpt","合成的帖子摘录，只用于界面预览。").put("sourceUrl","https://x.com/thsottiaux/status/"+id);
 }
 /** Synthetic 28 天挑战 on its thirteenth day; post ids are made up and link nowhere real. */
 private static JSONObject tibo()throws Exception{
  java.time.Instant now=java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
  java.time.LocalDate start=java.time.LocalDate.now(java.time.ZoneId.of("America/Los_Angeles")).minusDays(12);
  java.util.function.IntFunction<java.time.Instant> day=d->start.plusDays(d-1).atTime(10,0).atZone(java.time.ZoneId.of("America/Los_Angeles")).toInstant();
  org.json.JSONArray events=new org.json.JSONArray();
  events.put(tiboPost("2111600000000000013",13,"reset-scheduled","官方预计太平洋时间今晚重置",now.minusSeconds(1800),now.plusSeconds(2*3600+1234)));
  events.put(tiboPost("2111600000000000012",13,"update","Tibo 新动态",now.minusSeconds(5400),null));
  events.put(tiboPost("2111600000000000031",3,"cards-completed","官方确认重置卡已发放",day.apply(3).plusSeconds(7200),null));
  events.put(tiboPost("2111600000000000030",3,"cards-announced","宣布发放一张重置卡",day.apply(3),null));
  events.put(tiboPost("2111600000000000021",2,"reset-completed","官方表示额度重置已完成",day.apply(2).plusSeconds(3600),null));
  events.put(tiboPost("2111600000000000020",2,"improvement","Day 2 产品更新",day.apply(2),null));
  org.json.JSONArray summaries=new org.json.JSONArray();
  for(int d=1;d<=12;d++)summaries.put(new JSONObject().put("day",d).put("preview",d==2?"免费审查 · 已重置":d==3?"云端重新发布 · 发卡":"演示进展 "+d).put("updatedAt",day.apply(d).plusSeconds(3600).toString())
   .put("items",new org.json.JSONArray().put(new JSONObject().put("id","21116000000000001"+String.format(java.util.Locale.ROOT,"%02d",d)).put("title",d==2?"Day 2 重置完成":"Day "+d+" 演示更新").put("summary",d==2?"官方原帖确认这次额度重置已完成。":"合成的每日摘要，只用于界面预览。")
    .put("kind",d==2?"reset-completed":"update").put("publishedAt",day.apply(d).toString()).put("sourceUrl","https://x.com/thsottiaux/status/21116000000000001"+String.format(java.util.Locale.ROOT,"%02d",d)))));
  return new JSONObject().put("schemaVersion",1).put("status","connected").put("checkedAt",now.toString()).put("lastSuccessAt",now.toString()).put("intervalSeconds",300)
   .put("sourceNote","每日摘要自动同步 Codex Radar，并核对 Tibo 原帖作者；重置倒计时仅采用官方明确时间。").put("summaryStatus","connected").put("summaryLastSuccessAt",now.toString())
   .put("challenge",new JSONObject().put("startDate",start.toString()).put("days",28).put("timeZone","America/Los_Angeles").put("currentDay",13).put("promiseUrl",JSONObject.NULL))
   .put("upcoming",tiboPost("2111600000000000013",13,"reset-scheduled","官方预计太平洋时间今晚重置",now.minusSeconds(1800),now.plusSeconds(2*3600+1234)).put("state","scheduled"))
   .put("events",events).put("dailySummaries",summaries);
 }
 /**
  * DeepSeek 模型 in the gateway's shape: the two public models as DeepSeek's own documentation example describes them, the
  * site's published prices, one synthetic model the site has not priced and one priced model the official list dropped.
  */
 private static JSONObject deepseek(String time)throws Exception{
  JSONObject body=new JSONObject("""
   {"object":"list","data":[
    {"id":"deepseek-flash","object":"model","owned_by":"deepseek","name":"DeepSeek-V4.1-Flash","context_window":1048576,"max_output_tokens":393216,"input_modalities":["text","image"],"output_modalities":["text"],"effort":{"supported_levels":["low","high","max"],"default_level":"high"},"api_capabilities":{"anthropic_messages":{"system_prompt_update":"in-history"}}},
    {"id":"deepseek-v4-pro","object":"model","owned_by":"deepseek","name":"DeepSeek-V4-Pro","context_window":1048576,"max_output_tokens":393216,"input_modalities":["text"],"output_modalities":["text"],"effort":{"supported_levels":["low","high","max"],"default_level":"high"},"api_capabilities":{"anthropic_messages":{"system_prompt_update":"leading-only"}}},
    {"id":"preview-new-model","object":"model","owned_by":"deepseek","context_window":131072,"preview_field":"合成预览字段"}]}
   """);
  String policy="{\"name\":\"NAME\",\"currency\":\"USD\",\"unit\":\"1M tokens\",\"input\":IN,\"cachedInput\":HIT,\"output\":OUT,\"context\":1000000,\"maxOutput\":393216,\"vision\":VISION,\"thinking\":true,\"efforts\":[\"low\",\"high\",\"max\"],\"enabled\":true,\"verifiedAt\":\"2026-10-15T06:15:00.000Z\",\"expiresAt\":\"2026-10-22T06:15:00.000Z\",\"source\":\"https://api-docs.deepseek.com/quick_start/pricing/\"}";
  org.json.JSONArray models=new org.json.JSONArray()
   .put(new JSONObject().put("id","deepseek-flash").put("listed",true).put("official",body.getJSONArray("data").get(0)).put("site",new JSONObject().put("available",true).put("reason","可用"))
    .put("policy",new JSONObject(policy.replace("NAME","DeepSeek V4.1 Flash").replace("IN","0.3").replace("HIT","0.006").replace("OUT","1.2").replace("VISION","true"))))
   .put(new JSONObject().put("id","deepseek-v4-pro").put("listed",true).put("official",body.getJSONArray("data").get(1)).put("site",new JSONObject().put("available",true).put("reason","可用"))
    .put("policy",new JSONObject(policy.replace("NAME","DeepSeek V4 Pro").replace("IN","1.32").replace("HIT","0.044").replace("OUT","3.96").replace("VISION","false"))))
   .put(new JSONObject().put("id","preview-new-model").put("listed",true).put("official",body.getJSONArray("data").get(2)).put("site",new JSONObject().put("available",false).put("reason","能力与价格待配置")).put("policy",JSONObject.NULL))
   .put(new JSONObject().put("id","preview-retired-model").put("listed",false).put("official",JSONObject.NULL).put("site",new JSONObject().put("available",false).put("reason","官方列表中不存在"))
    .put("policy",new JSONObject(policy.replace("NAME","合成已下线模型").replace("IN","0.5").replace("HIT","0.01").replace("OUT","2").replace("VISION","false"))));
  return new JSONObject().put("schemaVersion",1).put("site","deepseek.robby.wang").put("checkedAt",time).put("models",models)
   .put("response",new JSONObject().put("endpoint","https://api.deepseek.com/models").put("method","GET").put("status",200).put("fetchedAt",time).put("stale",false).put("refreshFailed",false).put("body",body));
 }
 static JSONObject data(String path)throws Exception{
 String time=java.time.Instant.now().toString();
 if(path.equals("overview"))return new JSONObject("{\"apiVersion\":\"2026-08-01\",\"summary\":{\"servicesOnline\":18,\"servicesKnown\":20,\"servicesTotal\":22,\"infrastructureInstances\":4,\"domainAssets\":12},\"status\":{\"schemaVersion\":2,\"summary\":{},\"sites\":[],\"upstreams\":[]},\"notices\":[{\"title\":\"演示域名即将到期\",\"severity\":\"warning\",\"detail\":\"合成提醒 · 请查看域名资产\"}]} ").put("generatedAt",time).put("status",status(time));
 if(path.equals("travel-ledger/current/projects"))return new JSONObject("{\"schemaVersion\":1,\"projects\":[{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"}]}").put("generatedAt",time);
 if(path.startsWith("travel-ledger/current/snapshot"))return new JSONObject("{\"schemaVersion\":1,\"project\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"},\"summary\":{},\"entries\":[],\"people\":[],\"page\":{\"hasMore\":false}}").put("generatedAt",time);
 if(path.equals("account/status"))return new JSONObject("""
  {"schemaVersion":1,"canWrite":true,"displayName":"演示用户","unavailable":[],
   "security":{"totpBound":true,"passkeyBound":true,"twoFactorRequired":true,"settingsUrl":"https://auth.god.robby.wang/settings/two-factor-authentication","passkeys":[
    {"id":"0123456789abcdef","description":"演示手机","createdAt":"2026-10-09T06:59:12Z","lastUsedAt":"2026-10-19T08:00:00Z","aaguid":null,"attachment":"platform","discoverable":true,"backupEligible":true,"backupState":true,"verified":true,"cloneWarning":false},
    {"id":"fedcba9876543210","description":"演示安全密钥","createdAt":"2026-09-01T02:00:00Z","lastUsedAt":null,"aaguid":null,"attachment":"cross-platform","discoverable":true,"backupEligible":false,"backupState":false,"verified":true,"cloneWarning":false}]},
   "social":{"apple":{"available":true,"bound":true,"boundAt":"2026-10-09T07:00:00Z"},"wechat":{"available":true,"bound":false,"boundAt":null}},
   "email":{"bound":true,"masked":"d***@example.com","verifiedAt":"2026-10-10T03:00:00Z","canSend":true,"purpose":"contact","changesRecoveryEmail":false},
   "phone":{"available":false,"bound":false}}
  """).put("checkedAt",time);
 if(path.equals("account/devices")){
  java.time.Instant now=java.time.Instant.now();org.json.JSONArray list=new org.json.JSONArray();
  String[][] rows={{"android","0","5","true"},{"ios","40","120","false"},{"android","300","2000","false"},{"android","4000","9000","false"},{"ios","9000","20000","false"},{"android","20000","40000","false"},{"android","40000","60000","false"},{"ios","60000","90000","false"}};
  for(int i=0;i<rows.length;i++)list.put(new JSONObject().put("id",String.format("%016x",0xa0+i)).put("client",rows[i][0]).put("lastActiveAt",now.minusSeconds(Long.parseLong(rows[i][1])*60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()).put("signedInAt",now.minusSeconds(Long.parseLong(rows[i][2])*60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()).put("current",Boolean.parseBoolean(rows[i][3])));
  return new JSONObject().put("schemaVersion",1).put("checkedAt",time).put("currentFound",true).put("devices",list);
 }
 if(path.equals("tokens/quotas"))return tokens();
 if(path.equals("tokens/tibo"))return tibo();
 if(path.equals("deepseek/models"))return deepseek(time);
 if(path.equals("market"))return new JSONObject("""
  {"apiVersion":"2026-07-01","sourceStatus":{},"disclaimer":{"text":"完全合成的界面预览数据"},"aiUpstream":[],
   "watchlist":[{"symbol":"BTC","label":"合成币 A","price":82874.36,"currency":"USD","changePct":1.23,"ytdPct":-5.28,"updatedAt":"NOW"},
                {"symbol":"0700.HK","label":"合成港股 B","price":410.4,"currency":"HKD","changePct":-1.77,"ytdPct":12.4,"updatedAt":"NOW"},
                {"symbol":"XAU","label":"合成金价 C","price":640.1,"currency":"CNY/g","ytdPct":-9.11,"updatedAt":"NOW"},
                {"symbol":"USD/CNY","label":"合成汇率 D","price":6.6774,"currency":"CNY","updatedAt":"NOW"}],
   "sectors":[{"symbol":"SH512800","label":"合成板块 银行","price":0.866,"change":-0.011,"changePct":-1.25,"ytdPct":5.22,"updatedAt":"NOW"},
              {"symbol":"SZ159928","label":"合成板块 消费","price":0.664,"change":0,"changePct":0,"ytdPct":-16.27,"updatedAt":"NOW"}],
   "benchmarks":[{"market":"A 股","label":"合成指数 甲","price":3735.37,"change":4.45,"changePct":0.12,"ytdPct":-6.31},
                 {"market":"美股","label":"合成指数 乙","price":7712.57,"change":21.8,"changePct":0.28}]}
  """.replace("NOW",time)).put("generatedAt",time);
 throw new IllegalStateException("此预览不连接真实服务");
 }
}
