package starking.eccles.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.json.JSONObject;

/**
 * Minimal blocking JSON-over-HTTPS POST helper. Plain HttpURLConnection on purpose: the app
 * has no third-party networking dependency to keep up to date, and every provider speaks the
 * same simple request/response shape.
 */
public class EcclesHttp {

	public static final int CONNECT_MS= 15000;
	/** Vision requests with large screenshots can legitimately take a while. */
	public static final int READ_MS= 90000;

	private volatile HttpURLConnection current;
	private volatile boolean aborted;

	public String postJson(String url,Map<String,String> headers,String body) throws EcclesProvider.AiException{
		HttpURLConnection c= null;
		aborted= false;
		try{
			c= (HttpURLConnection) new URL(url).openConnection();
			current= c;
			c.setRequestMethod("POST");
			c.setConnectTimeout(CONNECT_MS);
			c.setReadTimeout(READ_MS);
			c.setDoOutput(true);
			c.setRequestProperty("Content-Type","application/json; charset=utf-8");
			c.setRequestProperty("Accept","application/json");
			if(headers != null){
				for(Map.Entry<String,String> e : headers.entrySet()){
					c.setRequestProperty(e.getKey(),e.getValue());
				}
			}
			byte[] out= body.getBytes(StandardCharsets.UTF_8);
			c.setFixedLengthStreamingMode(out.length);
			try(OutputStream os= c.getOutputStream()){
				os.write(out);
			}
			int code= c.getResponseCode();
			InputStream is= code >= 400 ? c.getErrorStream() : c.getInputStream();
			String resp= is == null ? "" : readAll(is);
			if(code >= 200 && code < 300) return resp;
			throw new EcclesProvider.AiException(code,describe(code,resp),code == 429 || code >= 500);
		} catch (EcclesProvider.AiException e){
			throw e;
		} catch (IOException e){
			if(aborted) throw new EcclesProvider.AiException(-2,"Cancelled",false);
			throw new EcclesProvider.AiException(-1,"Network error: " + e.getMessage(),true);
		} finally {
			current= null;
			if(c != null) c.disconnect();
		}
	}

	public void abort(){
		aborted= true;
		final HttpURLConnection c= current;
		// disconnect() touches the socket, which is not allowed on the main thread (the Stop
		// button calls this from there), so hop to a throwaway thread.
		if(c != null) new Thread(c::disconnect,"eccles-abort").start();
	}

	static String describe(int code,String body){
		String detail= extractMessage(body);
		switch(code){
			case 400: return "The AI rejected the request (400)" + suffix(detail);
			case 401:
			case 403: return "The API key was rejected (" + code + "). Check it in Settings" + suffix(detail);
			case 404: return "Model or endpoint not found (404). Check the model name in Settings" + suffix(detail);
			case 429: return "Rate limit or quota reached (429)" + suffix(detail);
			default:
				if(code >= 500) return "The AI service had a problem (" + code + ")" + suffix(detail);
				return "Unexpected response (" + code + ")" + suffix(detail);
		}
	}

	private static String suffix(String d){
		return d == null || d.isEmpty() ? "." : ": " + d;
	}

	/** All three supported APIs report failures as {"error":{"message":"..."}}. */
	static String extractMessage(String body){
		if(body == null || body.isEmpty()) return "";
		try{
			JSONObject o= new JSONObject(body);
			JSONObject err= o.optJSONObject("error");
			if(err != null){
				String m= err.optString("message","");
				if(!m.isEmpty()) return trim(m);
			}
			String m= o.optString("message","");
			if(!m.isEmpty()) return trim(m);
		} catch (Exception ignored){
		}
		return trim(body);
	}

	private static String trim(String s){
		s= s.replace('\n',' ').trim();
		return s.length() > 300 ? s.substring(0,300) + "…" : s;
	}

	private static String readAll(InputStream is) throws IOException{
		ByteArrayOutputStream bo= new ByteArrayOutputStream();
		byte[] buf= new byte[8192];
		int n;
		while((n= is.read(buf)) != -1) bo.write(buf,0,n);
		return new String(bo.toByteArray(),StandardCharsets.UTF_8);
	}
}
