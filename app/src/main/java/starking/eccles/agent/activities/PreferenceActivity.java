package starking.eccles.agent.activities;

import android.os.Bundle;
import starking.eccles.agent.R;
import starking.eccles.agent.pref.EcclesPreference;

public class PreferenceActivity extends EcclesOptions {

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		setContentView(R.layout.activity_pref);
		setTitle(R.string.settings);
		if(b == null){
			getSupportFragmentManager().beginTransaction().replace(R.id.prefs_container,new EcclesPreference()).commit();
		}
	}
}
