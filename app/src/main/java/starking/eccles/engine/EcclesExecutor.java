package starking.eccles.engine;

import android.graphics.Bitmap;
import starking.eccles.access.ScreenSnapshot;

/** Something that can perform actions on the device and observe it. */
public interface EcclesExecutor {

	String name();

	boolean isAvailable();

	/** Performs one action. Coordinates in the action are already real screen pixels. */
	EcclesResult execute(EcclesAction a);

	/** Package name of the app in front, or null if it cannot be determined. */
	String foregroundPackage();

	/** Full-resolution screenshot, or null. */
	Bitmap capture();

	/** Structured view of the screen, or null if this executor has none (ADB). */
	ScreenSnapshot snapshot();
}
