package starking.eccles.agent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Splash. Waits (briefly) for the history database, then opens the console. */
public class MainActivity extends Activity {

	private static final long MIN_SPLASH_MS= 700;
	private ScheduledExecutorService poll;

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		final EcclesApplication app= (EcclesApplication) getApplication();
		final long start= System.currentTimeMillis();
		poll= Executors.newSingleThreadScheduledExecutor();
		poll.scheduleWithFixedDelay(() -> {
			if(app.databaseOpen && System.currentTimeMillis() - start >= MIN_SPLASH_MS){
				poll.shutdown();
				runOnUiThread(() -> {
					if(isFinishing() || isDestroyed()) return;
					startActivity(new Intent(MainActivity.this,MajorActivity.class));
					finish();
				});
			}
		},0,100,TimeUnit.MILLISECONDS);
	}

	@Override
	protected void onDestroy(){
		if(poll != null) poll.shutdownNow();
		super.onDestroy();
	}
}
