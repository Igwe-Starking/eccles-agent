package starking.eccles.access;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A point-in-time view of the screen: the foreground package plus a prioritised, indexed list
 * of nodes. {@link #toPrompt()} renders it in the compact text form the model sees; the same
 * indices are what the model sends back in {"target":{"index":N}}.
 */
public class ScreenSnapshot {

	public static final int MAX_NODES= 160;
	private static final int MAX_TEXT= 80;

	public String packageName= "";
	public int screenWidth,screenHeight;
	public boolean hasPassword;
	public int hiddenCount;
	public final List<ScreenNode> nodes= new ArrayList<>();

	/**
	 * Keeps every interactive node first (they are what the agent acts on), then fills the
	 * remaining budget with plain text nodes, then restores reading order and assigns indices.
	 */
	public static ScreenSnapshot build(String pkg,int w,int h,List<ScreenNode> raw,int maxNodes){
		ScreenSnapshot s= new ScreenSnapshot();
		s.packageName= pkg == null ? "" : pkg;
		s.screenWidth= w;
		s.screenHeight= h;
		List<ScreenNode> keep= new ArrayList<>();
		for(ScreenNode n : raw){
			if(n.password) s.hasPassword= true;
		}
		int budget= maxNodes;
		boolean[] chosen= new boolean[raw.size()];
		for(int i= 0;i < raw.size() && budget > 0;i++){
			if(raw.get(i).interactive()){ chosen[i]= true; budget--; }
		}
		for(int i= 0;i < raw.size() && budget > 0;i++){
			if(!chosen[i]){ chosen[i]= true; budget--; }
		}
		int idx= 0;
		for(int i= 0;i < raw.size();i++){
			if(chosen[i]){
				ScreenNode n= raw.get(i);
				n.index= idx++;
				keep.add(n);
			} else {
				s.hiddenCount++;
			}
		}
		s.nodes.addAll(keep);
		return s;
	}

	public int interactiveCount(){
		int c= 0;
		for(ScreenNode n : nodes) if(n.interactive()) c++;
		return c;
	}

	/** Few interactive nodes usually means a canvas/game/Flutter/WebView screen: send an image. */
	public boolean looksSparse(){
		return interactiveCount() < 3;
	}

	public ScreenNode byIndex(int i){
		return i >= 0 && i < nodes.size() ? nodes.get(i) : null;
	}

	/**
	 * Resolves a model-supplied target against this snapshot. Priority: exact id, exact text,
	 * exact description, then contains-matches; clickable/editable nodes win ties. The model's
	 * index is trusted only if it does not contradict the text/desc it also supplied.
	 */
	public ScreenNode resolve(int index,String text,String desc,String id){
		ScreenNode byIdx= byIndex(index);
		if(byIdx != null){
			boolean okText= text == null || text.isEmpty() || eqi(byIdx.text,text) || eqi(byIdx.desc,text);
			boolean okDesc= desc == null || desc.isEmpty() || eqi(byIdx.desc,desc) || eqi(byIdx.text,desc);
			if(okText && okDesc) return byIdx;
		}
		ScreenNode best= null;
		int bestScore= 0;
		for(ScreenNode n : nodes){
			int sc= 0;
			if(id != null && !id.isEmpty()){
				if(n.id.equals(id)) sc+= 50;
				else if(n.id.endsWith("/" + id) || n.id.endsWith(id)) sc+= 40;
			}
			if(text != null && !text.isEmpty()){
				if(eqi(n.text,text)) sc+= 30;
				else if(eqi(n.desc,text)) sc+= 28;
				else if(containsi(n.text,text)) sc+= 15;
				else if(containsi(n.desc,text)) sc+= 14;
			}
			if(desc != null && !desc.isEmpty()){
				if(eqi(n.desc,desc)) sc+= 30;
				else if(eqi(n.text,desc)) sc+= 28;
				else if(containsi(n.desc,desc)) sc+= 15;
				else if(containsi(n.text,desc)) sc+= 14;
			}
			if(sc == 0) continue;
			if(n.interactive()) sc+= 3;
			if(!n.enabled) sc-= 5;
			if(sc > bestScore){ best= n; bestScore= sc; }
		}
		return best;
	}

	private static boolean eqi(String a,String b){
		return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
	}

	private static boolean containsi(String hay,String needle){
		return hay != null && needle != null && !needle.trim().isEmpty()
			&& hay.toLowerCase(Locale.ROOT).contains(needle.trim().toLowerCase(Locale.ROOT));
	}

	/** Compact, one line per node, at native screen scale. */
	public String toPrompt(){
		return toPrompt(1.0);
	}

	/**
	 * Same, with all bounds multiplied by {@code scale}. When a downscaled screenshot is sent
	 * with the tree, the model must see the element bounds and the image in ONE coordinate
	 * space (and answer in it); the engine divides its coordinates by the same scale to get
	 * back to real screen pixels. Quotes and newlines in node text are neutralised so a node
	 * cannot break the line format.
	 */
	public String toPrompt(double scale){
		StringBuilder sb= new StringBuilder();
		sb.append("package=").append(packageName)
			.append(" screen=").append(Math.round(screenWidth * scale)).append('x').append(Math.round(screenHeight * scale)).append('\n');
		sb.append("index|class|text|desc|id|flags|bounds(l,t,r,b)\n");
		for(ScreenNode n : nodes){
			sb.append(n.index).append('|')
				.append(shortClass(n.cls)).append('|')
				.append(clean(n.text)).append('|')
				.append(clean(n.desc)).append('|')
				.append(shortId(n.id)).append('|')
				.append(flags(n)).append('|')
				.append(Math.round(n.left * scale)).append(',').append(Math.round(n.top * scale)).append(',')
				.append(Math.round(n.right * scale)).append(',').append(Math.round(n.bottom * scale))
				.append('\n');
		}
		if(hiddenCount > 0) sb.append("(").append(hiddenCount).append(" more low-priority elements omitted)\n");
		return sb.toString();
	}

	static String flags(ScreenNode n){
		StringBuilder f= new StringBuilder();
		if(n.clickable) f.append('C');
		if(n.longClickable) f.append('L');
		if(n.editable) f.append('E');
		if(n.scrollable) f.append('S');
		if(n.checkable) f.append(n.checked ? 'K' : 'U');
		if(n.focused) f.append('F');
		if(!n.enabled) f.append('X');
		if(n.password) f.append('P');
		return f.toString();
	}

	static String clean(String s){
		if(s == null || s.isEmpty()) return "";
		s= s.replace('\n',' ').replace('\r',' ').replace('|','/').replace('"','\'').trim();
		return s.length() > MAX_TEXT ? s.substring(0,MAX_TEXT - 1) + "…" : s;
	}

	static String shortClass(String c){
		if(c == null) return "";
		int i= c.lastIndexOf('.');
		return i >= 0 ? c.substring(i + 1) : c;
	}

	static String shortId(String id){
		if(id == null) return "";
		int i= id.indexOf(":id/");
		return i >= 0 ? id.substring(i + 4) : id;
	}
}
