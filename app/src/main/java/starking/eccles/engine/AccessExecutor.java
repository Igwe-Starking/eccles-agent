package starking.eccles.engine;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import starking.eccles.access.EcclesAccessibilityService;
import starking.eccles.access.ScreenNode;
import starking.eccles.access.ScreenSnapshot;
import starking.eccles.guard.EcclesGuard;

/** Performs actions through the AccessibilityService: semantic clicks first, gestures as backup. */
public class AccessExecutor implements EcclesExecutor {

	/** Default touch length for a tap: a real, short press (the standard 0 -> 100 ms stroke). */
	public static final long TAP_MS= 100;

	private final Context ctx;

	public AccessExecutor(Context c){
		this.ctx= c.getApplicationContext();
	}

	private EcclesAccessibilityService svc(){
		return EcclesAccessibilityService.get();
	}

	@Override public String name(){ return "Accessibility"; }

	@Override public boolean isAvailable(){ return svc() != null; }

	@Override
	public EcclesResult execute(EcclesAction a){
		EcclesAccessibilityService s= svc();
		if(s == null) return EcclesResult.fail("The Eccles Agent accessibility service is not running.");
		try{
			switch(a.type){
				case EcclesAction.OPEN_APP: return openApp(s,a);
				case EcclesAction.CLICK: return click(s,a);
				case EcclesAction.TAP: return tapAt(s,a);
				case EcclesAction.LONG_PRESS: return longPress(s,a);
				case EcclesAction.SWIPE:
					return s.swipe(a.x,a.y,a.x2,a.y2,a.durationMs) ? EcclesResult.ok() : EcclesResult.fail("The swipe was cancelled or not dispatched.");
				case EcclesAction.TYPE: return type(s,a);
				case EcclesAction.KEY:
					return s.globalKey(a.key) ? EcclesResult.ok() : EcclesResult.fail("The system refused the " + a.key + " action.");
				case EcclesAction.SCROLL: return scroll(s,a);
				case EcclesAction.WAIT:
					SystemClock.sleep(a.durationMs);
					return EcclesResult.ok();
				default:
					return EcclesResult.fail("Unsupported action " + a.type);
			}
		} catch (Exception e){
			return EcclesResult.fail("Accessibility error: " + e.getMessage());
		}
	}

	/**
	 * Opens an app through a ladder of independent methods, verifying each by watching the foreground
	 * package, because Android 10+ silently ignores startActivity() from the background (no exception).
	 * <ol>
	 *   <li>Direct launch. Reliable while Eccles's own screen is in front (the first step of a task).</li>
	 *   <li>Launch from the accessibility service's own context.</li>
	 *   <li>Drive the launcher like a user: Home, find the app's icon in the launcher's accessibility tree
	 *       (opening the app drawer and scrolling if needed) and click it. The launcher starts the app,
	 *       so the background-start restriction never applies. The router then adds ADB as the last resort.</li>
	 * </ol>
	 */
	private EcclesResult openApp(EcclesAccessibilityService s,EcclesAction a){
		String pkg= AppResolver.resolve(ctx,a.app);
		if(pkg == null){
			java.util.List<String> close= AppResolver.suggest(ctx,a.app,4);
			return EcclesResult.fail("No installed app is called \"" + a.app + "\"."
				+ (close.isEmpty() ? " Use a name from INSTALLED APPS." : " Did you mean: " + String.join(", ",close) + "?"));
		}
		EcclesGuard.Decision d= EcclesGuard.check(ctx,pkg);
		if(d.blocked) return EcclesResult.fail("Refused: " + d.reason);
		String label= AppResolver.label(ctx,pkg);

		String fg= s.foregroundPackage();
		if(pkg.equals(fg)) return EcclesResult.ok(label + " is already open");
		boolean ourScreenFront= ctx.getPackageName().equals(fg);

		// 1. Direct launch: gets the generous wait when our own screen is in front, since that is when it works.
		if(AppResolver.launch(ctx,pkg) && waitForeground(s,pkg,ourScreenFront ? 4000 : 1500)) return opened(s,label,"direct launch");

		// 2. From the accessibility service's context (only worth trying when we were in the background).
		if(!ourScreenFront && !pkg.equals(s.foregroundPackage())
			&& AppResolver.launch(s,pkg) && waitForeground(s,pkg,1500)) return opened(s,label,"service launch");

		// A slow cold start may have finished while we were deciding: never double-launch.
		if(waitForeground(s,pkg,300)) return opened(s,label,"direct launch");

		// 3. Through the launcher.
		EcclesResult viaLauncher= openViaLauncher(s,pkg,label);
		if(viaLauncher.ok) return viaLauncher;
		return EcclesResult.fail("Could not open " + label + ": Android blocked the direct launch, and " + viaLauncher.detail);
	}

