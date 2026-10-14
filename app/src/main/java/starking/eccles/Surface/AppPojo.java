package starking.eccles.Surface;

import android.graphics.drawable.Drawable;

/** One installed launcher app in the Excluded Apps list. */
public class AppPojo {

	public static final int SRC_NONE= 0;
	public static final int SRC_USER= 1;
	public static final int SRC_AUTO= 2;

	public final String pkg;
	public final String label;
	public final Drawable icon;
	public boolean excluded;
	public int source;

	public AppPojo(String pkg,String label,Drawable icon,boolean excluded,int source){
		this.pkg= pkg;
		this.label= label;
		this.icon= icon;
		this.excluded= excluded;
		this.source= source;
	}
}
