package starking.eccles.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import androidx.preference.PreferenceManager;
import starking.eccles.crypto.KeystoreAes;

/**
 * Typed access to every setting the agent reads, plus encrypted storage for API keys.
 * <p>
 * Keys are never written to the normal preferences file: they are encrypted with an Android
 * Keystore AES-GCM key (non-exportable, bound to this device) and stored in a separate file,
 * so a backup, a file-manager root browse, or a screenshot of the Settings screen cannot leak
 * them. Preference defaults live here in code too, because an XML android:defaultValue is only
 * applied once the Settings screen has been opened.
 */
public class EcclesStorage {

	public static final String KEY_PROVIDER= "provider";
	public static final String KEY_MODEL_PREFIX= "model_";
	public static final String KEY_BASE_URL= "custom_base_url";
	public static final String KEY_EXEC_MODE= "exec_mode";
	public static final String KEY_ADB_TRANSPORT= "adb_transport";
	public static final String KEY_ADB_PORT= "adb_port";
	public static final String KEY_VISION_MODE= "vision_mode";
	public static final String KEY_MAX_STEPS= "max_steps";
	public static final String KEY_STEP_DELAY= "step_delay";
	public static final String KEY_CONFIRM= "confirm_sensitive";
	public static final String KEY_SMART_DETECT= "smart_detect";
	public static final String KEY_BLOCK_PASSWORD= "block_password";
	public static final String KEY_OVERLAY= "show_overlay";
	public static final String KEY_THEME= "theme_mode";

	public static final String PROVIDER_DEFAULT= "gemini";
	public static final String EXEC_AUTO= "auto";
	public static final String EXEC_ACCESSIBILITY= "accessibility";
	public static final String EXEC_ADB= "adb";
	public static final String ADB_TCP= "tcp";
	public static final String ADB_ROOT= "root";
	public static final String VISION_FALLBACK= "fallback";
	public static final String VISION_ALWAYS= "always";
	public static final String VISION_NEVER= "never";

	private static final String SECRETS_FILE= "eccles_agent_secrets";
	private static final KeystoreAes SECRET_KEY= new KeystoreAes("eccles_agent_secret_key");

	public static SharedPreferences prefs(Context c){
		return PreferenceManager.getDefaultSharedPreferences(c);
	}

	public static String provider(Context c){
		String p= prefs(c).getString(KEY_PROVIDER,PROVIDER_DEFAULT);
		return p == null || p.isEmpty() ? PROVIDER_DEFAULT : p;
	}

	public static String model(Context c,String provider){
		return prefs(c).getString(KEY_MODEL_PREFIX + provider,"").trim();
	}

	public static String baseUrl(Context c){
		return prefs(c).getString(KEY_BASE_URL,"").trim();
	}

	public static String execMode(Context c){
		return prefs(c).getString(KEY_EXEC_MODE,EXEC_AUTO);
	}

	public static String adbTransport(Context c){
		return prefs(c).getString(KEY_ADB_TRANSPORT,ADB_TCP);
	}

	public static int adbPort(Context c){
		return clamp(parseInt(prefs(c).getString(KEY_ADB_PORT,"5555"),5555),1,65535);
	}

	public static String visionMode(Context c){
		return prefs(c).getString(KEY_VISION_MODE,VISION_FALLBACK);
	}

	public static int maxSteps(Context c){
		return clamp(parseInt(prefs(c).getString(KEY_MAX_STEPS,"25"),25),3,60);
	}

	public static long stepDelayMs(Context c){
		return clamp(parseInt(prefs(c).getString(KEY_STEP_DELAY,"700"),700),200,3000);
	}

	public static boolean confirmSensitive(Context c){
		return prefs(c).getBoolean(KEY_CONFIRM,true);
	}

	public static boolean smartDetect(Context c){
		return prefs(c).getBoolean(KEY_SMART_DETECT,true);
	}

	public static boolean blockPasswordScreens(Context c){
		return prefs(c).getBoolean(KEY_BLOCK_PASSWORD,true);
	}

	public static boolean showOverlay(Context c){
		return prefs(c).getBoolean(KEY_OVERLAY,true);
	}

	// ---- API keys -----------------------------------------------------------------------

	private static SharedPreferences secrets(Context c){
		return c.getSharedPreferences(SECRETS_FILE,Context.MODE_PRIVATE);
	}

	public static void saveApiKey(Context c,String provider,String key){
		String k= key == null ? "" : key.trim();
		SharedPreferences.Editor e= secrets(c).edit();
		if(k.isEmpty()){
			e.remove("key_" + provider);
		} else {
			try{
				byte[] enc= SECRET_KEY.encrypt(k.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				e.putString("key_" + provider,Base64.encodeToString(enc,Base64.NO_WRAP));
			} catch (Exception ex){
				Log.e("EcclesStorage","Could not encrypt API key",ex);
				return;
			}
		}
		e.apply();
	}

	public static String apiKey(Context c,String provider){
		String b64= secrets(c).getString("key_" + provider,null);
		if(b64 == null) return "";
		try{
			return new String(SECRET_KEY.decrypt(Base64.decode(b64,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception ex){
			// Keystore key lost (e.g. device restore): treat as "not set" so the user is asked again.
			Log.e("EcclesStorage","Stored API key unreadable",ex);
			secrets(c).edit().remove("key_" + provider).apply();
			return "";
		}
	}

	public static boolean hasApiKey(Context c,String provider){
		return !apiKey(c,provider).isEmpty();
	}

	/** "••••1234" style hint, safe to show in the UI. */
	public static String maskedKey(Context c,String provider){
		String k= apiKey(c,provider);
		if(k.isEmpty()) return "";
		return k.length() <= 4 ? "••••" : "••••" + k.substring(k.length() - 4);
	}

	private static int parseInt(String s,int def){
		try{ return Integer.parseInt(s.trim()); } catch (Exception e){ return def; }
	}

	private static int clamp(int v,int lo,int hi){
		return Math.max(lo,Math.min(hi,v));
	}
}
