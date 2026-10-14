package starking.eccles.engine;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Turns the model's reply into an {@link EcclesPlan}.
 * <p>
 * Models do not always obey "JSON only": they wrap it in code fences, add a sentence before it,
 * or use a synonym for a field. This parser is tolerant of that, but strict about what can
 * actually be executed: an unknown action type or a missing required field is an error (and
 * the engine asks the model to repair its reply), never a guess.
 */
public class EcclesParser {

	public static class ParseException extends Exception {
		public ParseException(String m){
			super(m);
		}
	}

	public static final int MAX_ACTIONS_PER_STEP= 6;

	public static EcclesPlan parse(String raw) throws ParseException{
		if(raw == null || raw.trim().isEmpty()) throw new ParseException("The reply was empty.");
		String json= extractJson(raw);
		JSONObject o;
		try{
			o= new JSONObject(json);
		} catch (JSONException e){
			throw new ParseException("The reply was not valid JSON: " + e.getMessage());
		}

		EcclesPlan plan= new EcclesPlan();
		plan.thought= o.optString("thought","");
		plan.message= o.optString("message","");
		plan.needVision= o.optBoolean("need_vision",false);
		plan.status= normalizeStatus(o.optString("status",EcclesPlan.CONTINUE));

		JSONArray arr= o.optJSONArray("actions");
		if(arr == null){
			JSONObject single= o.optJSONObject("action");
			if(single != null){
				arr= new JSONArray();
				arr.put(single);
			}
		}
		if(arr != null){
			for(int i= 0;i < arr.length() && plan.actions.size() < MAX_ACTIONS_PER_STEP;i++){
				JSONObject a= arr.optJSONObject(i);
				if(a == null) throw new ParseException("actions[" + i + "] is not an object.");
				plan.actions.add(parseAction(a,i));
			}
		}

		if(plan.status.equals(EcclesPlan.CONTINUE) && plan.actions.isEmpty() && !plan.needVision){
			throw new ParseException("status is \"continue\" but there are no actions and need_vision is false.");
		}
		return plan;
	}

	/** Finds the outermost {...} so fences, prose, and trailing commentary are ignored. */
	static String extractJson(String raw){
		String s= raw.trim();
		int fence= s.indexOf("```");
		if(fence >= 0){
			int nl= s.indexOf('\n',fence);
			int end= s.indexOf("```",nl < 0 ? fence + 3 : nl);
			if(nl >= 0 && end > nl) s= s.substring(nl + 1,end);
		}
		int a= s.indexOf('{');
		int b= s.lastIndexOf('}');
		if(a >= 0 && b > a) return s.substring(a,b + 1);
		return s;
	}

	static String normalizeStatus(String s) throws ParseException{
		String v= s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replace('-','_').replace(' ','_');
		switch(v){
			case "":
			case "continue":
			case "in_progress":
			case "working":
				return EcclesPlan.CONTINUE;
			case "done":
			case "complete":
			case "completed":
			case "success":
			case "finished":
				return EcclesPlan.DONE;
			case "failed":
			case "fail":
			case "failure":
			case "error":
			case "cannot_complete":
				return EcclesPlan.FAILED;
			case "ask_user":
			case "ask":
			case "need_input":
			case "needs_input":
				return EcclesPlan.ASK_USER;
			default:
				throw new ParseException("Unknown status \"" + s + "\".");
		}
	}

