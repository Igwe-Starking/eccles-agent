package starking.eccles.adb;

import java.io.IOException;

/**
 * A way to run a shell command with shell-level (ADB) privileges. Two implementations ship:
 * {@link RootTransport} (rooted devices) and {@link TcpAdbTransport} (talks to the device's own
 * adbd over localhost after a one-time "adb tcpip 5555" from a computer).
 */
public interface AdbTransport {

	/** Short name for the UI: "Root", "ADB (localhost:5555)". */
	String name();

	/** Runs the command and returns raw stdout (binary-safe, so screencap works). */
	byte[] exec(String command,int timeoutMs) throws IOException;

	/** Cheap connectivity probe. Never throws. */
	boolean isAvailable();

	void close();
}
