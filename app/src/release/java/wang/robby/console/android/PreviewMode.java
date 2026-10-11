package wang.robby.console.android;
import android.content.Intent;
import org.json.JSONObject;
/** Release implementation has no fixture data or preview entry point. */
final class PreviewMode {
 static boolean isBuild(){return false;}
 static boolean loggedIn(){return false;}
 static void configure(android.app.Activity activity,Intent ignored){}
 static JSONObject data(String ignored){throw new IllegalStateException();}
}
