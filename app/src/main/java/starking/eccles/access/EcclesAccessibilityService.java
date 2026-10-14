package starking.eccles.access;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import starking.eccles.engine.EcclesAction;
import starking.eccles.engine.EcclesPolicy;
import starking.eccles.engine.EcclesResult;

/**
 * The agent's hands and eyes. Everything here is callable from the engine's worker thread:
 * AccessibilityNodeInfo calls are binder IPC and safe off the main thread, and gesture/screenshot
 * results are awaited on latches (their callbacks are delivered on the main thread).
 * <p>
 * Privacy rule enforced here as well as in the engine: {@link #snapshot()} and
 * {@link #screenshot()} are only ever invoked after the guard has cleared the foreground app;
 * this class never reads a tree on its own initiative.
 */
public class EcclesAccessibilityService extends AccessibilityService {

	private static final String TAG= "EcclesAccess";
	private static final int MAX_VISIT= 900;
	private static final int MAX_DEPTH= 45;

	private static volatile EcclesAccessibilityService instance;

	public static EcclesAccessibilityService get(){
		return instance;
	}

	private volatile long lastChange= SystemClock.uptimeMillis();
	private volatile ScreenSnapshot lastSnapshot;
	private volatile EcclesOverlay overlay;

	@Override
	protected void onServiceConnected(){
		super.onServiceConnected();
		instance= this;
		overlay= new EcclesOverlay(this);
		Log.i(TAG,"connected");
	}

	@Override
	public void onAccessibilityEvent(AccessibilityEvent e){
		if(e == null) return;
		CharSequence p= e.getPackageName();
		// Our own overlay updates would otherwise keep resetting the "UI is idle" timer.
		if(p != null && getPackageName().contentEquals(p)) return;
		lastChange= SystemClock.uptimeMillis();
	}

	@Override
	public void onInterrupt(){
	}

	@Override
	public boolean onUnbind(Intent intent){
		instance= null;
		if(overlay != null) overlay.destroy();
		return super.onUnbind(intent);
	}

	@Override
	public void onDestroy(){
		instance= null;
		super.onDestroy();
	}

	public EcclesOverlay overlay(){
		return overlay;
	}

	public ScreenSnapshot lastSnapshot(){
		return lastSnapshot;
	}

	/** Blocks until no window events arrived for quietMs, or maxMs elapsed. */
	public void awaitIdle(long quietMs,long maxMs){
		long end= SystemClock.uptimeMillis() + maxMs;
		while(SystemClock.uptimeMillis() < end){
			if(SystemClock.uptimeMillis() - lastChange >= quietMs) return;
			SystemClock.sleep(50);
		}
	}

	// ---- reading the screen ------------------------------------------------------------------

	private AccessibilityNodeInfo activeRoot(){
		AccessibilityNodeInfo root= getRootInActiveWindow();
		if(root != null) return root;
		try{
			List<AccessibilityWindowInfo> ws= getWindows();
			if(ws != null){
				for(AccessibilityWindowInfo w : ws){
					if(w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (w.isActive() || w.isFocused())){
						AccessibilityNodeInfo r= w.getRoot();
						if(r != null) return r;
					}
				}
			}
		} catch (Exception e){
			Log.w(TAG,"window fallback failed: " + e.getMessage());
		}
		return null;
	}

	/** Package of the app in front. Reads only the root's package name, never its content. */
	public String foregroundPackage(){
		AccessibilityNodeInfo root= activeRoot();
		if(root == null) return null;
		try{
			CharSequence p= root.getPackageName();
			return p == null ? null : p.toString();
		} finally {
			recycle(root);
		}
	}

	public ScreenSnapshot snapshot(){
		AccessibilityNodeInfo root= activeRoot();
		if(root == null) return null;
		try{
			CharSequence p= root.getPackageName();
			int[] size= screenSize();
			List<ScreenNode> raw= new ArrayList<>();
			walk(root,raw,0,new int[]{0});
			ScreenSnapshot s= ScreenSnapshot.build(p == null ? "" : p.toString(),size[0],size[1],raw,ScreenSnapshot.MAX_NODES);
			lastSnapshot= s;
			return s;
		} finally {
			recycle(root);
		}
	}

