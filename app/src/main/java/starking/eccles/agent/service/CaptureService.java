package starking.eccles.agent.service;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import java.nio.ByteBuffer;
import starking.eccles.util.EcclesNotifier;

/**
 * Screen capture through MediaProjection: the primary way the agent takes screenshots.
 * <p>
 * Lifecycle, and why it looks like this:
 * <ul>
 *   <li>The user grants capture once (system dialog). Android 14+ makes that grant single-use per
 *       MediaProjection, so one VirtualDisplay is created and kept for the whole session instead of
 *       asking again every task. It lives in this foreground service (type mediaProjection).</li>
 *   <li><b>Privacy:</b> the virtual display only has a surface attached while a task is running
 *       ({@link #beginTask()} / {@link #endTask()}). Between tasks no frames are produced at all.</li>
 *   <li>Frames are only ever <i>read</i> by {@link #capture()}, which the Reader calls after the
 *       excluded-apps guard has cleared the foreground app.</li>
 *   <li>If the system or the user stops the projection (onStop), everything is torn down and the
 *       router silently falls back to the accessibility screenshot, then ADB.</li>
 * </ul>
 */
public class CaptureService extends EcclesService {

	public static final String ACTION_START= "starking.eccles.agent.CAPTURE_START";
	public static final String ACTION_STOP= "starking.eccles.agent.CAPTURE_STOP";
	private static final String EXTRA_CODE= "code";
	private static final String EXTRA_DATA= "data";
	private static final String TAG= "CaptureService";
	private static final long FRAME_WAIT_MS= 1500;

	private static volatile CaptureService instance;

	// ---- static API (used by UI and the engine) --------------------------------------------

	public static boolean isActive(){
		CaptureService s= instance;
		return s != null && s.active;
	}

	/** The system consent dialog. On Android 14+ it is limited to the entire screen. */
	public static Intent consentIntent(Context c){
		MediaProjectionManager m= (MediaProjectionManager) c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
		if(Build.VERSION.SDK_INT >= 34) return m.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
		return m.createScreenCaptureIntent();
	}

	public static void start(Context c,int resultCode,Intent consent){
		Intent i= new Intent(c,CaptureService.class).setAction(ACTION_START)
			.putExtra(EXTRA_CODE,resultCode).putExtra(EXTRA_DATA,consent);
		ContextCompat.startForegroundService(c,i);
	}

	public static void stop(Context c){
		CaptureService s= instance;
		if(s != null) s.shutdown();
		else c.stopService(new Intent(c,CaptureService.class));
	}

	/** Attach the surface: frames flow while a task runs. Safe to call when capture is off. */
	public static void beginTask(){
		CaptureService s= instance;
		if(s != null) s.attach();
	}

	public static void endTask(){
		CaptureService s= instance;
		if(s != null) s.detach();
	}

	/** Latest full-resolution frame, or null (not active / no frame / blank, e.g. a secure window). */
	public static Bitmap capture(){
		CaptureService s= instance;
		return s == null ? null : s.grab();
	}

	// ---- instance state ----------------------------------------------------------------------

	private MediaProjection projection;
	private VirtualDisplay display;
	private ImageReader reader;
	private HandlerThread thread;
	private Handler handler;
	private int width,height,dpi;
	private volatile boolean active;
	private boolean attached;
	private Bitmap cache;
	private final Object frameLock= new Object();

	private final ImageReader.OnImageAvailableListener onFrame= r -> {
		synchronized(frameLock){
			frameLock.notifyAll();
		}
	};

	@Override
	public int onStartCommand(Intent intent,int flags,int startId){
		// startForegroundService() obliges an immediate startForeground(), on every path.
		Notification n= EcclesNotifier.capture(this);
		ServiceCompat.startForeground(this,EcclesNotifier.ID_CAPTURE,n,
			Build.VERSION.SDK_INT >= 29 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0);

		if(intent == null || !ACTION_START.equals(intent.getAction())){
			shutdown();
			return START_NOT_STICKY;
		}
		try{
			setup(intent.getIntExtra(EXTRA_CODE,0),(Intent) intent.getParcelableExtra(EXTRA_DATA));
		} catch (Exception e){
			Log.e(TAG,"Could not start screen capture",e);
			shutdown();
		}
		return START_NOT_STICKY;
	}

