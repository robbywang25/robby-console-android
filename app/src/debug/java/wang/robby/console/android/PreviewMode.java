package wang.robby.console.android;
import android.content.Intent;
import org.json.JSONObject;
/** Debug-only isolated visual fixture. No requests and no real credentials. */
final class PreviewMode {
 private static boolean signedOut;
 static boolean isBuild(){return true;}
 static boolean loggedIn(){return !signedOut;}
 static void configure(android.app.Activity activity,Intent intent){signedOut=intent.getBooleanExtra("preview_signed_out",false);}
 static JSONObject data(String path)throws Exception{
 String time=java.time.Instant.now().toString();
 if(path.equals("overview"))return new JSONObject("{\"apiVersion\":\"2026-08-01\",\"summary\":{\"servicesOnline\":18,\"servicesKnown\":20,\"servicesTotal\":22,\"infrastructureInstances\":4,\"domainAssets\":12},\"status\":{\"schemaVersion\":2,\"summary\":{},\"sites\":[],\"upstreams\":[]},\"notices\":[{\"title\":\"演示域名即将到期\",\"severity\":\"warning\",\"detail\":\"合成提醒 · 请查看域名资产\"}]} ").put("generatedAt",time);
 if(path.equals("travel-ledger/current/projects"))return new JSONObject("{\"schemaVersion\":1,\"projects\":[{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"}]}").put("generatedAt",time);
 if(path.startsWith("travel-ledger/current/snapshot"))return new JSONObject("{\"schemaVersion\":1,\"project\":{\"id\":\"00000000000000000000000000000001\",\"name\":\"界面演示账本\"},\"summary\":{},\"entries\":[],\"people\":[],\"page\":{\"hasMore\":false}}").put("generatedAt",time);
 if(path.equals("market"))return new JSONObject("{\"apiVersion\":\"2026-07-01\",\"sourceStatus\":{},\"watchlist\":[{\"label\":\"合成行情 A\",\"price\":123.45,\"currency\":\"USD\",\"changePct\":1.23}],\"sectors\":[],\"benchmarks\":[],\"aiUpstream\":[],\"disclaimer\":{\"text\":\"完全合成的界面预览数据\"}}").put("generatedAt",time);
 throw new IllegalStateException("此预览不连接真实服务");
 }
}
