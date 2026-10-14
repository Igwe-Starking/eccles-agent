package starking.eccles.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import starking.eccles.agent.EcclesApplication;

/** Handles the notification buttons: Stop, and Allow/Deny for a pending confirmation. */
public class EcclesReceiver extends BroadcastReceiver {

	public static final String ACTION_STOP= "starking.eccles.agent.STOP";
	public static final String ACTION_ALLOW= "starking.eccles.agent.ALLOW";
	public static final String ACTION_DENY= "starking.eccles.agent.DENY";
	public static final String ACTION_CAPTURE_STOP= "starking.eccles.agent.CAPTURE_STOP_BTN";

	@Override
	public void onReceive(Context context,Intent intent){
		if(intent == null || intent.getAction() == null) return;
		EcclesApplication app= (EcclesApplication) context.getApplicationContext();
		switch(intent.getAction()){
			case ACTION_STOP:
				app.cancelTask();
				break;
			case ACTION_ALLOW:
				app.gate.resolve(true);
				break;
			case ACTION_DENY:
				app.gate.resolve(false);
				break;
			case ACTION_CAPTURE_STOP:
				starking.eccles.agent.service.CaptureService.stop(context);
				break;
		}
	}
}