	private void walk(AccessibilityNodeInfo n,List<ScreenNode> out,int depth,int[] visited){
		if(n == null || depth > MAX_DEPTH || visited[0]++ > MAX_VISIT) return;
		if(!n.isVisibleToUser()) return; // prunes off-screen list rows and hidden panes
		ScreenNode sn= toNode(n);
		if(sn != null) out.add(sn);
		int cc= n.getChildCount();
		for(int i= 0;i < cc;i++){
			AccessibilityNodeInfo ch= n.getChild(i);
			if(ch == null) continue;
			try{
				walk(ch,out,depth + 1,visited);
			} finally {
				recycle(ch);
			}
		}
	}

	private ScreenNode toNode(AccessibilityNodeInfo n){
		Rect r= new Rect();
		n.getBoundsInScreen(r);
		if(r.width() <= 0 || r.height() <= 0) return null;

		ScreenNode s= new ScreenNode();
		s.cls= n.getClassName() == null ? "" : n.getClassName().toString();
		s.password= n.isPassword();
		// A password field's content must never leave the device, not even in the local snapshot.
		s.text= s.password || n.getText() == null ? "" : n.getText().toString();
		s.desc= n.getContentDescription() == null ? "" : n.getContentDescription().toString();
		String id= n.getViewIdResourceName();
		s.id= id == null ? "" : id;
		s.clickable= n.isClickable();
		s.longClickable= n.isLongClickable();
		s.editable= n.isEditable();
		s.scrollable= n.isScrollable();
		s.checkable= n.isCheckable();
		s.checked= n.isChecked();
		s.focused= n.isFocused();
		s.enabled= n.isEnabled();
		s.left= r.left; s.top= r.top; s.right= r.right; s.bottom= r.bottom;

		if(s.clickable && s.text.isEmpty() && s.desc.isEmpty() && !s.password){
			// A tappable container with no label of its own: borrow its children's text so the
			// model sees "Post" on the thing that is actually clickable.
			s.text= descendantText(n,3,new StringBuilder()).toString().trim();
		}
		boolean informative= !s.text.isEmpty() || !s.desc.isEmpty() || s.interactive() || s.password;
		return informative ? s : null;
	}

	private StringBuilder descendantText(AccessibilityNodeInfo n,int depth,StringBuilder sb){
		if(depth == 0 || sb.length() > 60) return sb;
		int cc= n.getChildCount();
		for(int i= 0;i < cc && sb.length() <= 60;i++){
			AccessibilityNodeInfo ch= n.getChild(i);
			if(ch == null) continue;
			try{
				if(!ch.isPassword() && ch.getText() != null && ch.getText().length() > 0){
					if(sb.length() > 0) sb.append(' ');
					sb.append(ch.getText());
				}
				descendantText(ch,depth - 1,sb);
			} finally {
				recycle(ch);
			}
		}
		return sb;
	}

	public int[] displaySize(){
		return screenSize();
	}

	private int[] screenSize(){
		WindowManager wm= (WindowManager) getSystemService(Context.WINDOW_SERVICE);
		if(Build.VERSION.SDK_INT >= 30){
			Rect b= wm.getMaximumWindowMetrics().getBounds();
			return new int[]{b.width(),b.height()};
		}
		DisplayMetrics dm= new DisplayMetrics();
		wm.getDefaultDisplay().getRealMetrics(dm);
		return new int[]{dm.widthPixels,dm.heightPixels};
	}

	// ---- acting ----------------------------------------------------------------------------------

	/** Re-finds a node from an earlier snapshot in the live tree and clicks it (or its clickable ancestor). */
	public boolean clickNode(ScreenNode target){
		AccessibilityNodeInfo live= findLive(target);
		if(live == null) return false;
		try{
			AccessibilityNodeInfo cur= live;
			for(int i= 0;i < 7 && cur != null;i++){
				if(cur.isClickable() && cur.isEnabled()){
					boolean ok= cur.performAction(AccessibilityNodeInfo.ACTION_CLICK);
					if(cur != live) recycle(cur);
					return ok;
				}
				AccessibilityNodeInfo parent= cur.getParent();
				if(cur != live) recycle(cur);
				cur= parent;
			}
			return false;
		} finally {
			recycle(live);
		}
	}

