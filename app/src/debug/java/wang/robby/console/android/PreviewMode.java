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
 static JSONObject data(String path)throws Exception{
 String time=java.time.Instant.now().toString();
 if(path.equals("overview"))return new JSONObject("{\"apiVersion\":\"2026-08-01\",\"summary\":{\"servicesOnline\":18,\"servicesKnown\":20,\"servicesTotal\":22,\"infrastructureInstances\":4,\"domainAssets\":12},\"status\":{\"schemaVersion\":2,\"summary\":{},\"sites\":[],\"upstreams\":[]},\"notices\":[{\"title\":\"演示域名即将到期\",\"severity\":\"warning\",\"detail\":\"合成提醒 · 请查看域名资产\"}]} ").put("generatedAt",time).put("status",status(time));
 if(path.equals("travel-ledger/current/projects"))return new JSONObject("{\"schemaVersion\":1,\"projects\":[{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"}]}").put("generatedAt",time);
 if(path.startsWith("travel-ledger/current/snapshot"))return new JSONObject("{\"schemaVersion\":1,\"project\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"},\"summary\":{},\"entries\":[],\"people\":[],\"page\":{\"hasMore\":false}}").put("generatedAt",time);
 if(path.equals("market"))return new JSONObject("{\"apiVersion\":\"2026-07-01\",\"sourceStatus\":{},\"watchlist\":[{\"label\":\"合成行情 A\",\"price\":123.45,\"currency\":\"USD\",\"changePct\":1.23}],\"sectors\":[],\"benchmarks\":[],\"aiUpstream\":[],\"disclaimer\":{\"text\":\"完全合成的界面预览数据\"}}").put("generatedAt",time);
 throw new IllegalStateException("此预览不连接真实服务");
 }
}
