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
 static JSONObject data(String path)throws Exception{
 String time=java.time.Instant.now().toString();
 if(path.equals("overview"))return new JSONObject("{\"apiVersion\":\"2026-08-01\",\"summary\":{\"servicesOnline\":18,\"servicesKnown\":20,\"servicesTotal\":22,\"infrastructureInstances\":4,\"domainAssets\":12},\"status\":{\"schemaVersion\":2,\"summary\":{},\"sites\":[],\"upstreams\":[]},\"notices\":[{\"title\":\"演示域名即将到期\",\"severity\":\"warning\",\"detail\":\"合成提醒 · 请查看域名资产\"}]} ").put("generatedAt",time);
 if(path.equals("travel-ledger/current/projects"))return new JSONObject("{\"schemaVersion\":1,\"projects\":[{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"}]}").put("generatedAt",time);
 if(path.startsWith("travel-ledger/current/snapshot"))return new JSONObject("{\"schemaVersion\":1,\"project\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"},\"summary\":{},\"entries\":[],\"people\":[],\"page\":{\"hasMore\":false}}").put("generatedAt",time);
 if(path.equals("market"))return new JSONObject("{\"apiVersion\":\"2026-07-01\",\"sourceStatus\":{},\"watchlist\":[{\"label\":\"合成行情 A\",\"price\":123.45,\"currency\":\"USD\",\"changePct\":1.23}],\"sectors\":[],\"benchmarks\":[],\"aiUpstream\":[],\"disclaimer\":{\"text\":\"完全合成的界面预览数据\"}}").put("generatedAt",time);
 throw new IllegalStateException("此预览不连接真实服务");
 }
}
