package starking.eccles.agent.activities;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import starking.eccles.Surface.HistoryAdapter;
import starking.eccles.agent.R;
import starking.eccles.agent.ui.EcclesDialog;
import starking.eccles.data.EcclesData;

public class HistoryActivity extends EcclesOptions {

	private static final String KEY_CLEAR= "clear_history";

	private HistoryAdapter adapter;
	private View empty;
	private final ExecutorService io= Executors.newSingleThreadExecutor();

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		setContentView(R.layout.activity_history);
		setTitle(R.string.history);
		empty= findViewById(R.id.history_empty);
		RecyclerView list= findViewById(R.id.history_list);
		adapter= new HistoryAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		getSupportFragmentManager().setFragmentResultListener(KEY_CLEAR,this,(k,r) -> {
			if(r.getBoolean(EcclesDialog.RESULT_OK)){
				final EcclesData d= app.data;
				io.execute(() -> {
					if(d != null) d.clear();
					load();
				});
			}
		});
		load();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		getMenuInflater().inflate(R.menu.history_menu,m);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId() == R.id.action_clear){
			EcclesDialog.confirm(this,KEY_CLEAR,"Clear history?","This removes the list of past tasks. It cannot be undone.","Clear");
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
		final EcclesData d= app.data;
		io.execute(() -> {
			final List<EcclesData.Row> rows= d == null ? new java.util.ArrayList<EcclesData.Row>() : d.recent(100);
			runOnUiThread(() -> {
				if(isFinishing() || isDestroyed()) return;
				adapter.submit(rows);
				empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
			});
		});
	}
}