	private synchronized void setup(int code,Intent consent) throws Exception{
		if(consent == null) throw new IllegalStateException("No consent data");
		releaseAll();
		MediaProjectionManager m= (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
		projection= m.getMediaProjection(code,consent);
		if(projection == null) throw new IllegalStateException("MediaProjection was refused");

		thread= new HandlerThread("eccles-capture");
		thread.start();
		handler= new Handler(thread.getLooper());
		// Android 14 requires a callback to be registered BEFORE the virtual display is created.
		projection.registerCallback(new MediaProjection.Callback(){
			@Override public void onStop(){
				Log.i(TAG,"projection stopped by the system or the user");
				shutdown();
			}
		},handler);

		measure();
		reader= ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
		reader.setOnImageAvailableListener(onFrame,handler);
		// Created WITHOUT a surface: nothing is rendered until a task begins.
		display= projection.createVirtualDisplay("eccles-capture",width,height,dpi,
			DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,null,null,handler);
		instance= this;
		active= true;
		Log.i(TAG,"ready " + width + "x" + height + " @" + dpi);
	}

	private void measure(){
		WindowManager wm= (WindowManager) getSystemService(Context.WINDOW_SERVICE);
		if(Build.VERSION.SDK_INT >= 30){
			Rect b= wm.getMaximumWindowMetrics().getBounds();
			width= b.width();
			height= b.height();
		} else {
			DisplayMetrics dm= new DisplayMetrics();
			wm.getDefaultDisplay().getRealMetrics(dm);
			width= dm.widthPixels;
			height= dm.heightPixels;
		}
		dpi= getResources().getConfiguration().densityDpi;
	}

	private synchronized void attach(){
		if(!active || display == null) return;
		int w= width,h= height;
		measure();
		if(w != width || h != height){
			// Rotated or folded since the grant: rebuild the reader at the new size.
			reader.close();
			reader= ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);
			reader.setOnImageAvailableListener(onFrame,handler);
			display.resize(width,height,dpi);
		}
		clearCache();
		display.setSurface(reader.getSurface());
		attached= true;
	}

	private synchronized void detach(){
		if(display != null && attached){
			try{ display.setSurface(null); } catch (Exception ignored){ }
		}
		attached= false;
		clearCache();
	}

	private void clearCache(){
		if(cache != null){
			cache.recycle();
			cache= null;
		}
	}

	private Bitmap grab(){
		ImageReader rd;
		synchronized(this){
			if(!active || reader == null) return null;
			if(!attached) attach();
			rd= reader;
		}
		long end= SystemClock.uptimeMillis() + FRAME_WAIT_MS;
		while(true){
			Image img= null;
			try{
				img= rd.acquireLatestImage();
			} catch (IllegalStateException e){
				Log.w(TAG,"acquire failed: " + e.getMessage());
			}
			if(img != null){
				try{
					Bitmap b= toBitmap(img);
					synchronized(this){
						clearCache();
						cache= b;
					}
				} finally {
					img.close();
				}
				break;
			}
			// No NEW frame means the screen has not changed since the cached one (the compositor
			// emits a frame on every change), so the cached frame is still the current screen.
			synchronized(this){
				if(cache != null) break;
			}
			long left= end - SystemClock.uptimeMillis();
			if(left <= 0) return null;
			synchronized(frameLock){
				try{
					frameLock.wait(Math.min(left,100));
				} catch (InterruptedException e){
					Thread.currentThread().interrupt();
					return null;
				}
			}
		}
		synchronized(this){
			if(cache == null || isBlank(cache)) return null; // secure window (FLAG_SECURE) or nothing drawn
			return cache.copy(Bitmap.Config.ARGB_8888,false);
		}
	}

	/** RGBA_8888 rows can be padded; copy then crop to the real width. */
	private static Bitmap toBitmap(Image img){
		Image.Plane p= img.getPlanes()[0];
		ByteBuffer buf= p.getBuffer();
		int ps= p.getPixelStride();
		int rowPadding= p.getRowStride() - ps * img.getWidth();
		Bitmap padded= Bitmap.createBitmap(img.getWidth() + rowPadding / ps,img.getHeight(),Bitmap.Config.ARGB_8888);
		padded.copyPixelsFromBuffer(buf);
		if(rowPadding == 0) return padded;
		Bitmap exact= Bitmap.createBitmap(padded,0,0,img.getWidth(),img.getHeight());
		padded.recycle();
		return exact;
	}

	/** Secure windows are delivered as solid black. Sample a grid; any colour at all means a real frame. */
	static boolean isBlank(Bitmap b){
		int w= b.getWidth(),h= b.getHeight();
		for(int yi= 1;yi < 12;yi++){
			for(int xi= 1;xi < 12;xi++){
				if((b.getPixel(xi * w / 12,yi * h / 12) & 0x00FFFFFF) != 0) return false;
			}
		}
		return true;
	}

	private synchronized void releaseAll(){
		active= false;
		attached= false;
		clearCache();
		try{ if(display != null) display.release(); } catch (Exception ignored){ }
		try{ if(reader != null) reader.close(); } catch (Exception ignored){ }
		try{ if(projection != null) projection.stop(); } catch (Exception ignored){ }
		if(thread != null) thread.quitSafely();
		display= null;
		reader= null;
		projection= null;
		thread= null;
		handler= null;
		if(instance == this) instance= null;
	}

	private void shutdown(){
		releaseAll();
		ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	@Override
	public void onDestroy(){
		releaseAll();
		super.onDestroy();
	}
}
