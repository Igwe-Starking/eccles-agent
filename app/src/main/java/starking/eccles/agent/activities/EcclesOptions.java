package starking.eccles.agent.activities;

import android.os.Bundle;
import android.view.MenuItem;
import androidx.appcompat.app.ActionBar;
import starking.eccles.agent.EcclesActivity;

/** Base for secondary screens: an action bar with an Up arrow. */
public class EcclesOptions extends EcclesActivity {

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		ActionBar ab= getSupportActionBar();
		if(ab != null) ab.setDisplayHomeAsUpEnabled(true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId() == android.R.id.home){
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