	private EcclesResult opened(EcclesAccessibilityService s,String label,String how){
		s.awaitIdle(350,2500); // let the splash settle so the next observation is the real screen
		return EcclesResult.ok("opened " + label + " (" + how + ")");
	}

	private boolean waitForeground(EcclesAccessibilityService s,String pkg,long maxMs){
		long end= SystemClock.uptimeMillis() + maxMs;
		do{
			if(pkg.equals(s.foregroundPackage())) return true;
			SystemClock.sleep(150);
		} while(SystemClock.uptimeMillis() < end);
		return false;
	}

	/**
	 * Home, then find the icon by its label in the launcher's own accessibility tree. This is read and
	 * used locally only; nothing from the launcher is sent to the AI. If the icon is not on the home
	 * screen, open the app drawer with an upward swipe and page through it until the list stops changing.
	 */
	private EcclesResult openViaLauncher(EcclesAccessibilityService s,String pkg,String label){
		s.globalKey(EcclesAction.KEY_HOME);
		String home= AppResolver.homePackage(ctx);
		if(home != null) waitForeground(s,home,2500);
		s.awaitIdle(400,2500);

		int[] size= s.displaySize();
		int lastSig= 0;
		for(int page= 0;page < 9;page++){
			ScreenSnapshot snap= s.snapshot();
			if(snap == null){
				SystemClock.sleep(300);
				continue;
			}
			if(home != null && !home.equals(snap.packageName)){
				return EcclesResult.fail("the launcher was not in front (" + snap.packageName + ").");
			}
			ScreenNode icon= snap.resolve(-1,label,label,null);
			if(icon != null && !icon.password){
				s.clickNodeVerified(icon);
				if(waitForeground(s,pkg,2500)) return opened(s,label,"launcher icon");
				// the node would not take the click: touch the icon instead
				s.tap(icon.centerX(),icon.centerY(),AccessExecutor.TAP_MS);
				if(waitForeground(s,pkg,3000)) return opened(s,label,"launcher icon, touched");
				return EcclesResult.fail("tapping the icon did not open it.");
			}
			int sig= snap.toPrompt().hashCode();
			if(page >= 2 && sig == lastSig) break; // the drawer stopped scrolling: end of the list
			lastSig= sig;
			if(page == 0){
				// not on the home screen: open the app drawer (start above the gesture-navigation zone)
				s.swipe(size[0] / 2,(int) (size[1] * 0.80),size[0] / 2,(int) (size[1] * 0.25),350);
			} else if(!s.scrollNode("down")){
				s.scroll("down");
			}
			s.awaitIdle(400,2000);
		}
		return EcclesResult.fail("its icon was not found in the launcher.");
	}

	/** Finds the node the model meant: the snapshot it saw first, then a fresh one. */
	public ScreenNode resolveNode(EcclesAction a){
		EcclesAccessibilityService s= svc();
		if(s == null) return null;
		ScreenSnapshot snap= s.lastSnapshot();
		ScreenNode n= snap == null ? null : snap.resolve(a.index,a.targetText,a.targetDesc,a.targetId);
		if(n == null){
			snap= s.snapshot();
			if(snap != null) n= snap.resolve(a.index,a.targetText,a.targetDesc,a.targetId);
		}
		return n;
	}

	private static final String GESTURE_FAILED= "The gesture was cancelled or could not be dispatched.";

