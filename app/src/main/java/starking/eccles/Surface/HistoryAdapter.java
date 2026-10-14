package starking.eccles.Surface;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;
import starking.eccles.agent.R;
import starking.eccles.data.EcclesData;

public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.Holder> {

	private final List<EcclesData.Row> rows= new ArrayList<>();

	public void submit(List<EcclesData.Row> r){
		rows.clear();
		rows.addAll(r);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){
		return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.history_row,p,false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder h,int pos){
		EcclesData.Row r= rows.get(pos);
		h.goal.setText(r.goal);
		h.message.setText(r.message == null || r.message.isEmpty() ? "" : r.message);
		h.message.setVisibility(r.message == null || r.message.isEmpty() ? View.GONE : View.VISIBLE);
		int color;
		String label;
		switch(r.status == null ? "" : r.status){
			case "success": label= "Done"; color= R.color.eccles_success; break;
			case "failed": label= "Failed"; color= R.color.eccles_error; break;
			case "blocked": label= "Protected"; color= R.color.eccles_warning; break;
			case "cancelled": label= "Stopped"; color= R.color.eccles_warning; break;
			case "needs_input": label= "Needs input"; color= R.color.eccles_warning; break;
			case "running": label= "Interrupted"; color= R.color.eccles_warning; break; // never finished: app was killed
			default: label= r.status; color= R.color.eccles_warning;
		}
		h.status.setText(label);
		h.status.setTextColor(h.itemView.getContext().getColor(color));
		String when= DateUtils.getRelativeTimeSpanString(r.started,System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS).toString();
		h.meta.setText(when + " · " + r.steps + (r.steps == 1 ? " step" : " steps") + (r.provider == null ? "" : " · " + r.provider));
	}

	@Override
	public int getItemCount(){
		return rows.size();
	}

	static class Holder extends RecyclerView.ViewHolder {
		final TextView goal,message,status,meta;

		Holder(View v){
			super(v);
			goal= v.findViewById(R.id.h_goal);
			message= v.findViewById(R.id.h_message);
			status= v.findViewById(R.id.h_status);
			meta= v.findViewById(R.id.h_meta);
		}
	}
}