	private AccessibilityNodeInfo findLive(ScreenNode target){
		AccessibilityNodeInfo root= activeRoot();
		if(root == null) return null;
		try{
			int[] bestScore= {0};
			List<Integer> bestPath= new ArrayList<>();
			findBest(root,target,new ArrayList<Integer>(),bestScore,bestPath,0,new int[]{0});
			if(bestScore[0] < 5) return null;
			AccessibilityNodeInfo cur= root;
			AccessibilityNodeInfo hold= null;
			for(int idx : bestPath){
				AccessibilityNodeInfo ch= cur.getChild(idx);
				if(hold != null) recycle(hold);
				if(ch == null) return null;
				hold= ch;
				cur= ch;
			}
			return hold == null ? obtainCopy(root) : hold;
		} finally {
			recycle(root);
		}
	}

	@SuppressWarnings("deprecation")
	private static AccessibilityNodeInfo obtainCopy(AccessibilityNodeInfo n){
		return AccessibilityNodeInfo.obtain(n);
	}

	private void findBest(AccessibilityNodeInfo n,ScreenNode target,List<Integer> path,int[] bestScore,
		List<Integer> bestPath,int depth,int[] visited){
		if(n == null || depth > MAX_DEPTH || visited[0]++ > MAX_VISIT) return;
		if(!n.isVisibleToUser()) return;
		ScreenNode cand= toNodeRaw(n);
		if(cand != null){
			int sc= target.similarity(cand);
			if(sc > bestScore[0]){
				bestScore[0]= sc;
				bestPath.clear();
				bestPath.addAll(path);
			}
		}
		int cc= n.getChildCount();
		for(int i= 0;i < cc;i++){
			AccessibilityNodeInfo ch= n.getChild(i);
			if(ch == null) continue;
			try{
				path.add(i);
				findBest(ch,target,path,bestScore,bestPath,depth + 1,visited);
				path.remove(path.size() - 1);
			} finally {
				recycle(ch);
			}
		}
	}

	/** Like toNode but without the "informative" filter (the target may be an unlabelled container). */
	private ScreenNode toNodeRaw(AccessibilityNodeInfo n){
		Rect r= new Rect();
		n.getBoundsInScreen(r);
		if(r.width() <= 0 || r.height() <= 0) return null;
		ScreenNode s= new ScreenNode();
		s.cls= n.getClassName() == null ? "" : n.getClassName().toString();
		s.text= n.isPassword() || n.getText() == null ? "" : n.getText().toString();
		s.desc= n.getContentDescription() == null ? "" : n.getContentDescription().toString();
		String id= n.getViewIdResourceName();
		s.id= id == null ? "" : id;
		s.left= r.left; s.top= r.top; s.right= r.right; s.bottom= r.bottom;
		return s;
	}

	// ---- node-first actions, with proof that something happened ---------------------------------

	/** Waits until a window event newer than {@code since} (uptime ms) arrives. */
	public boolean awaitChangeAfter(long since,long maxMs){
		long end= SystemClock.uptimeMillis() + maxMs;
		while(SystemClock.uptimeMillis() < end){
			if(lastChange > since) return true;
			SystemClock.sleep(40);
		}
		return lastChange > since;
	}

	/** Performs the action on the node or its nearest ancestor that supports it. */
	private boolean performOnNodeOrAncestor(AccessibilityNodeInfo start,int action,boolean longClick){
		AccessibilityNodeInfo cur= start;
		for(int i= 0;i < 7 && cur != null;i++){
			boolean supports= longClick ? cur.isLongClickable() : cur.isClickable();
			if(supports && cur.isEnabled()){
				boolean ok= cur.performAction(action);
				if(cur != start) recycle(cur);
				return ok;
			}
			AccessibilityNodeInfo parent= cur.getParent();
			if(cur != start) recycle(cur);
			cur= parent;
		}
		return false;
	}

	/** ACTION_CLICK on a known element, judged by whether the UI reacted (see EcclesPolicy). */
	public int clickNodeVerified(ScreenNode target){
		AccessibilityNodeInfo live= findLive(target);
		if(live == null) return EcclesPolicy.UNAVAILABLE;
		try{
			long before= SystemClock.uptimeMillis();
			if(!performOnNodeOrAncestor(live,AccessibilityNodeInfo.ACTION_CLICK,false)) return EcclesPolicy.UNAVAILABLE;
			return awaitChangeAfter(before,1200) ? EcclesPolicy.CHANGED : EcclesPolicy.NO_CHANGE;
		} finally {
			recycle(live);
		}
	}

