package starking.eccles.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import starking.eccles.access.ScreenNode;
import starking.eccles.access.ScreenSnapshot;
import starking.eccles.adb.AdbKeyStore;
import starking.eccles.adb.AdbTransport;
import starking.eccles.adb.RootTransport;
import starking.eccles.adb.TcpAdbTransport;
import starking.eccles.agent.service.CaptureService;
import starking.eccles.data.EcclesStorage;

/**
 * Orders the ways of acting and seeing, strongest first, ADB strictly last.
 * <ul>
 *   <li><b>Acting:</b> accessibility node actions first, a real touch gesture when the node cannot do
 *       it; ADB only after accessibility has failed.</li>
 *   <li><b>Seeing:</b> accessibility screenshot (Android 11+) then MediaProjection then ADB screencap.</li>
 * </ul>
 * ADB is completely lazy: it is not constructed, probed, or asked for a key (which would pop the
 * "Allow USB debugging?" prompt) unless something has already failed and it is the last option left.
 */
public class EcclesRouter {

	private static final String TAG= "EcclesRouter";

	private final Context ctx;
	private final AccessExecutor access;
	private final String mode;
	private final boolean adbAllowed;
	private final boolean adbOnly;

	private boolean adbTried;
	private AdbExecutor adbExec;
	private Boolean adbReady;

	private EcclesRouter(Context c){
		this.ctx= c.getApplicationContext();
		this.access= new AccessExecutor(c);
		this.mode= EcclesStorage.execMode(c);
		this.adbAllowed= !mode.equals(EcclesStorage.EXEC_ACCESSIBILITY);
		this.adbOnly= mode.equals(EcclesStorage.EXEC_ADB);
	}

	public static EcclesRouter create(Context c){
		return new EcclesRouter(c);
	}

	// ---- lazy ADB ------------------------------------------------------------------------

	private synchronized AdbExecutor adb(){
		if(adbTried) return adbExec;
		adbTried= true;
		try{
			AdbTransport t;
			if(EcclesStorage.ADB_ROOT.equals(EcclesStorage.adbTransport(ctx))){
				t= new RootTransport();
			} else {
				t= new TcpAdbTransport("127.0.0.1",EcclesStorage.adbPort(ctx),AdbKeyStore.get(ctx),"eccles-agent@android");
			}
			adbExec= new AdbExecutor(ctx,t);
		} catch (Exception e){
			Log.w(TAG,"ADB could not be set up: " + e.getMessage());
		}
		return adbExec;
	}

	/** The ADB executor if it is allowed AND actually answers; probed once, only when first needed. */
	private synchronized AdbExecutor adbIfReady(){
		if(!adbAllowed) return null;
		if(adbReady == null){
			AdbExecutor e= adb();
			adbReady= e != null && e.isAvailable();
			Log.i(TAG,"ADB last-resort available: " + adbReady);
		}
		return adbReady ? adb() : null;
	}

	// ---- state ---------------------------------------------------------------------------

	private boolean accessUp(){
		return !adbOnly && access.isAvailable();
	}

	public boolean anyAvailable(){
		return accessUp() || adbIfReady() != null;
	}

	/** True when a view tree is available (accessibility). Without it the agent is vision-only. */
	public boolean hasTree(){
		return accessUp();
	}

	public String describe(){
		StringBuilder sb= new StringBuilder();
		if(adbOnly) sb.append("ADB");
		else {
			sb.append("Accessibility");
			if(CaptureService.isActive()) sb.append(" + MediaProjection backup");
			if(adbAllowed) sb.append(" (ADB last resort)");
		}
		return sb.toString();
	}

	// ---- seeing --------------------------------------------------------------------------

	public String foregroundPackage(){
		if(accessUp()){
			String p= access.foregroundPackage();
			if(p != null && !p.isEmpty()) return p;
		}
		AdbExecutor e= adbIfReady();
		return e == null ? null : e.foregroundPackage();
	}

	public ScreenSnapshot snapshot(){
		return accessUp() ? access.snapshot() : null;
	}

	/**
	 * Accessibility screenshot first (Android 11+: no extra permission, no recording indicator, no
	 * persistent service), then MediaProjection if the user granted it (the only non-ADB option on
	 * Android 8-10, and a backup when the accessibility call is throttled or refused), then ADB screencap.
	 */
	public Bitmap capture(){
		Bitmap b= null;
		if(accessUp()){
			b= access.capture();
			if(b != null) return b;
		}
		b= CaptureService.capture();
		if(b != null) return b;
		AdbExecutor e= adbIfReady();
		return e == null ? null : e.capture();
	}

	/** Best-effort label of the element a click targets, used for the confirmation rule. */
	public String labelOf(EcclesAction a){
		if(a.type.equals(EcclesAction.CLICK) && accessUp()){
			ScreenNode n= access.resolveNode(a);
			if(n != null){
				String l= n.text.isEmpty() ? n.desc : n.text;
				if(!l.isEmpty()) return l;
			}
		}
		if(a.targetText != null) return a.targetText;
		if(a.targetDesc != null) return a.targetDesc;
		return "";
	}

	// ---- acting --------------------------------------------------------------------------

	public EcclesResult execute(EcclesAction a){
		StringBuilder why= new StringBuilder();
		if(accessUp()){
			EcclesResult r= access.execute(a);
			if(r.ok) return r;
			why.append("Accessibility: ").append(r.detail).append(' ');
			Log.w(TAG,"accessibility failed: " + r.detail);
		} else if(!adbOnly){
			why.append("Accessibility is not running. ");
		}

		// Everything else failed: ADB, if it is allowed and reachable.
		AdbExecutor e= adbIfReady();
		if(e == null){
			if(adbAllowed) why.append("(ADB is not available either.)");
			return EcclesResult.fail(why.toString().trim());
		}
		EcclesAction act= a;
		if(a.type.equals(EcclesAction.CLICK) || (a.type.equals(EcclesAction.LONG_PRESS) && !a.hasPoint())){
			act= toPoint(a);
			if(act == null){
				return EcclesResult.fail(why + "ADB needs coordinates and the element could not be located.");
			}
		}
		EcclesResult r= e.execute(act);
		if(r.ok) return EcclesResult.ok("via ADB (last resort)");
		return EcclesResult.fail(why + "ADB: " + r.detail);
	}

	private EcclesAction toPoint(EcclesAction a){
		if(a.hasPoint()) return a;
		ScreenNode n= accessUp() ? access.resolveNode(a) : null;
		if(n == null || n.password) return null;
		EcclesAction c= a.copy();
		c.type= a.type.equals(EcclesAction.LONG_PRESS) ? EcclesAction.LONG_PRESS : EcclesAction.TAP;
		c.x= n.centerX();
		c.y= n.centerY();
		if(c.durationMs <= 0) c.durationMs= AccessExecutor.TAP_MS;
		return c;
	}

	/** Waits for the UI to settle after an action. */
	public void settle(long minMs){
		SystemClock.sleep(minMs); // let the transition animation start before judging "idle"
		if(accessUp()) access.awaitIdle(2500);
	}
}
