package starking.eccles.agent.service;

import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.ServiceCompat;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.access.EcclesAccessibilityService;
import starking.eccles.access.EcclesOverlay;
import starking.eccles.agent.EcclesApplication;
import starking.eccles.ai.EcclesProvider;
import starking.eccles.data.EcclesStorage;
import starking.eccles.engine.EcclesEngine;
import starking.eccles.engine.EcclesRouter;
import starking.eccles.util.EcclesNotifier;

/**
 * Foreground service that hosts one agent task. A foreground service (with a Stop action in its
 * notification) keeps the task alive while the user is inside the app being automated, and makes
 * it impossible for the agent to run invisibly.
 */
public class Listener extends EcclesService {

	public static final String ACTION_RUN= "starking.eccles.agent.RUN";
	public static final String EXTRA_GOAL= "goal";
	private static final String TAG= "Listener";

	@Override
	public int onStartCommand(Intent intent,int flags,final int startId){
		final EcclesApplication app= (EcclesApplication) getApplication();

		// startForegroundService() obliges us to call startForeground() quickly, whatever happens next.
		Notification n= EcclesNotifier.ongoing(this,"Starting…");
		int type= Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
		ServiceCompat.startForeground(this,EcclesNotifier.ID_TASK,n,type);

		final String goal= intent == null ? null : intent.getStringExtra(EXTRA_GOAL);
		if(intent == null || !ACTION_RUN.equals(intent.getAction()) || goal == null || goal.trim().isEmpty()
			|| !app.tryBeginTask()){
			ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);
			stopSelf(startId);
			return START_NOT_STICKY;
		}
		Thread t= new Thread(() -> runTask(app,goal.trim()),"eccles-agent");
		t.start();
		return START_NOT_STICKY;
	}

	private void runTask(final EcclesApplication app,String goal){
		EcclesEngine.Result res= null;
		long historyId= -1;
		EcclesOverlay overlay= null;
		try{
			app.clearTimeline();
			Caller caller;
			try{
				caller= new Caller(this);
			} catch (EcclesProvider.AiException e){
				app.postEntry(EcclesPojo.task(goal));
				app.postEntry(EcclesPojo.result(EcclesPojo.FAIL,"Could not start",e.getMessage()));
				res= new EcclesEngine.Result(EcclesEngine.Outcome.FAILED,e.getMessage(),0);
				return;
			}
			if(app.data != null) historyId= app.data.begin(goal,caller.providerId());

			EcclesRouter router= EcclesRouter.create(this);
			Reader reader= new Reader(this,router);

			EcclesAccessibilityService svc= EcclesAccessibilityService.get();
			if(svc != null && EcclesStorage.showOverlay(this)) overlay= svc.overlay();
			final EcclesOverlay ov= overlay;
			if(ov != null) ov.show("Eccles is working…");

			EcclesEngine engine= new EcclesEngine(this,router,reader,caller,new EcclesEngine.Callback(){
				@Override public void onEntry(EcclesPojo p){
					app.postEntry(p);
				}
				@Override public void onStatus(String text){
					if(ov != null) ov.setStatus(text);
					EcclesNotifier.updateOngoing(Listener.this,text);
				}
				@Override public boolean onConfirm(String q){
					app.gate.open();
					if(ov != null) ov.showConfirm(q,() -> app.gate.resolve(true),() -> app.gate.resolve(false));
					EcclesNotifier.confirm(Listener.this,q);
					boolean ok= app.gate.await(60000);
					if(ov != null) ov.hideConfirm();
					EcclesNotifier.cancelConfirm(Listener.this);
					return ok;
				}
			});
			app.engine= engine;
			CaptureService.beginTask(); // frames only flow while a task runs
			res= engine.run(goal);
		} catch (Throwable t){
			Log.e(TAG,"Task crashed",t);
			String m= "Something went wrong: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " - " + t.getMessage());
			app.postEntry(EcclesPojo.result(EcclesPojo.FAIL,"Could not finish",m));
			res= new EcclesEngine.Result(EcclesEngine.Outcome.FAILED,m,0);
		} finally {
			CaptureService.endTask();
			if(overlay != null){
				final EcclesOverlay ov2= overlay;
				ov2.setStatus(res == null ? "Stopped" : title(res.outcome));
				new Handler(Looper.getMainLooper()).postDelayed(ov2::hide,2500);
			}
			if(app.data != null && historyId >= 0 && res != null){
				app.data.finish(historyId,res.outcome.name().toLowerCase(),res.message,res.steps);
			}
			if(res != null && app.timelineListener == null){
				EcclesNotifier.result(this,title(res.outcome),res.message);
			}
			app.endTask();
			ServiceCompat.stopForeground(this,ServiceCompat.STOP_FOREGROUND_REMOVE);
			stopSelf();
		}
	}

	private static String title(EcclesEngine.Outcome o){
		switch(o){
			case SUCCESS: return "Done";
			case CANCELLED: return "Stopped";
			case BLOCKED: return "Stopped for your protection";
			case NEEDS_INPUT: return "Needs your input";
			default: return "Could not finish";
		}
	}
}