	public int longClickNodeVerified(ScreenNode target){
		AccessibilityNodeInfo live= findLive(target);
		if(live == null) return EcclesPolicy.UNAVAILABLE;
		try{
			long before= SystemClock.uptimeMillis();
			if(!performOnNodeOrAncestor(live,AccessibilityNodeInfo.ACTION_LONG_CLICK,true)) return EcclesPolicy.UNAVAILABLE;
			return awaitChangeAfter(before,1200) ? EcclesPolicy.CHANGED : EcclesPolicy.NO_CHANGE;
		} finally {
			recycle(live);
		}
	}

	/**
	 * For a coordinate the model chose from a screenshot: find the smallest clickable (or
	 * long-clickable) element under that point and act on it as a node. Returns UNAVAILABLE when
	 * nothing there is clickable (canvas, games, web content), which sends the caller to a gesture.
	 */
	public int clickAtVerified(int x,int y,boolean longClick){
		AccessibilityNodeInfo root= activeRoot();
		if(root == null) return EcclesPolicy.UNAVAILABLE;
		AccessibilityNodeInfo live= null;
		try{
			long[] bestArea= {Long.MAX_VALUE};
			List<Integer> bestPath= new ArrayList<>();
			boolean[] found= {false};
			findAt(root,x,y,longClick,new ArrayList<Integer>(),bestArea,bestPath,found,0,new int[]{0});
			if(!found[0]) return EcclesPolicy.UNAVAILABLE;
			live= navigate(root,bestPath);
			if(live == null) return EcclesPolicy.UNAVAILABLE;
			long before= SystemClock.uptimeMillis();
			boolean ok= live.performAction(longClick ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
			if(!ok) return EcclesPolicy.UNAVAILABLE;
			return awaitChangeAfter(before,1200) ? EcclesPolicy.CHANGED : EcclesPolicy.NO_CHANGE;
		} finally {
			recycle(live);
			recycle(root);
		}
	}

	private void findAt(AccessibilityNodeInfo n,int x,int y,boolean longClick,List<Integer> path,long[] bestArea,
		List<Integer> bestPath,boolean[] found,int depth,int[] visited){
		if(n == null || depth > MAX_DEPTH || visited[0]++ > MAX_VISIT) return;
		if(!n.isVisibleToUser()) return;
		Rect r= new Rect();
		n.getBoundsInScreen(r);
		boolean can= longClick ? n.isLongClickable() : n.isClickable();
		if(can && n.isEnabled() && r.contains(x,y)){
			long area= (long) r.width() * r.height();
			if(area < bestArea[0]){
				bestArea[0]= area;
				bestPath.clear();
				bestPath.addAll(path);
				found[0]= true;
			}
		}
		int cc= n.getChildCount();
		for(int i= 0;i < cc;i++){
			AccessibilityNodeInfo ch= n.getChild(i);
			if(ch == null) continue;
			try{
				path.add(i);
				findAt(ch,x,y,longClick,path,bestArea,bestPath,found,depth + 1,visited);
				path.remove(path.size() - 1);
			} finally {
				recycle(ch);
			}
		}
	}

	/** Follows child indexes from the root; the returned node is the caller's to recycle. */
	private AccessibilityNodeInfo navigate(AccessibilityNodeInfo root,List<Integer> path){
		AccessibilityNodeInfo cur= root;
		AccessibilityNodeInfo hold= null;
		for(int idx : path){
			AccessibilityNodeInfo ch= cur.getChild(idx);
			if(hold != null) recycle(hold);
			if(ch == null) return null;
			hold= ch;
			cur= ch;
		}
		return hold == null ? obtainCopy(root) : hold;
	}

	/**
	 * Scrolls the largest visible scrollable element that supports the direction, using its own
	 * scroll action. Returns false if there is none or nothing reacted, so the caller can swipe.
	 */
	public boolean scrollNode(String dir){
		int specific,generic;
		switch(dir){
			case "up": specific= AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.getId(); break;
			case "left": specific= AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId(); break;
			case "right": specific= AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId(); break;
			default: specific= AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId();
		}
		generic= EcclesPolicy.scrollsForward(dir) ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;

		AccessibilityNodeInfo root= activeRoot();
		if(root == null) return false;
		AccessibilityNodeInfo live= null;
		try{
			long[] bestArea= {-1};
			List<Integer> bestPath= new ArrayList<>();
			findScrollable(root,specific,generic,new ArrayList<Integer>(),bestArea,bestPath,0,new int[]{0});
			if(bestArea[0] < 0) return false;
			live= navigate(root,bestPath);
			if(live == null) return false;
			long before= SystemClock.uptimeMillis();
			boolean ok= live.performAction(specific) || live.performAction(generic);
			return ok && awaitChangeAfter(before,1000);
		} finally {
			recycle(live);
			recycle(root);
		}
	}

	private void findScrollable(AccessibilityNodeInfo n,int specific,int generic,List<Integer> path,long[] bestArea,
		List<Integer> bestPath,int depth,int[] visited){
		if(n == null || depth > MAX_DEPTH || visited[0]++ > MAX_VISIT) return;
		if(!n.isVisibleToUser()) return;
		if(n.isScrollable() && (hasAction(n,specific) || hasAction(n,generic))){
			Rect r= new Rect();
			n.getBoundsInScreen(r);
			long area= (long) r.width() * r.height();
			if(area > bestArea[0]){
				bestArea[0]= area;
				bestPath.clear();
				bestPath.addAll(path);
			}
		}
		int cc= n.getChildCount();
		for(int i= 0;i < cc;i++){
			AccessibilityNodeInfo ch= n.getChild(i);
			if(ch == null) continue;
			try{
				path.add(i);
				findScrollable(ch,specific,generic,path,bestArea,bestPath,depth + 1,visited);
				path.remove(path.size() - 1);
			} finally {
				recycle(ch);
			}
		}
	}

	private static boolean hasAction(AccessibilityNodeInfo n,int id){
		List<AccessibilityNodeInfo.AccessibilityAction> list= n.getActionList();
		if(list == null) return false;
		for(AccessibilityNodeInfo.AccessibilityAction a : list) if(a.getId() == id) return true;
		return false;
	}

	public EcclesResult setText(ScreenNode target,String text,boolean append){
		AccessibilityNodeInfo node= target == null ? null : findLive(target);
		AccessibilityNodeInfo root= null;
		try{
			if(node == null){
				root= activeRoot();
				if(root != null){
					AccessibilityNodeInfo f= root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
					if(f != null && f.isEditable()) node= f;
					else if(f != null) recycle(f);
				}
			}
			if(node == null) return EcclesResult.fail("No text field is focused. Click the field first, then type.");
			if(node.isPassword()) return EcclesResult.fail("Refused: that is a password field.");

			node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
			String base= "";
			if(append && node.getText() != null && !node.isShowingHintText()) base= node.getText().toString();
			Bundle args= new Bundle();
			args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,base + text);
			if(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args)) return EcclesResult.ok("typed");

			// Some custom editors reject SET_TEXT; paste works on most of them.
			if(paste(node,base + text)) return EcclesResult.ok("typed via paste");
			return EcclesResult.fail("The field rejected the text.");
		} finally {
			recycle(node);
			recycle(root);
		}
	}

	private boolean paste(AccessibilityNodeInfo node,String text){
		try{
			ClipboardManager cm= (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip= ClipData.newPlainText("eccles",text);
			if(Build.VERSION.SDK_INT >= 33){
				PersistableBundle pb= new PersistableBundle();
				pb.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE,true); // hides it from the clipboard preview
				clip.getDescription().setExtras(pb);
			}
			cm.setPrimaryClip(clip);
			node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
			boolean ok= node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
			if(Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip();
			return ok;
		} catch (Exception e){
			Log.w(TAG,"paste fallback failed: " + e.getMessage());
			return false;
		}
	}

	public boolean tap(int x,int y,long durationMs){
		int[] s= screenSize();
		Path p= new Path();
		p.moveTo(clamp(x,s[0]),clamp(y,s[1]));
		return gesture(p,Math.max(1,durationMs));
	}

	public boolean swipe(int x1,int y1,int x2,int y2,long durationMs){
		int[] s= screenSize();
		Path p= new Path();
		p.moveTo(clamp(x1,s[0]),clamp(y1,s[1]));
		p.lineTo(clamp(x2,s[0]),clamp(y2,s[1]));
		return gesture(p,Math.max(50,durationMs));
	}

	public boolean scroll(String dir){
		int[] s= screenSize();
		int w= s[0],h= s[1];
		int x1= w / 2,y1= h / 2,x2= w / 2,y2= h / 2;
		switch(dir){
			case "up": y1= (int) (h * 0.3); y2= (int) (h * 0.7); break;
			case "left": x1= (int) (w * 0.2); x2= (int) (w * 0.8); break;
			case "right": x1= (int) (w * 0.8); x2= (int) (w * 0.2); break;
			default: y1= (int) (h * 0.7); y2= (int) (h * 0.3);
		}
		return swipe(x1,y1,x2,y2,350);
	}

	public boolean globalKey(String key){
		switch(key){
			case EcclesAction.KEY_BACK: return performGlobalAction(GLOBAL_ACTION_BACK);
			case EcclesAction.KEY_HOME: return performGlobalAction(GLOBAL_ACTION_HOME);
			case EcclesAction.KEY_RECENTS: return performGlobalAction(GLOBAL_ACTION_RECENTS);
			case EcclesAction.KEY_NOTIFICATIONS: return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
			case EcclesAction.KEY_QUICK_SETTINGS: return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
			default: return false;
		}
	}

	private static int clamp(int v,int max){
		return Math.max(0,Math.min(max - 1,v));
	}

	private boolean gesture(Path path,long durationMs){
		EcclesOverlay ov= overlay;
		if(ov != null) ov.setPassThrough(true);
		try{
			GestureDescription.Builder b= new GestureDescription.Builder();
			b.addStroke(new GestureDescription.StrokeDescription(path,0,durationMs));
			final CountDownLatch done= new CountDownLatch(1);
			final boolean[] ok= {false};
			boolean dispatched= dispatchGesture(b.build(),new GestureResultCallback(){
				@Override public void onCompleted(GestureDescription g){ ok[0]= true; done.countDown(); }
				@Override public void onCancelled(GestureDescription g){ done.countDown(); }
			},null);
			if(!dispatched) return false;
			try{
				done.await(durationMs + 3000,TimeUnit.MILLISECONDS);
			} catch (InterruptedException e){
				Thread.currentThread().interrupt();
				return false;
			}
			return ok[0];
		} finally {
			if(ov != null) ov.setPassThrough(false);
		}
	}

	// ---- screenshots ---------------------------------------------------------------------------------

	/**
	 * Full-resolution screenshot via the accessibility API (Android 11+). Returns null on older
	 * versions (the router then falls back to ADB), on secure windows, or on failure.
	 */
	public Bitmap screenshot(){
		if(Build.VERSION.SDK_INT < 30) return null;
		EcclesOverlay ov= overlay;
		if(ov != null) ov.setHiddenForCapture(true);
		try{
			for(int attempt= 0;attempt < 3;attempt++){
				final CountDownLatch done= new CountDownLatch(1);
				final Bitmap[] out= {null};
				final int[] err= {0};
				takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
					@Override public void onSuccess(ScreenshotResult r){
						try{
							HardwareBuffer hb= r.getHardwareBuffer();
							Bitmap hw= Bitmap.wrapHardwareBuffer(hb,r.getColorSpace());
							out[0]= hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888,false);
							hb.close();
						} catch (Exception e){
							Log.w(TAG,"screenshot decode failed: " + e.getMessage());
						} finally {
							done.countDown();
						}
					}
					@Override public void onFailure(int code){ err[0]= code; done.countDown(); }
				});
				try{
					done.await(5,TimeUnit.SECONDS);
				} catch (InterruptedException e){
					Thread.currentThread().interrupt();
					return null;
				}
				if(out[0] != null) return out[0];
				if(err[0] == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){
					SystemClock.sleep(1100); // the API allows about one screenshot per second
					continue;
				}
				Log.w(TAG,"takeScreenshot failed, code " + err[0]);
				return null;
			}
			return null;
		} finally {
			if(ov != null) ov.setHiddenForCapture(false);
		}
	}

	@SuppressWarnings("deprecation")
	private static void recycle(AccessibilityNodeInfo n){
		if(n != null && Build.VERSION.SDK_INT < 33) n.recycle();
	}
}
