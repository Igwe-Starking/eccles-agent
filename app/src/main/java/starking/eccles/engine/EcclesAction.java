package starking.eccles.engine;

/**
 * One step the AI asked the device to perform. Pure data (no android.* types) so the parser
 * and the safety rules can be tested on the JVM.
 */
public class EcclesAction {

	public static final String OPEN_APP= "open_app";
	public static final String CLICK= "click";
	public static final String TAP= "tap";
	public static final String LONG_PRESS= "long_press";
	public static final String SWIPE= "swipe";
	public static final String TYPE= "type";
	public static final String KEY= "key";
	public static final String SCROLL= "scroll";
	public static final String WAIT= "wait";

	public static final String KEY_BACK= "back";
	public static final String KEY_HOME= "home";
	public static final String KEY_RECENTS= "recents";
	public static final String KEY_NOTIFICATIONS= "notifications";
	public static final String KEY_QUICK_SETTINGS= "quick_settings";

	public String type;
	/** open_app: app label or package name. */
	public String app;
	/** type: the text to enter. */
	public String text;
	public String key;
	public String direction;

	/** Target node: index into the screen list the model was shown, plus fallbacks. */
	public int index= -1;
	public String targetText,targetDesc,targetId;

	/** Coordinates in the space of the observation the model saw (image px if a screenshot was attached). */
	public int x= -1,y= -1,x2= -1,y2= -1;
	public long durationMs;

	/** The model's own flag for irreversible actions (send, post, delete, pay, ...). */
	public boolean sensitive;
	/** type: append to the existing text instead of replacing it. */
	public boolean append;

	public boolean hasPoint(){
		return x >= 0 && y >= 0;
	}

	public boolean hasTarget(){
		return index >= 0 || notEmpty(targetText) || notEmpty(targetDesc) || notEmpty(targetId);
	}

	private static boolean notEmpty(String s){
		return s != null && !s.isEmpty();
	}

	public EcclesAction copy(){
		EcclesAction a= new EcclesAction();
		a.type= type; a.app= app; a.text= text; a.key= key; a.direction= direction;
		a.index= index; a.targetText= targetText; a.targetDesc= targetDesc; a.targetId= targetId;
		a.x= x; a.y= y; a.x2= x2; a.y2= y2; a.durationMs= durationMs;
		a.sensitive= sensitive; a.append= append;
		return a;
	}

	/**
	 * Converts coordinates from the model's space to real screen pixels. {@code scale} is
	 * observation pixels per screen pixel (below 1.0 when the screenshot was downscaled), so
	 * dividing maps back. Returns this action unchanged when no conversion is needed.
	 */
	public EcclesAction toScreen(double scale){
		if(scale == 1.0 || scale <= 0) return this;
		EcclesAction c= copy();
		if(c.x >= 0) c.x= (int) Math.round(c.x / scale);
		if(c.y >= 0) c.y= (int) Math.round(c.y / scale);
		if(c.x2 >= 0) c.x2= (int) Math.round(c.x2 / scale);
		if(c.y2 >= 0) c.y2= (int) Math.round(c.y2 / scale);
		return c;
	}

	/** Short human description for the timeline and history. Never includes typed text longer than 40 chars. */
	public String describe(){
		switch(type){
			case OPEN_APP: return "Open " + app;
			case CLICK: return "Click " + targetLabel();
			case TAP: return "Tap (" + x + ", " + y + ") for " + durationMs + " ms";
			case LONG_PRESS: return "Long-press (" + x + ", " + y + ") for " + durationMs + " ms";
			case SWIPE: return "Swipe (" + x + ", " + y + ") → (" + x2 + ", " + y2 + ") in " + durationMs + " ms";
			case TYPE: return "Type “" + shorten(text,40) + "”";
			case KEY: return "Press " + key;
			case SCROLL: return "Scroll " + direction;
			case WAIT: return "Wait " + durationMs + " ms";
			default: return type;
		}
	}

	public String targetLabel(){
		if(notEmpty(targetText)) return "“" + shorten(targetText,40) + "”";
		if(notEmpty(targetDesc)) return "“" + shorten(targetDesc,40) + "”";
		if(notEmpty(targetId)) return "#" + targetId;
		if(index >= 0) return "item " + index;
		return "target";
	}

	static String shorten(String s,int max){
		if(s == null) return "";
		s= s.replace('\n',' ');
		return s.length() <= max ? s : s.substring(0,max - 1) + "…";
	}
}
