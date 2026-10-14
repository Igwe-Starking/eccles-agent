package starking.eccles.agent.service;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import starking.eccles.ai.EcclesProvider;
import starking.eccles.ai.EcclesProviders;
import starking.eccles.data.EcclesStorage;

/** Calls the chosen AI with retry/backoff for transient failures. Blocking; run off the main thread. */
public class Caller {

	private static final String TAG= "Caller";
	private static final int ATTEMPTS= 3;

	private final EcclesProvider provider;
	private volatile boolean cancelled;

	public Caller(Context c) throws EcclesProvider.AiException{
		String id= EcclesStorage.provider(c);
		String key= EcclesStorage.apiKey(c,id);
		if(key.isEmpty() && !EcclesProviders.keyOptional(id)){
			throw new EcclesProvider.AiException(401,"No API key for " + EcclesProviders.label(id) + ". Add one in Settings.",false);
		}
		provider= EcclesProviders.create(id,key,EcclesStorage.model(c,id),EcclesStorage.baseUrl(c));
	}

	/** For the Settings "Test connection" button. */
	public static EcclesProvider providerFor(Context c){
		String id= EcclesStorage.provider(c);
		return EcclesProviders.create(id,EcclesStorage.apiKey(c,id),EcclesStorage.model(c,id),EcclesStorage.baseUrl(c));
	}

	public String label(){
		return provider.label() + " · " + provider.model();
	}

	public String providerId(){
		return provider.id();
	}

	public String call(String system,String user,byte[] jpeg) throws EcclesProvider.AiException{
		EcclesProvider.AiException last= null;
		for(int attempt= 1;attempt <= ATTEMPTS;attempt++){
			if(cancelled) throw new EcclesProvider.AiException(-2,"Cancelled",false);
			try{
				return provider.generate(system,user,jpeg);
			} catch (EcclesProvider.AiException e){
				last= e;
				if(cancelled || !e.retryable || attempt == ATTEMPTS) throw e;
				long wait= attempt * 1500L;
				Log.w(TAG,"attempt " + attempt + " failed (" + e.getMessage() + "), retrying in " + wait + " ms");
				long end= SystemClock.uptimeMillis() + wait;
				while(SystemClock.uptimeMillis() < end){
					if(cancelled) throw new EcclesProvider.AiException(-2,"Cancelled",false);
					SystemClock.sleep(100);
				}
			}
		}
		throw last;
	}

	public void abort(){
		cancelled= true;
		provider.abort();
	}
}
