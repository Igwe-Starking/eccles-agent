package starking.eccles.ai;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * OpenAI Chat Completions. Also serves the "custom" provider: anything that speaks the same
 * protocol (OpenRouter, Groq, DeepSeek, a local Ollama/LM Studio over localhost, ...) works by
 * pointing the base URL at it.
 */
public class OpenAiProvider extends EcclesProvider {

	public static final String ID= "openai";
	public static final String CUSTOM_ID= "custom";
	public static final String DEFAULT_MODEL= "gpt-5.6-sol";
	public static final String DEFAULT_BASE= "https://api.openai.com/v1";

	private final boolean custom;

	public OpenAiProvider(String apiKey,String model,String baseUrl,boolean custom){
		super(apiKey,blank(model) && !custom ? DEFAULT_MODEL : model,
			blank(baseUrl) && !custom ? DEFAULT_BASE : baseUrl);
		this.custom= custom;
	}

	private static boolean blank(String s){
		return s == null || s.trim().isEmpty();
	}

	@Override public String id(){ return custom ? CUSTOM_ID : ID; }
	@Override public String label(){ return custom ? "Custom (OpenAI-compatible)" : "OpenAI"; }
	@Override public boolean keyOptional(){ return custom; }

	String url() throws AiException{
		String b= baseUrl;
		while(b.endsWith("/")) b= b.substring(0,b.length() - 1);
		if(b.isEmpty()) throw new AiException(400,"Set the base URL for the custom provider in Settings.",false);
		if(model.isEmpty()) throw new AiException(400,"Set the model name for the custom provider in Settings.",false);
		return b.endsWith("/chat/completions") ? b : b + "/chat/completions";
	}

	@Override
	public String generate(String system,String user,byte[] jpeg) throws AiException{
		requireKey();
		Map<String,String> h= new HashMap<>();
		if(!apiKey.isEmpty()) h.put("Authorization","Bearer " + apiKey);
		String resp= http.postJson(url(),h,buildBody(system,user,jpeg));
		return parseReply(resp);
	}

	String buildBody(String system,String user,byte[] jpeg) throws AiException{
		try{
			JSONArray content= new JSONArray();
			content.put(new JSONObject().put("type","text").put("text",user));
			if(jpeg != null){
				content.put(new JSONObject().put("type","image_url").put("image_url",
					new JSONObject().put("url","data:image/jpeg;base64," + b64(jpeg))));
			}
			JSONArray messages= new JSONArray();
			messages.put(new JSONObject().put("role","system").put("content",system));
			messages.put(new JSONObject().put("role","user").put("content",content));
			JSONObject body= new JSONObject();
			body.put("model",model);
			body.put("messages",messages);
			// Neither temperature nor max_tokens is sent: newer reasoning-class models reject
			// temperature and renamed max_tokens, and the defaults are fine for a short JSON reply.
			body.put("response_format",new JSONObject().put("type","json_object"));
			return body.toString();
		} catch (JSONException e){
			throw new AiException(-3,"Could not build request: " + e.getMessage(),false);
		}
	}

	static String parseReply(String resp) throws AiException{
		try{
			JSONObject r= new JSONObject(resp);
			JSONArray choices= r.optJSONArray("choices");
			if(choices == null || choices.length() == 0){
				throw new AiException(200,"The model returned no answer.",true);
			}
			JSONObject msg= choices.getJSONObject(0).optJSONObject("message");
			if(msg == null) throw new AiException(200,"The model returned an empty answer.",true);
			Object c= msg.opt("content");
			if(c instanceof JSONArray){
				StringBuilder sb= new StringBuilder();
				JSONArray a= (JSONArray) c;
				for(int i= 0;i < a.length();i++) sb.append(a.getJSONObject(i).optString("text",""));
				return sb.toString();
			}
			String s= msg.optString("content","");
			if(s.isEmpty()){
				String refusal= msg.optString("refusal","");
				throw new AiException(200,refusal.isEmpty() ? "The model returned an empty answer." : "The model refused: " + refusal,false);
			}
			return s;
		} catch (JSONException e){
			throw new AiException(200,"Unreadable response: " + e.getMessage(),true);
		}
	}
}
