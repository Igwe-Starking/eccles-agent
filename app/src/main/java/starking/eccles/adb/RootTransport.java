package starking.eccles.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs commands through "su -c". Only useful on rooted devices. */
public class RootTransport implements AdbTransport {

	private static final int MAX_OUTPUT= 16 * 1024 * 1024;
	private volatile Boolean available;

	@Override
	public String name(){
		return "Root";
	}

	@Override
	public byte[] exec(String command,int timeoutMs) throws IOException{
		Process p= new ProcessBuilder("su","-c",command).redirectErrorStream(false).start();
		try{
			// Drain stderr on a side thread so a chatty command cannot block on a full pipe.
			final InputStream err= p.getErrorStream();
			Thread t= new Thread(() -> {
				try{ byte[] b= new byte[4096]; while(err.read(b) != -1){ } } catch (IOException ignored){ }
			},"eccles-su-err");
			t.setDaemon(true);
			t.start();

			ByteArrayOutputStream bo= new ByteArrayOutputStream();
			InputStream in= p.getInputStream();
			byte[] buf= new byte[16384];
			int n;
			while((n= in.read(buf)) != -1){
				bo.write(buf,0,n);
				if(bo.size() > MAX_OUTPUT) throw new IOException("Command output too large");
			}
			if(!p.waitFor(timeoutMs,TimeUnit.MILLISECONDS)){
				throw new IOException("Command timed out");
			}
			return bo.toByteArray();
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted");
		} finally {
			p.destroy();
		}
	}

	@Override
	public boolean isAvailable(){
		Boolean a= available;
		if(a != null && a) return true; // only cache success; root can be granted later
		try{
			String out= new String(exec("id",4000),StandardCharsets.UTF_8);
			boolean ok= out.contains("uid=0");
			available= ok;
			return ok;
		} catch (Exception e){
			return false;
		}
	}

	@Override
	public void close(){
	}
}