	static EcclesAction parseAction(JSONObject o,int i) throws ParseException{
		EcclesAction a= new EcclesAction();
		String t= o.optString("type","").trim().toLowerCase(Locale.ROOT).replace('-','_').replace(' ','_');
		switch(t){
			case "open_app": case "open": case "launch": case "launch_app":
				a.type= EcclesAction.OPEN_APP; break;
			case "click": case "press_button": case "select":
				a.type= EcclesAction.CLICK; break;
			case "tap": case "click_at": case "tap_at":
				a.type= EcclesAction.TAP; break;
			case "long_press": case "longpress": case "long_click": case "hold":
				a.type= EcclesAction.LONG_PRESS; break;
			case "swipe": case "drag":
				a.type= EcclesAction.SWIPE; break;
			case "type": case "input_text": case "text": case "enter_text": case "set_text":
				a.type= EcclesAction.TYPE; break;
			case "key": case "press_key": case "global": case "button":
				a.type= EcclesAction.KEY; break;
			case "scroll":
				a.type= EcclesAction.SCROLL; break;
			case "wait": case "sleep": case "pause":
				a.type= EcclesAction.WAIT; break;
			default:
				throw new ParseException("actions[" + i + "] has unknown type \"" + o.optString("type","") + "\".");
		}

		a.app= firstNonEmpty(o.optString("app",""),o.optString("package",""),o.optString("name",""));
		a.text= o.has("text") ? o.optString("text","") : null;
		a.key= o.optString("key","").trim().toLowerCase(Locale.ROOT);
		a.direction= o.optString("direction","").trim().toLowerCase(Locale.ROOT);
		a.sensitive= o.optBoolean("sensitive",false);
		a.append= o.optBoolean("append",false);

		JSONObject target= o.optJSONObject("target");
		if(target != null){
			a.index= target.has("index") ? (int) Math.round(target.optDouble("index",-1)) : -1;
			a.targetText= emptyToNull(target.optString("text",""));
			a.targetDesc= emptyToNull(firstNonEmpty(target.optString("desc",""),target.optString("description","")));
			a.targetId= emptyToNull(target.optString("id",""));
		} else if(o.has("index")){
			a.index= (int) Math.round(o.optDouble("index",-1));
		}

		a.x= coord(o,"x"); a.y= coord(o,"y"); a.x2= coord(o,"x2"); a.y2= coord(o,"y2");
		if(a.x < 0 && a.x2 < 0){
			// accept {"x1":..,"y1":..} spelling for swipes
			a.x= coord(o,"x1"); a.y= coord(o,"y1");
		}
		long dur= o.has("duration_ms") ? o.optLong("duration_ms",0) : o.optLong("duration",0);

		switch(a.type){
			case EcclesAction.OPEN_APP:
				if(isEmpty(a.app)) throw new ParseException("actions[" + i + "] open_app needs \"app\".");
				break;
			case EcclesAction.CLICK:
				if(!a.hasTarget()){
					if(a.hasPoint()){
						a.type= EcclesAction.TAP; // a click with only coordinates is a tap
						a.durationMs= clamp(dur,100,1,1000);
					} else {
						throw new ParseException("actions[" + i + "] click needs a target (index/text/desc/id) or x,y.");
					}
				}
				break;
			case EcclesAction.TAP:
				if(!a.hasPoint()) throw new ParseException("actions[" + i + "] tap needs x and y.");
				a.durationMs= clamp(dur,100,1,1000);
				break;
			case EcclesAction.LONG_PRESS:
				if(!a.hasPoint()){
					if(!a.hasTarget()) throw new ParseException("actions[" + i + "] long_press needs x,y or a target.");
				}
				a.durationMs= clamp(dur,800,400,5000);
				break;
			case EcclesAction.SWIPE:
				if(a.x < 0 || a.y < 0 || a.x2 < 0 || a.y2 < 0) throw new ParseException("actions[" + i + "] swipe needs x, y, x2, y2.");
				a.durationMs= clamp(dur,300,50,5000);
				break;
			case EcclesAction.TYPE:
				if(a.text == null) throw new ParseException("actions[" + i + "] type needs \"text\".");
				break;
			case EcclesAction.KEY:
				a.key= normalizeKey(a.key);
				if(a.key == null) throw new ParseException("actions[" + i + "] key must be back, home, recents, notifications or quick_settings.");
				break;
			case EcclesAction.SCROLL:
				if(!(a.direction.equals("up") || a.direction.equals("down") || a.direction.equals("left") || a.direction.equals("right"))){
					a.direction= "down";
				}
				break;
			case EcclesAction.WAIT:
				a.durationMs= clamp(dur,1000,100,15000);
				break;
		}
		return a;
	}

	private static String normalizeKey(String k){
		switch(k){
			case "back": case "go_back": return EcclesAction.KEY_BACK;
			case "home": case "go_home": return EcclesAction.KEY_HOME;
			case "recents": case "recent": case "overview": case "app_switch": return EcclesAction.KEY_RECENTS;
			case "notifications": case "notification": case "notification_shade": return EcclesAction.KEY_NOTIFICATIONS;
			case "quick_settings": case "quicksettings": return EcclesAction.KEY_QUICK_SETTINGS;
			default: return null;
		}
	}

	private static int coord(JSONObject o,String k){
		if(!o.has(k)) return -1;
		double d= o.optDouble(k,-1);
		if(Double.isNaN(d) || d < 0) return -1;
		return (int) Math.round(d);
	}

	private static long clamp(long v,long def,long min,long max){
		if(v <= 0) return def;
		return Math.max(min,Math.min(max,v));
	}

	private static boolean isEmpty(String s){
		return s == null || s.trim().isEmpty();
	}

	private static String emptyToNull(String s){
		return isEmpty(s) ? null : s;
	}

	private static String firstNonEmpty(String... v){
		for(String s : v) if(!isEmpty(s)) return s;
		return "";
	}
}
