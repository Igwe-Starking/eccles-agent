package starking.eccles.agent;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.snackbar.Snackbar;
import starking.eccles.access.EcclesAccessibilityService;

public class EcclesActivity extends AppCompatActivity {

	public static final String TAG= "EcclesActivity";

	protected EcclesApplication app;

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		app= (EcclesApplication) getApplication();
		applySystemBars();
	}

	/** Dark icons on light bars and vice versa, so the status/navigation bar never vanishes into the background. */
	private void applySystemBars(){
		boolean night= (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		WindowInsetsControllerCompat c= WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView());
		c.setAppearanceLightStatusBars(!night);
		c.setAppearanceLightNavigationBars(!night);
		// Before Android 8.1 navigation-bar icons cannot be darkened, so keep that bar black in the light theme.
		if(Build.VERSION.SDK_INT < 27 && !night) getWindow().setNavigationBarColor(Color.BLACK);
	}

	protected void toast(String m){
		Toast.makeText(this,m,Toast.LENGTH_SHORT).show();
	}

	protected Snackbar snack(View anchor,String m){
		Snackbar s= Snackbar.make(anchor,m,Snackbar.LENGTH_LONG);
		s.show();
		return s;
	}

	/** True when the user has switched the Eccles Agent accessibility service on in Android settings. */
	public static boolean isAccessibilityEnabled(Context c){
		try{
			if(Settings.Secure.getInt(c.getContentResolver(),Settings.Secure.ACCESSIBILITY_ENABLED,0) != 1) return false;
			String list= Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
			if(list == null) return false;
			ComponentName me= new ComponentName(c,EcclesAccessibilityService.class);
			for(String s : list.split(":")){
				ComponentName cn= ComponentName.unflattenFromString(s);
				if(cn != null && cn.equals(me)) return true;
			}
		} catch (Exception ignored){
		}
		return false;
	}

	public void openAccessibilitySettings(){
		try{
			startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
		} catch (Exception e){
			toast("Open Android Settings > Accessibility and turn on Eccles Agent.");
		}
	}
}
