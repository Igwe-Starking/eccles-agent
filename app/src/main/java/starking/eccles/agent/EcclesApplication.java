package starking.eccles.agent;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.appcompat.app.AppCompatDelegate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.data.EcclesData;
import starking.eccles.data.EcclesStorage;
import starking.eccles.engine.EcclesEngine;
import starking.eccles.engine.EcclesGate;
import starking.eccles.guard.EcclesGuard;
import starking.eccles.util.EcclesNotifier;

/**
 * Process-wide state: the history database, the live task timeline, the running engine and the
 * confirmation gate. The timeline lives here (not in the activity) so a task keeps its log
 * while the user is inside another app, and the console can re-attach to it afterwards.
 */
public class EcclesApplication extends Application {

	public static final String TAG= "EcclesApplication";
	private static final int MAX_TIMELINE= 300;

	public volatile boolean databaseOpen;
	public volatile EcclesData data;

	public final EcclesGate gate= new EcclesGate();
	public volatile EcclesEngine engine;

	public final List<EcclesPojo> timeline= new CopyOnWriteArrayList<>();
	private final AtomicBoolean running= new AtomicBoolean(false);
	private final Handler main= new Handler(Looper.getMainLooper());

	/** Set by the console while it is on screen; null otherwise. Always invoked on the main thread. */
	public interface TimelineListener {
		void onEntry(EcclesPojo p,boolean added);
		void onCleared();
		void onRunState(boolean running);
	}

	public volatile TimelineListener timelineListener;

	@Override
	public void onCreate(){
		super.onCreate();
		EcclesNotifier.createChannels(this);
		EcclesGuard.seedIfNeeded(this);
		applyTheme(EcclesStorage.prefs(this).getString(EcclesStorage.KEY_THEME,"system"));
		// Opened off the main thread; MainActivity (splash) waits for databaseOpen, like Bluechat.
		new Thread(this::openDataBase,"eccles-db").start();
	}

	public void openDataBase(){
		try{
			EcclesData d= new EcclesData(this);
			d.getWritableDatabase();
			data= d;
			databaseOpen= true;
		} catch (Exception e){
			Log.e(TAG,"Could not open the history database",e);
			databaseOpen= true; // history is optional: never block the app on it
		}
	}

	public static void applyTheme(String mode){
		switch(mode == null ? "system" : mode){
			case "light": AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO); break;
			case "dark": AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES); break;
			default: AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
		}
	}

	// ---- task state ----------------------------------------------------------------------

	public boolean isRunning(){
		return running.get();
	}

	/** Atomically claims the single task slot. */
	public boolean tryBeginTask(){
		boolean ok= running.compareAndSet(false,true);
		if(ok) main.post(() -> { TimelineListener l= timelineListener; if(l != null) l.onRunState(true); });
		return ok;
	}

	public void endTask(){
		engine= null;
		running.set(false);
		main.post(() -> { TimelineListener l= timelineListener; if(l != null) l.onRunState(false); });
	}

	public void cancelTask(){
		EcclesEngine e= engine;
		if(e != null) e.cancel();
		gate.resolve(false);
	}

	public void clearTimeline(){
		timeline.clear();
		main.post(() -> { TimelineListener l= timelineListener; if(l != null) l.onCleared(); });
	}

	/** Adds the entry, or refreshes it if this same object was posted before. Safe from any thread. */
	public void postEntry(final EcclesPojo p){
		final boolean added;
		synchronized(timeline){
			added= !timeline.contains(p);
			if(added){
				timeline.add(p);
				while(timeline.size() > MAX_TIMELINE) timeline.remove(0);
			}
		}
		main.post(() -> { TimelineListener l= timelineListener; if(l != null) l.onEntry(p,added); });
	}
}
