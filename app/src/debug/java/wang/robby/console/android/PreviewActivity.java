package wang.robby.console.android;
import android.content.Context;
import android.content.res.Configuration;
/** App-scoped configurations for visual checks. Never changes device settings. */
public class PreviewActivity extends MainActivity {
 protected boolean dark(){return false;}
 protected float fontScale(){return 1f;}
 @Override protected void attachBaseContext(Context base){Configuration c=new Configuration(base.getResources().getConfiguration());c.uiMode=(c.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(dark()?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);c.fontScale=fontScale();super.attachBaseContext(base.createConfigurationContext(c));}
 public static class Dark extends PreviewActivity { @Override protected boolean dark(){return true;} }
 public static class Large extends PreviewActivity { @Override protected float fontScale(){return 1.6f;} }
}
