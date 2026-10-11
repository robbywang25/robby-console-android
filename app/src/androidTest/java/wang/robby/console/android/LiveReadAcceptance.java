package wang.robby.console.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import wang.robby.console.android.auth.AuthSession;

/** Explicit, signed, device-only acceptance. Never part of the release APK or automatic tests.
 * Run only after the device owner approves login, with live_read_check=true.
 * Business requests are GET-only; refreshing rotates only this app's own session.
 * Results contain fixed booleans/status codes, never tokens, identities or business data.
 */
public final class LiveReadAcceptance extends Instrumentation {
    private boolean approved;
    @Override public void onCreate(Bundle args) {super.onCreate(args);approved=args!=null&&"true".equals(args.getString("live_read_check"));start();}
    @Override public void onStart() {
        Bundle out=new Bundle();
        if(!approved){out.putString("result","explicit_live_read_check_required");finish(Activity.RESULT_CANCELED,out);return;}
        try {
            AuthSession auth=new AuthSession(getTargetContext());out.putBoolean("session_present",auth.hasSession());
            if(!auth.hasSession()){finish(Activity.RESULT_CANCELED,out);return;}
            String[] jwt=auth.accessToken().split("\\.");
            if(jwt.length==3){
                JSONObject claims=new JSONObject(new String(android.util.Base64.decode(jwt[1],android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING),java.nio.charset.StandardCharsets.UTF_8));
                out.putBoolean("expected_issuer",AuthSession.ISSUER.equals(claims.optString("iss")));
                out.putBoolean("expected_client",AuthSession.CLIENT_ID.equals(claims.optString("client_id")));
                Object aud=claims.opt("aud");boolean audience=AuthSession.AUDIENCE.equals(aud);
                if(aud instanceof JSONArray)for(int i=0;i<((JSONArray)aud).length();i++)audience|=AuthSession.AUDIENCE.equals(((JSONArray)aud).optString(i));
                out.putBoolean("expected_audience",audience);
                out.putBoolean("mobile_lifetime_bounded",claims.optLong("exp")-claims.optLong("iat")<=600);
            }
            MobileApi api=new MobileApi(auth);
            try{JSONObject p=api.get("overview");out.putBoolean("overview_contract",FeatureRenderer.isCompatible(FeatureCatalog.find("overview"),p));}
            catch(MobileApi.ApiError error){out.putInt("overview_http_error",error.status);}
            catch(Exception ignored){out.putBoolean("overview_contract",false);}
            try {
                JSONObject projects=api.get("travel-ledger/current/projects");JSONArray all=projects.getJSONArray("projects");
                out.putBoolean("ledger_projects_contract",projects.getInt("schemaVersion")==1);
                if(all.length()>0){String id=all.getJSONObject(0).getString("id");
                    if(!id.matches("[a-f0-9]{32}|new-zealand-south-island-2026"))throw new IllegalArgumentException();
                    JSONObject snapshot=api.get("travel-ledger/current/snapshot?project="+id+"&limit=1");
                    out.putBoolean("ledger_snapshot_contract",FeatureRenderer.isCompatible(FeatureCatalog.find("travel-ledger"),snapshot));
                }else out.putBoolean("ledger_projects_empty",true);
            }catch(MobileApi.ApiError error){out.putInt("ledger_http_error",error.status);}
            catch(Exception ignored){out.putBoolean("ledger_projects_contract",false);}
            try{auth.accessToken(true);out.putBoolean("refresh_succeeded",true);}catch(Exception ignored){out.putBoolean("refresh_succeeded",false);}
            out.putBoolean("business_writes_performed",false);finish(Activity.RESULT_OK,out);
        } catch(Exception ignored){out.putString("result","acceptance_unavailable");finish(Activity.RESULT_CANCELED,out);}
    }
}
