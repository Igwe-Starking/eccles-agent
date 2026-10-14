package starking.eccles.engine;

/**
 * The decision rules for "try the accessibility node first, fall back to a touch gesture".
 * Pure Java so the policy is unit-tested rather than buried in Android code.
 * <p>
 * Why not simply gesture-tap everything, or simply ACTION_CLICK everything?
 * <ul>
 *   <li>A node action needs no coordinates (so it cannot land on a popup or be skewed by scaling) and
 *       works on partly covered targets. But some custom views ignore it, or accept it without doing
 *       anything.</li>
 *   <li>A gesture works on anything a finger can touch, but depends on exact coordinates.</li>
 * </ul>
 * So: node first, and a check that something really happened.
 */
public final class EcclesPolicy {

	private EcclesPolicy(){
	}

	/** The accessibility action was performed and the UI reacted (a window event followed). */
	public static final int CHANGED= 0;
	/** The action was accepted, but nothing on screen reacted. */
	public static final int NO_CHANGE= 1;
	/** There was no suitable node, or the node refused the action. */
	public static final int UNAVAILABLE= 2;

	public enum Next { DONE,REPLAN,GESTURE }

	/**
	 * What to do after a node action.
	 * <ul>
	 *   <li>{@code CHANGED}: finished.</li>
	 *   <li>{@code UNAVAILABLE}: nothing happened, so a gesture cannot double-fire: do it.</li>
	 *   <li>{@code NO_CHANGE}: accepted but no visible reaction. A blind gesture retry could
	 *       double-activate if the first one did work (a "like" toggled twice is "unliked", a
	 *       post sent twice), so report it instead and let the model look at the real screen.</li>
	 * </ul>
	 */
	public static Next afterNodeAction(int outcome){
		switch(outcome){
			case CHANGED: return Next.DONE;
			case NO_CHANGE: return Next.REPLAN;
			default: return Next.GESTURE;
		}
	}

	public static final String NO_CHANGE_MESSAGE=
		"The click was accepted but the screen did not react. It may need a different element or exact "
		+ "coordinates; look at the screenshot and try again.";

	/** A short press: a click. */
	public static boolean isTap(long durationMs){
		return durationMs <= 300;
	}

	/** A held press: a long-click. Durations in between are treated as deliberate gestures. */
	public static boolean isLongPress(long durationMs){
		return durationMs >= 400;
	}

	/** Maps scroll direction to whether the "forward" action is the right fallback (down/right). */
	public static boolean scrollsForward(String direction){
		return "down".equals(direction) || "right".equals(direction);
	}
}
