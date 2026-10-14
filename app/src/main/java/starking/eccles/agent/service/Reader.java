package starking.eccles.agent.service;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import starking.eccles.access.ScreenSnapshot;
import starking.eccles.data.EcclesStorage;
import starking.eccles.engine.EcclesRouter;
import starking.eccles.guard.EcclesGuard;

/**
 * Builds what the model gets to see each step: the text view of the screen and, when needed, a
 * screenshot. This is where the privacy guarantee is enforced: the foreground app is cleared by
 * {@link EcclesGuard} BEFORE the tree is read or an image is captured, and checked again AFTER
 * capture (the user may have switched apps in between). If either check fails, nothing is returned.
 */
public class Reader {

	private static final String TAG= "Reader";
	private static final int MAX_IMAGE_DIM= 1280;
	private static final int JPEG_QUALITY= 70;

	public static class Observation {
		public String pkg= "";
		public String label= "";
		public String screenText= "";
		public byte[] jpeg;
		/** Size of the space the model works in (image size if an image is attached, else screen size). */
		public int obsW,obsH;
		/** Observation pixels per real screen pixel (1.0 without an image). */
		public double scale= 1.0;
		public String note= "";
		/** Set when the guard blocked the foreground app: nothing was read. */
		public String blockedReason;
		/** Set when a password field is on screen and the user chose to stop there. */
		public boolean sensitiveScreen;
		public boolean unknownForeground;
		/** Eccles Agent's own screen is in front: a neutral launch pad, nothing is read or captured. */
		public boolean selfInFront;
	}

	private final Context ctx;
	private final EcclesRouter router;

	public Reader(Context c,EcclesRouter router){
		this.ctx= c.getApplicationContext();
		this.router= router;
	}

	public Observation observe(boolean wantImage){
		Observation o= new Observation();
		String vision= EcclesStorage.visionMode(ctx);

		String pkg= router.foregroundPackage();
		if(pkg == null){
			o.unknownForeground= true;
			o.screenText= "(The screen is not readable right now: Android did not report which app is in front. "
				+ "You may open an app, press a key, or wait.)";
			return o;
		}
		o.pkg= pkg;
		if(pkg.equals(ctx.getPackageName())){
			// The task was just started from the Eccles console. That screen is never read or
			// operated; the agent may only navigate away from it (open an app, press home).
			o.selfInFront= true;
			o.label= "Eccles Agent";
			o.screenText= "(Eccles Agent's own screen is in front and cannot be read or operated. "
				+ "Use open_app to start the app the task needs, or press home.)";
			return o;
		}
		EcclesGuard.Decision d= EcclesGuard.check(ctx,pkg);
		o.label= d.label;
		if(d.blocked){
			o.blockedReason= d.reason;
			return o;
		}

		ScreenSnapshot snap= router.snapshot();
		int screenW= 0,screenH= 0;
		if(snap != null){
			screenW= snap.screenWidth;
			screenH= snap.screenHeight;
			if(snap.hasPassword && EcclesStorage.blockPasswordScreens(ctx)){
				o.sensitiveScreen= true;
				return o;
			}
			if(snap.looksSparse()){
				wantImage= true;
				o.note= "The element list is nearly empty (a game, canvas or web view?), so an image is attached.";
			}
		} else {
			wantImage= true; // ADB-only: there is no tree, the image is the only way to see
		}
		if(vision.equals(EcclesStorage.VISION_ALWAYS)) wantImage= true;
		if(vision.equals(EcclesStorage.VISION_NEVER) && snap != null) wantImage= false;

		if(wantImage){
			Bitmap full= router.capture();
			if(full != null){
				try{
					// The user may have switched apps while we captured: re-verify before keeping the image.
					String after= router.foregroundPackage();
					if(after == null || !after.equals(pkg)){
						o.note= "The screen changed while capturing, so no image was attached.";
						if(after != null){
							EcclesGuard.Decision d2= EcclesGuard.check(ctx,after);
							if(d2.blocked){
								o.pkg= after;
								o.label= d2.label;
								o.blockedReason= d2.reason;
								return o;
							}
						}
					} else {
						if(screenW == 0){
							screenW= full.getWidth();
							screenH= full.getHeight();
						}
						encode(full,o,screenW,screenH);
					}
				} finally {
					full.recycle();
				}
			} else {
				o.note= "A screenshot could not be captured" + (snap == null ? "; the agent cannot see this screen." : ".");
			}
		}

		if(snap != null){
			o.screenText= snap.toPrompt(o.scale);
			if(o.obsW == 0){
				o.obsW= snap.screenWidth;
				o.obsH= snap.screenHeight;
			}
		} else {
			o.screenText= "(No accessibility tree is available; rely on the IMAGE.)";
			if(o.obsW == 0){
				o.obsW= screenW;
				o.obsH= screenH;
			}
		}
		return o;
	}

	private void encode(Bitmap full,Observation o,int screenW,int screenH){
		int w= full.getWidth(),h= full.getHeight();
		double down= Math.min(1.0,(double) MAX_IMAGE_DIM / Math.max(w,h));
		Bitmap scaled= down < 1.0 ? Bitmap.createScaledBitmap(full,(int) Math.round(w * down),(int) Math.round(h * down),true) : full;
		try{
			ByteArrayOutputStream bo= new ByteArrayOutputStream();
			scaled.compress(Bitmap.CompressFormat.JPEG,JPEG_QUALITY,bo);
			o.jpeg= bo.toByteArray();
			o.obsW= scaled.getWidth();
			o.obsH= scaled.getHeight();
			o.scale= screenW > 0 ? (double) o.obsW / screenW : 1.0;
			Log.d(TAG,"image " + o.obsW + "x" + o.obsH + " " + o.jpeg.length / 1024 + " KB scale=" + o.scale);
		} finally {
			if(scaled != full) scaled.recycle();
		}
	}
}
