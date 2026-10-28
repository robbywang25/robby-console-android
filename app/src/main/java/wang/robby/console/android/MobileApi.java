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
        public final int status; public final String code; public final JSONObject body;
        ApiError(int status,String code,String message){this(status,code,message,null);}
        ApiError(int status,String code,String message,JSONObject body){super(message);this.status=status;this.code=code;this.body=body;}
    }
    public static String checkedPath(String path) {
        if (path == null || path.length()>5000 || !path.matches("[a-z0-9][a-z0-9/-]*(\\?[a-zA-Z0-9_=&%.-]+)?") || path.contains("..") || path.contains("//"))
            throw new IllegalArgumentException("接口地址无效");
        String route = path.split("\\?",2)[0];
        boolean known = FeatureCatalog.all().stream().anyMatch(f -> f.path.equals(route));
        if (!known && !route.equals("travel-ledger/current/snapshot") && !route.equals("tokens/tibo/alerts") && !ledgerRoute(route) && !AccountCenter.readRoute(route) && !AccountCenter.writeRoute(route))
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
        long started=android.os.SystemClock.elapsedRealtime();String token=auth.accessToken();long signed=android.os.SystemClock.elapsedRealtime();
        try { JSONObject result=request("GET",path,null,token);timing(path,started,signed);return result; }
        catch (ApiError error) { if (!retryWithFreshToken(error)) throw error; }
        return request("GET",path,null,auth.accessToken(true));
    }
    /** Route and milliseconds only (sign-in vs the read itself); never the token, query or data. */
    private static void timing(String path,long started,long signed){
        long now=android.os.SystemClock.elapsedRealtime();android.util.Log.i("GodApi",path.split("\\?",2)[0]+" token="+(signed-started)+"ms http="+(now-signed)+"ms");
    }
    static boolean retryWithFreshToken(ApiError error){return error.status==401;}
    /** One avatar exchange: 200 with bytes (GET) or a new ETag (PUT), 304 unchanged, 404 not set. */
    public static final class AvatarResult { public final int status; public final byte[] bytes; public final String etag;
        AvatarResult(int status,byte[] bytes,String etag){this.status=status;this.bytes=bytes;this.etag=etag;} }
    static final String AVATAR_URL="https://god.robby.wang/api/mobile/v1/profile/avatar";
    /**
     * The owner's avatar on the server: GET (with the ETag already held), PUT a JPEG, or DELETE. A rejected bearer is
     * refused before the server reads the body, so every method gets the same one forced refresh and retry.
     */
    public AvatarResult avatar(String method,byte[] jpeg,String etag) throws Exception {
        if(!method.matches("GET|PUT|DELETE")||method.equals("PUT")!=(jpeg!=null)||jpeg!=null&&jpeg.length>262144)throw new IllegalArgumentException("头像请求无效");
        try{return avatarRequest(method,jpeg,etag,auth.accessToken());}
        catch(ApiError error){if(!retryWithFreshToken(error))throw error;}
        return avatarRequest(method,jpeg,etag,auth.accessToken(true));
    }
    private AvatarResult avatarRequest(String method,byte[] jpeg,String etag,String accessToken) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(AVATAR_URL).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(25000);c.setRequestMethod(method);c.setUseCaches(false);
        c.setRequestProperty("Authorization","Bearer "+accessToken);c.setRequestProperty("Cache-Control","no-store");c.setRequestProperty("X-Request-ID",UUID.randomUUID().toString());
        if(etag!=null&&etag.matches("\"[a-f0-9]{32}\"")&&method.equals("GET"))c.setRequestProperty("If-None-Match",etag);
        try{
            if(jpeg!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","image/jpeg");c.setFixedLengthStreamingMode(jpeg.length);try(OutputStream out=c.getOutputStream()){out.write(jpeg);}}
            int status=c.getResponseCode();String tag=c.getHeaderField("ETag");if(tag!=null&&!tag.matches("\"[a-f0-9]{32}\""))tag=null;
            if(method.equals("GET")&&status==304)return new AvatarResult(304,null,etag);
            String type=c.getContentType();type=type==null?"":type.toLowerCase(java.util.Locale.ROOT).split(";",2)[0].trim();
            InputStream source=status<400?c.getInputStream():c.getErrorStream();byte[] data=new byte[0];
            if(source!=null)try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>300*1024)throw new IOException("响应超出安全大小");out.write(buf,0,n);}data=out.toByteArray();}
            if(status==200&&method.equals("GET")){if(!type.equals("image/jpeg")||tag==null||data.length<4||(data[0]&0xff)!=0xff||(data[1]&0xff)!=0xd8)throw new IOException("头像格式不匹配");return new AvatarResult(200,data,tag);}
            JSONObject result=null;if(type.equals("application/json")&&data.length>0)try{result=new JSONObject(new String(data,StandardCharsets.UTF_8));}catch(org.json.JSONException ignored){}
            if(status==200){String saved=result==null?null:result.optString("etag",null);return new AvatarResult(200,null,saved!=null&&saved.matches("\"[a-f0-9]{32}\"")?saved:null);}
            if(status==404&&method.equals("GET"))return new AvatarResult(404,null,null);
            String code=result==null?"http_error":result.optString("error","http_error");if(!code.matches("[a-z0-9_]{1,80}"))code="http_error";
            throw new ApiError(status,code,switch(status){case 401->"会话已失效，请重新登录";case 403->"需要重新登录一次，授权 God 保存头像";case 413->"头像文件过大";case 415,422->"头像格式不受支持";case 429->"请求较多，请稍后再试";default->status>=500?"服务暂不可用，请稍后重试":"服务器未返回预期结果";});
        }finally{c.disconnect();}
    }
    /** How many response bytes may be read. A preferences 409 carries the current document plus its wrapper. */
    static int responseLimit(int status, String path) {
        if (status == 200) return path != null && path.startsWith("travel-ledger/current/snapshot") ? 2097152 : 524288;
        if (status == 409 && AccountCenter.PREFERENCES.equals(path)) return 24576;
        return 16384;
    }
    public JSONObject post(String path,JSONObject body) throws Exception {
        checkedPath(path);
        if(!writable(path))throw new IllegalArgumentException("写入接口未登记");
        return request("POST",path,body,auth.accessToken());
    }
    /** Writes go only to registered routes; the lamp's versioned app-secrets path is the one write with a query. */
    static boolean writable(String path){return path.matches("travel-ledger/(entry-writes|people-writes)/(prepare|commit|resolve)")||AccountCenter.writeRoute(path)||AccountCenter.APP_SECRETS_V2.equals(path);}
    private JSONObject request(String method,String path,JSONObject body,String accessToken) throws Exception {
        checkedPath(path);
        URL url = new URL("https://god.robby.wang/api/mobile/v1/" + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(12000); c.setReadTimeout(25000); c.setRequestMethod(method);
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Cache-Control", "no-store");
        c.setRequestProperty("X-Request-ID",UUID.randomUUID().toString()); c.setUseCaches(false);
        // A fully read response leaves the connection in the pool, so the next read skips the TCP and TLS handshake
        // (most of a read's time from the phone); anything that did not finish cleanly is disconnected instead.
        boolean reusable=false;
        try {
            if(body!=null){byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>16384)throw new IOException("提交内容过大");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=c.getOutputStream()){out.write(bytes);}}
            int status = c.getResponseCode();
            int max=responseLimit(status, path);
            String type = c.getContentType();JSONObject result=null;
            if(type!=null&&type.toLowerCase(java.util.Locale.ROOT).split(";",2)[0].trim().equals("application/json")){
                if(c.getContentLengthLong()>max)throw new IOException("响应超出安全大小");
                InputStream source=status<400?c.getInputStream():c.getErrorStream();
                if(source!=null)try(InputStream in=source;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("响应超出安全大小");out.write(buf,0,n);}result=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
                }
            }
            // Ledger routes name the refusal in "code", the account routes in "error".
            if(status!=200){String code=result==null?"http_error":result.optString("code",result.optString("error","http_error"));if(!code.matches("[a-z0-9_]{1,80}"))code="http_error";
                throw new ApiError(status,code,switch(status){case 401->"会话已失效，请重新登录";case 403->"当前账号没有此功能的权限";case 404->"此移动接口尚未启用";case 409->"数据已变化，请刷新后重试";case 429->"请求较多，请稍后刷新";default->status>=500?"服务暂不可用，请稍后重试":"服务器未返回预期结果";},result);}
            if(result==null)throw new IOException("接口返回格式不匹配");reusable=true;return result;
        } finally { if(!reusable)c.disconnect(); }
    }
}
