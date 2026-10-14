package starking.eccles.Surface;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import starking.eccles.agent.R;

public class ExcludedAdapter extends RecyclerView.Adapter<ExcludedAdapter.Holder> {

	public interface OnToggle {
		void onToggle(AppPojo app,boolean excluded);
	}

	private final List<AppPojo> all= new ArrayList<>();
	private final List<AppPojo> shown= new ArrayList<>();
	private final OnToggle onToggle;
	private String query= "";

	public ExcludedAdapter(OnToggle t){
		this.onToggle= t;
	}

	public void submit(List<AppPojo> apps){
		all.clear();
		all.addAll(apps);
		refilter();
	}

	public void filter(String q){
		query= q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		refilter();
	}

	private void refilter(){
		shown.clear();
		for(AppPojo a : all){
			if(query.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(query) || a.pkg.toLowerCase(Locale.ROOT).contains(query)){
				shown.add(a);
			}
		}
		notifyDataSetChanged();
	}

	public int excludedCount(){
		int n= 0;
		for(AppPojo a : all) if(a.excluded) n++;
		return n;
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup p,int t){
		return new Holder(LayoutInflater.from(p.getContext()).inflate(R.layout.app_row,p,false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder h,int pos){
		final AppPojo a= shown.get(pos);
		h.icon.setImageDrawable(a.icon);
		h.label.setText(a.label);
		h.pkg.setText(a.pkg);
		h.source.setVisibility(a.excluded && a.source != AppPojo.SRC_NONE ? View.VISIBLE : View.GONE);
		h.source.setText(a.source == AppPojo.SRC_AUTO ? R.string.excluded_source_auto : R.string.excluded_source_list);
		h.toggle.setOnCheckedChangeListener(null); // never fire while recycling
		h.toggle.setChecked(a.excluded);
		h.toggle.setOnCheckedChangeListener((b,checked) -> {
			a.excluded= checked;
			a.source= checked ? AppPojo.SRC_USER : AppPojo.SRC_NONE;
			if(onToggle != null) onToggle.onToggle(a,checked);
			int bp= h.getBindingAdapterPosition();
			if(bp != RecyclerView.NO_POSITION) notifyItemChanged(bp);
		});
		h.itemView.setOnClickListener(v -> h.toggle.toggle());
	}

	@Override
	public int getItemCount(){
		return shown.size();
	}

	static class Holder extends RecyclerView.ViewHolder {
		final ImageView icon;
		final TextView label,pkg,source;
		final MaterialSwitch toggle;

		Holder(View v){
			super(v);
			icon= v.findViewById(R.id.app_icon);
			label= v.findViewById(R.id.app_label);
			pkg= v.findViewById(R.id.app_pkg);
			source= v.findViewById(R.id.app_source);
			toggle= v.findViewById(R.id.app_switch);
		}
	}
}
