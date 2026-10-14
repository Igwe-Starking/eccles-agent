package starking.eccles.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import starking.eccles.access.ScreenSnapshot;
import starking.eccles.adb.AdbTransport;
import starking.eccles.guard.EcclesGuard;

/**
 * Performs actions with shell input commands over ADB/root. It has no view tree, so it works in
 * coordinates only; the engine therefore attaches a screenshot on every step when this is the
 * only executor available.
 */
public class AdbExecutor implements EcclesExecutor {

	private static final String TAG= "AdbExecutor";
	private static final Pattern PKG= Pattern.compile("^[A-Za-z0-9._]+$");
	private static final Pattern FOCUS= Pattern.compile("([A-Za-z0-9._]+)/[A-Za-z0-9._$]+");
	private static final Pattern RESUMED= Pattern.compile("u\\d+ ([A-Za-z0-9._]+)/");

	private final Context ctx;
	private final AdbTransport transport;

	public AdbExecutor(Context c,AdbTransport t){
		this.ctx= c.getApplicationContext();
		this.transport= t;
	}

	@Override public String name(){ return transport.name(); }

	@Override public boolean isAvailable(){ return transport.isAvailable(); }

	private String sh(String cmd,int timeoutMs) throws Exception{
		return new String(transport.exec(cmd,timeoutMs),StandardCharsets.UTF_8);
	}

	@Override
	public EcclesResult execute(EcclesAction a){
		try{
			switch(a.type){
				case EcclesAction.OPEN_APP: return openApp(a);
				case EcclesAction.TAP:
				case EcclesAction.LONG_PRESS:
					if(!a.hasPoint()) return EcclesResult.fail("ADB needs coordinates for " + a.type + ".");
					// "swipe x y x y dur" held in place is the standard way to express a timed press
					return input("input swipe " + a.x + " " + a.y + " " + a.x + " " + a.y + " " + a.durationMs);
				case EcclesAction.CLICK:
					if(!a.hasPoint()) return EcclesResult.fail("ADB cannot find elements; it needs x,y coordinates.");
					return input("input tap " + a.x + " " + a.y);
				case EcclesAction.SWIPE:
					return input("input swipe " + a.x + " " + a.y + " " + a.x2 + " " + a.y2 + " " + a.durationMs);
				case EcclesAction.SCROLL: return scroll(a.direction);
				case EcclesAction.KEY: return key(a.key);
				case EcclesAction.TYPE: return type(a.text);
				case EcclesAction.WAIT:
					SystemClock.sleep(a.durationMs);
					return EcclesResult.ok();
				default:
					return EcclesResult.fail("Unsupported action " + a.type);
			}
		} catch (Exception e){
			return EcclesResult.fail("ADB error: " + e.getMessage());
		}
	}

	private EcclesResult input(String cmd) throws Exception{
		String out= sh(cmd,8000);
		if(out.contains("Exception") || out.contains("Error:") || out.contains("not found")){
			return EcclesResult.fail("ADB: " + shorten(out));
		}
		return EcclesResult.ok();
	}

	private EcclesResult openApp(EcclesAction a) throws Exception{
		String pkg= AppResolver.resolve(ctx,a.app);
		if(pkg == null) return EcclesResult.fail("No installed app matches \"" + a.app + "\".");
		if(!PKG.matcher(pkg).matches()) return EcclesResult.fail("Refused: odd package name.");
		EcclesGuard.Decision d= EcclesGuard.check(ctx,pkg);
		if(d.blocked) return EcclesResult.fail("Refused: " + d.reason);
		String out= sh("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1",10000);
		if(!out.contains("Events injected: 1")) return EcclesResult.fail("ADB could not start " + a.app + ": " + shorten(out));
		return EcclesResult.ok("opened " + pkg);
	}

	private EcclesResult scroll(String dir) throws Exception{
		// Defaults assume a ~1080x2400 screen; swipes are proportional via the real size when known.
		int[] sz= screenSize();
		int w= sz[0],h= sz[1];
		int cx= w / 2,cy= h / 2;
		int x1= cx,y1= cy,x2= cx,y2= cy;
		switch(dir){
			case "up": y1= (int) (h * 0.3); y2= (int) (h * 0.7); break;
			case "left": x1= (int) (w * 0.2); x2= (int) (w * 0.8); break;
			case "right": x1= (int) (w * 0.8); x2= (int) (w * 0.2); break;
			default: y1= (int) (h * 0.7); y2= (int) (h * 0.3);
		}
		return input("input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " 350");
	}

	private int[] screenSize() throws Exception{
		Matcher m= Pattern.compile("(\\d+)x(\\d+)").matcher(sh("wm size",4000));
		if(m.find()) return new int[]{Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2))};
		return new int[]{1080,2400};
	}

	private EcclesResult key(String k) throws Exception{
		switch(k){
			case EcclesAction.KEY_BACK: return input("input keyevent 4");
			case EcclesAction.KEY_HOME: return input("input keyevent 3");
			case EcclesAction.KEY_RECENTS: return input("input keyevent 187");
			case EcclesAction.KEY_NOTIFICATIONS: return input("cmd statusbar expand-notifications");
			case EcclesAction.KEY_QUICK_SETTINGS: return input("cmd statusbar expand-settings");
			default: return EcclesResult.fail("Unknown key " + k);
		}
	}

	private EcclesResult type(String text) throws Exception{
		for(int i= 0;i < text.length();i++){
			char ch= text.charAt(i);
			if(ch < 32 || ch > 126){
				return EcclesResult.fail("ADB can only type plain ASCII text; use the accessibility service for \"" + ch + "\".");
			}
		}
		// input text: spaces must be %s; wrap in single quotes (escaping any inside) so the shell sees one argument
		String arg= text.replace("'","'\\''").replace(" ","%s");
		return input("input text '" + arg + "'");
	}

	@Override
	public String foregroundPackage(){
		try{
			String out= sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",8000);
			for(String line : out.split("\n")){
				if(line.contains("mCurrentFocus") && !line.contains("=null")){
					Matcher m= FOCUS.matcher(line);
					if(m.find()) return m.group(1);
				}
			}
			for(String line : out.split("\n")){
				if(line.contains("mFocusedApp") && !line.contains("=null")){
					Matcher m= RESUMED.matcher(line);
					if(m.find()) return m.group(1);
				}
			}
		} catch (Exception e){
			Log.w(TAG,"foreground lookup failed: " + e.getMessage());
		}
		return null;
	}

	@Override
	public Bitmap capture(){
		try{
			byte[] png= transport.exec("screencap -p",20000);
			if(png.length < 8 || (png[0] & 0xff) != 0x89 || png[1] != 'P') return null;
			return BitmapFactory.decodeByteArray(png,0,png.length);
		} catch (Exception e){
			Log.w(TAG,"screencap failed: " + e.getMessage());
			return null;
		}
	}

	@Override
	public ScreenSnapshot snapshot(){
		return null;
	}

	private static String shorten(String s){
		s= s.replace('\n',' ').trim();
		return s.length() > 160 ? s.substring(0,160) + "…" : s;
	}
}
