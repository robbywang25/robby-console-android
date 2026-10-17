package wang.robby.console.android;

import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import wang.robby.console.android.auth.AuthSession;

/** Bounded, no-redirect, bearer-only transport to the fixed private mobile API. */
public final class MobileApi {
    private final AuthSession auth;
    public MobileApi(AuthSession auth) { this.auth = auth; }
    public static final class ApiError extends IOException {
        public final int status; public final String code;
        ApiError(int status,String code,String message){super(message);this.status=status;this.code=code;}
    }
    public static String checkedPath(String path) {
        if (path == null || path.length()>5000 || !path.matches("[a-z0-9][a-z0-9/-]*(\\?[a-zA-Z0-9_=&%.-]+)?") || path.contains("..") || path.contains("//"))
            throw new IllegalArgumentException("接口地址无效");
        String route = path.split("\\?",2)[0];
        boolean known = FeatureCatalog.all().stream().anyMatch(f -> f.path.equals(route));
        if (!known && !route.equals("travel-ledger/current/snapshot") && !route.equals("tokens/tibo/alerts") && !ledgerRoute(route))
            throw new IllegalArgumentException("接口未登记");
        return path;
    }
    private static boolean ledgerRoute(String route){return route.matches("travel-ledger/(entry-writes|people-writes)/(context|prepare|commit|receipt|resolve)")||route.equals("travel-ledger/entry-writes/trash");}
    /**
     * A 401 means the bearer was rejected before the request was processed. Reads get exactly one
     * forced refresh and retry, so a server-side revocation or early expiry no longer leaves the
     * screen stuck on "重新验证" until the cached access token naturally ages out. Writes never retry.
     */
    public JSONObject get(String path) throws Exception {
        checkedPath(path);
        try { return request("GET",path,null,auth.accessToken()); }
        catch (ApiError error) { if (!retryWithFreshToken(error)) throw error; }
        return request("GET",path,null,auth.accessToken(true));
    }
    static boolean retryWithFreshToken(ApiError error){return error.status==401;}
    public JSONObject post(String path,JSONObject body) throws Exception {
        checkedPath(path);
        if(!path.matches("travel-ledger/(entry-writes|people-writes)/(prepare|commit|resolve)"))throw new IllegalArgumentException("写入接口未登记");
        return request("POST",path,body,auth.accessToken());
    }
    private JSONObject request(String method,String path,JSONObject body,String accessToken) throws Exception {
        checkedPath(path);
        URL url = new URL("https://god.robby.wang/api/mobile/v1/" + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000); c.setReadTimeout(25000); c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Cache-Control", "no-store");
        c.setRequestProperty("X-Request-ID",UUID.randomUUID().toString()); c.setUseCaches(false);
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IOException("提交内容过大");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status = c.getResponseCode();
            int max=status==200?(path.startsWith("travel-ledger/current/snapshot")?2097152:524288):16384;
            String type = c.getContentType();JSONObject result=null;
            if(type!=null&&type.toLowerCase(java.util.Locale.ROOT).split(";",2)[0].trim().equals("application/json")){
                if(c.getContentLengthLong()>max)throw new IOException("响应超出安全大小");
                InputStream source=status<400?c.getInputStream():c.getErrorStream();
                if(source!=null)try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("响应超出安全大小");out.write(buf,0,n);}result=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                }
            }
            if(status!=200){String code=result==null?"http_error":result.optString("code","http_error");if(!code.matches("[a-z0-9_]{1,80}"))code="http_error";
                throw new ApiError(status,code,switch(status){case 401->"会话已失效，请重新登录";case 403->"当前账号没有此功能的权限";case 404->"此移动接口尚未启用";case 409->"数据已变化，请刷新后重试";case 429->"请求较多，请稍后刷新";default->status>=500?"服务暂不可用，请稍后重试":"服务器未返回预期结果";});}
            if(result==null)throw new IOException("接口返回格式不匹配");return result;
        } finally { c.disconnect(); }
    }
}
