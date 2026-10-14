package starking.eccles.agent.activities;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import starking.eccles.Surface.AppPojo;
import starking.eccles.Surface.ExcludedAdapter;
import starking.eccles.agent.R;
import starking.eccles.agent.ui.EcclesDialog;
import starking.eccles.guard.EcclesGuard;

/**
 * Lets the user choose which installed apps the agent may never read or operate. Apps the
 * guard blocks on its own (seed list, or auto-detected as banking/wallet/payment) show as ON;
 * switching one OFF records an explicit override.
 */
public class ExcludedActivity extends EcclesOptions {

	private static final String KEY_RESET= "reset_excluded";

	private ExcludedAdapter adapter;
	private TextView summary;
	private final ExecutorService io= Executors.newSingleThreadExecutor();

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		setContentView(R.layout.activity_excluded);
		setTitle(R.string.excluded_apps);

		summary= findViewById(R.id.excluded_summary);
		RecyclerView list= findViewById(R.id.app_list);
		adapter= new ExcludedAdapter((app,excluded) -> {
			EcclesGuard.setExcluded(this,app.pkg,excluded);
			updateSummary();
		});
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		EditText search= findViewById(R.id.search);
		search.addTextChangedListener(new TextWatcher(){
			@Override public void beforeTextChanged(CharSequence s,int a,int b,int c){ }
			@Override public void onTextChanged(CharSequence s,int a,int b,int c){ adapter.filter(s.toString()); }
			@Override public void afterTextChanged(Editable s){ }
		});

		getSupportFragmentManager().setFragmentResultListener(KEY_RESET,this,(k,r) -> {
			if(r.getBoolean(EcclesDialog.RESULT_OK)){
				EcclesGuard.reset(this);
				load();
			}
		});
		load();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		getMenuInflater().inflate(R.menu.excluded_menu,m);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId() == R.id.action_reset){
			EcclesDialog.confirm(this,KEY_RESET,"Reset excluded apps?",
				"This restores the default list and removes your changes.","Reset");
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onDestroy(){
		io.shutdownNow();
		super.onDestroy();
	}

	private void load(){
		io.execute(() -> {
			PackageManager pm= getPackageManager();
			Intent main= new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
			Set<String> userList= EcclesGuard.excluded(this);
			Set<String> seen= new HashSet<>();
			List<AppPojo> out= new ArrayList<>();
			for(ResolveInfo ri : pm.queryIntentActivities(main,0)){
				String pkg= ri.activityInfo.packageName;
				if(!seen.add(pkg) || pkg.equals(getPackageName())) continue;
				EcclesGuard.Decision d= EcclesGuard.check(this,pkg);
				int src= !d.blocked ? AppPojo.SRC_NONE : userList.contains(pkg) ? AppPojo.SRC_USER : AppPojo.SRC_AUTO;
				out.add(new AppPojo(pkg,String.valueOf(ri.loadLabel(pm)),ri.loadIcon(pm),d.blocked,src));
			}
			// Excluded apps first so the user can see what is protected, then alphabetical.
			Collections.sort(out,(a,b) -> {
				if(a.excluded != b.excluded) return a.excluded ? -1 : 1;
				return a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT));
			});
			runOnUiThread(() -> {
				if(isFinishing() || isDestroyed()) return;
				adapter.submit(out);
				updateSummary();
			});
		});
	}

	private void updateSummary(){
		int n= adapter.excludedCount();
		summary.setText(n + (n == 1 ? " app is" : " apps are") + " off-limits. Eccles never reads their screen, takes screenshots of them, or sends anything from them to the AI.");
	}
}
