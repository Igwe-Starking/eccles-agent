package starking.eccles.Surface;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import java.util.ArrayList;
import java.util.List;
import starking.eccles.agent.R;

/** Task timeline: the user's goal (me_task), each agent step (u_step), and the outcome (u_result). */
public class EcclesAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

	private final List<EcclesPojo> items= new ArrayList<>();

	// All mutation happens on the main thread (the application posts there).
	public void setAll(List<EcclesPojo> all){
		items.clear();
		items.addAll(all);
		notifyDataSetChanged();
	}

	public void clear(){
		items.clear();
		notifyDataSetChanged();
	}

	/** @return the adapter position that changed, for scrolling. */
	public int upsert(EcclesPojo p,boolean added){
		int idx= items.indexOf(p);
		if(idx < 0){
			items.add(p);
			notifyItemInserted(items.size() - 1);
			return items.size() - 1;
		}
		notifyItemChanged(idx);
		return idx;
	}

	@Override
	public int getItemCount(){
		return items.size();
	}

	@Override
	public int getItemViewType(int position){
		return items.get(position).type;
	}

	@NonNull
	@Override
	public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){
		LayoutInflater in= LayoutInflater.from(parent.getContext());
		switch(type){
			case EcclesPojo.TASK: return new TaskHolder(in.inflate(R.layout.me_task,parent,false));
			case EcclesPojo.RESULT: return new ResultHolder(in.inflate(R.layout.u_result,parent,false));
			default: return new StepHolder(in.inflate(R.layout.u_step,parent,false));
		}
	}

	@Override
	public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h,int position){
		EcclesPojo p= items.get(position);
		if(h instanceof TaskHolder) ((TaskHolder) h).bind(p);
		else if(h instanceof ResultHolder) ((ResultHolder) h).bind(p);
		else ((StepHolder) h).bind(p);
	}

	static int color(Context c,int attr,int fallback){
		return MaterialColors.getColor(c,attr,fallback);
	}

	static class TaskHolder extends RecyclerView.ViewHolder {
		final TextView text;

		TaskHolder(View v){
			super(v);
			text= v.findViewById(R.id.task_text);
		}

		void bind(EcclesPojo p){
			text.setText(p.title);
		}
	}

	static class StepHolder extends RecyclerView.ViewHolder {
		final ImageView icon;
		final CircularProgressIndicator spinner;
		final TextView title,detail;

		StepHolder(View v){
			super(v);
			icon= v.findViewById(R.id.step_icon);
			spinner= v.findViewById(R.id.step_spinner);
			title= v.findViewById(R.id.step_title);
			detail= v.findViewById(R.id.step_detail);
		}

		void bind(EcclesPojo p){
			Context c= itemView.getContext();
			title.setText(p.title);
			boolean running= p.state == EcclesPojo.RUNNING;
			spinner.setVisibility(running ? View.VISIBLE : View.GONE);
			icon.setVisibility(running ? View.GONE : View.VISIBLE);
			int res,tint;
			switch(p.state){
				case EcclesPojo.OK: res= R.drawable.ic_check; tint= c.getColor(R.color.eccles_success); break;
				case EcclesPojo.FAIL: res= R.drawable.ic_close; tint= c.getColor(R.color.eccles_error); break;
				case EcclesPojo.WARN: res= R.drawable.ic_warning; tint= c.getColor(R.color.eccles_warning); break;
				default: res= R.drawable.ic_info; tint= color(c,com.google.android.material.R.attr.colorOnSurfaceVariant,0xFF888888);
			}
			icon.setImageResource(res);
			icon.setImageTintList(ColorStateList.valueOf(tint));
			boolean hasDetail= p.detail != null && !p.detail.isEmpty();
			detail.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
			detail.setText(p.detail);
			// Plain notes (thoughts / info) read lighter than actions.
			title.setAlpha(p.state == EcclesPojo.INFO ? 0.75f : 1f);
		}
	}

	static class ResultHolder extends RecyclerView.ViewHolder {
		final MaterialCardView card;
		final ImageView icon;
		final TextView title,detail;

		ResultHolder(View v){
			super(v);
			card= v.findViewById(R.id.result_card);
			icon= v.findViewById(R.id.result_icon);
			title= v.findViewById(R.id.result_title);
			detail= v.findViewById(R.id.result_detail);
		}

		void bind(EcclesPojo p){
			Context c= itemView.getContext();
			int bg,fg,res;
			switch(p.state){
				case EcclesPojo.OK: bg= R.color.eccles_success_container; fg= R.color.eccles_on_success_container; res= R.drawable.ic_check; break;
				case EcclesPojo.FAIL: bg= R.color.eccles_error_container; fg= R.color.eccles_on_error_container; res= R.drawable.ic_close; break;
				default: bg= R.color.eccles_warning_container; fg= R.color.eccles_on_warning_container; res= R.drawable.ic_warning;
			}
			card.setCardBackgroundColor(c.getColor(bg));
			int f= c.getColor(fg);
			title.setTextColor(f);
			detail.setTextColor(f);
			icon.setImageResource(res);
			icon.setImageTintList(ColorStateList.valueOf(f));
			title.setText(p.title);
			detail.setText(p.detail);
			detail.setVisibility(p.detail == null || p.detail.isEmpty() ? View.GONE : View.VISIBLE);
		}
	}
}
