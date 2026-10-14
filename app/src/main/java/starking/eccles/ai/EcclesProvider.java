package starking.eccles.ai;

/**
 * Base class for every AI backend Eccles Agent can talk to. A provider is deliberately
 * stateless and single-shot: the engine rebuilds the full prompt (goal + recent history +
 * current screen) for every step, so no conversation state ever has to be kept in sync with a
 * remote server, and a failed/retried request can never leave the two sides disagreeing.
 * <p>
 * This class (and its subclasses) must stay free of android.* imports so the request/response
 * handling can be unit-tested on the plain JVM.
 */
public abstract class EcclesProvider {

	public static class AiException extends Exception {
		public final int code;
		public final boolean retryable;

		public AiException(int code,String message,boolean retryable){
			super(message);
			this.code= code;
			this.retryable= retryable;
		}
	}

	protected final String apiKey,model,baseUrl;
	protected final EcclesHttp http= new EcclesHttp();

	protected EcclesProvider(String apiKey,String model,String baseUrl){
		this.apiKey= apiKey == null ? "" : apiKey.trim();
		this.model= model == null ? "" : model.trim();
		this.baseUrl= baseUrl == null ? "" : baseUrl.trim();
	}

	/** Stable machine id, also used as the preference key suffix: gemini, openai, claude, custom. */
	public abstract String id();

	/** Human readable name shown in the UI. */
	public abstract String label();

	/** True when a request without an API key can possibly succeed (local servers). */
	public boolean keyOptional(){
		return false;
	}

	/**
	 * Sends one request and returns the model's raw text reply.
	 *
	 * @param system the system prompt
	 * @param user   the per-step user prompt
	 * @param jpeg   optional screenshot, already scaled and JPEG-compressed; null for text-only
	 */
	public abstract String generate(String system,String user,byte[] jpeg) throws AiException;

	/** Aborts an in-flight request from another thread (used by the Stop button). */
	public void abort(){
		http.abort();
	}

	public String model(){
		return model;
	}

	protected void requireKey() throws AiException{
		if(apiKey.isEmpty() && !keyOptional()){
			throw new AiException(401,"No API key set for " + label() + ". Add one in Settings.",false);
		}
	}

	protected static String b64(byte[] data){
		return java.util.Base64.getEncoder().encodeToString(data);
	}
}