	/**
	 * Click an element. Order: the node's own accessibility action first (no coordinates, immune to
	 * popups and scaling), then a real touch gesture at its centre if the node cannot be clicked.
	 * If the action was accepted but the screen did not react, it is reported rather than retried by
	 * gesture, because a blind retry could double-activate (see {@link EcclesPolicy}).
	 */
	private EcclesResult click(EcclesAccessibilityService s,EcclesAction a){
		ScreenNode n= resolveNode(a);
		if(n == null) return EcclesResult.fail("No element matched " + a.targetLabel() + " on the current screen.");
		if(n.password) return EcclesResult.fail("Refused: that is a password field.");
		switch(EcclesPolicy.afterNodeAction(s.clickNodeVerified(n))){
			case DONE: return EcclesResult.ok("clicked (accessibility action)");
			case REPLAN: return EcclesResult.fail(EcclesPolicy.NO_CHANGE_MESSAGE);
			default:
				return s.tap(n.centerX(),n.centerY(),TAP_MS)
					? EcclesResult.ok("tapped at " + n.centerX() + "," + n.centerY() + " (gesture; the element had no click action)")
					: EcclesResult.fail(GESTURE_FAILED);
		}
	}

	/**
	 * A tap at coordinates (typically from a screenshot). If a clickable element is under that point
	 * the accessibility action is used on it; otherwise (canvas, games, web content) a real touch of
	 * the requested duration. Longer presses use the long-click action first.
	 */
	private EcclesResult tapAt(EcclesAccessibilityService s,EcclesAction a){
		boolean held= EcclesPolicy.isLongPress(a.durationMs);
		if(EcclesPolicy.isTap(a.durationMs) || held){
			switch(EcclesPolicy.afterNodeAction(s.clickAtVerified(a.x,a.y,held))){
				case DONE: return EcclesResult.ok((held ? "long-clicked" : "clicked") + " the element at " + a.x + "," + a.y + " (accessibility action)");
				case REPLAN: return EcclesResult.fail(EcclesPolicy.NO_CHANGE_MESSAGE);
				default: break; // nothing clickable there: touch it
			}
		}
		return s.tap(a.x,a.y,a.durationMs) ? EcclesResult.ok("touched (gesture)") : EcclesResult.fail(GESTURE_FAILED);
	}

	private EcclesResult longPress(EcclesAccessibilityService s,EcclesAction a){
		if(a.hasPoint()) return tapAt(s,a); // durations are clamped to >= 400 ms by the parser
		ScreenNode n= resolveNode(a);
		if(n == null) return EcclesResult.fail("No element matched " + a.targetLabel() + ".");
		switch(EcclesPolicy.afterNodeAction(s.longClickNodeVerified(n))){
			case DONE: return EcclesResult.ok("long-clicked (accessibility action)");
			case REPLAN: return EcclesResult.fail(EcclesPolicy.NO_CHANGE_MESSAGE);
			default:
				return s.tap(n.centerX(),n.centerY(),a.durationMs)
					? EcclesResult.ok("long-pressed (gesture)") : EcclesResult.fail(GESTURE_FAILED);
		}
	}

	/** Scroll the list's own scroll action first, a swipe gesture if there is none or nothing moved. */
	private EcclesResult scroll(EcclesAccessibilityService s,EcclesAction a){
		if(s.scrollNode(a.direction)) return EcclesResult.ok("scrolled (accessibility action)");
		return s.scroll(a.direction) ? EcclesResult.ok("scrolled (swipe gesture)") : EcclesResult.fail("The scroll gesture failed.");
	}

	private EcclesResult type(EcclesAccessibilityService s,EcclesAction a){
		ScreenNode n= a.hasTarget() ? resolveNode(a) : null;
		if(a.hasTarget() && n == null) return EcclesResult.fail("No text field matched " + a.targetLabel() + ".");
		return s.setText(n,a.text,a.append);
	}

	@Override
	public String foregroundPackage(){
		EcclesAccessibilityService s= svc();
		return s == null ? null : s.foregroundPackage();
	}

	@Override
	public Bitmap capture(){
		EcclesAccessibilityService s= svc();
		return s == null ? null : s.screenshot();
	}

	@Override
	public ScreenSnapshot snapshot(){
		EcclesAccessibilityService s= svc();
		return s == null ? null : s.snapshot();
	}

	public void awaitIdle(long maxMs){
		EcclesAccessibilityService s= svc();
		if(s != null) s.awaitIdle(350,maxMs);
		else SystemClock.sleep(Math.min(maxMs,500));
	}
}
