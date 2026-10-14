package starking.eccles.engine;

import java.util.ArrayList;
import java.util.List;

/** The parsed reply for one agent step. */
public class EcclesPlan {

	public static final String CONTINUE= "continue";
	public static final String DONE= "done";
	public static final String FAILED= "failed";
	public static final String ASK_USER= "ask_user";

	public String status= CONTINUE;
	public String thought= "";
	public String message= "";
	/** The model wants a screenshot on the next step. */
	public boolean needVision;
	public final List<EcclesAction> actions= new ArrayList<>();
}
