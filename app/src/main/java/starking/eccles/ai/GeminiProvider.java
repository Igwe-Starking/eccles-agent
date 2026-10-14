package starking.eccles.ai;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Google Gemini via the Generative Language API. This is the default provider. */
public class GeminiProvider extends EcclesProvider {

	public static final String ID= "gemini";
	public static final String DEFAULT_MODEL= "gemini-3.5-flash";
	private static final String ENDPOINT= "https://generativelanguage.googleapis.com/v1beta/models/";

	public GeminiProvider(String apiKey,String model){
		super(apiKey,model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model,"");
	}

	@Override public String id(){ return ID; }
	@Override public String label(){ return "Google Gemini"; }

	@Override
	public String generate(String system,String user,byte[] jpeg) throws AiException{
		requireKey();
		Map<String,String> h= new HashMap<>();
		// Header rather than ?key= so the key never lands in a URL (logs, proxies, crash reports).
		h.put("x-goog-api-key",apiKey);
		String resp= http.postJson(ENDPOINT + model + ":generateContent",h,buildBody(system,user,jpeg));
		return parseReply(resp);
	}

	String buildBody(String system,String user,byte[] jpeg) throws AiException{
		try{
			JSONArray parts= new JSONArray();
			parts.put(new JSONObject().put("text",user));
			if(jpeg != null){
				parts.put(new JSONObject().put("inline_data",new JSONObject()
					.put("mime_type","image/jpeg")
					.put("data",b64(jpeg))));
			}
			JSONObject body= new JSONObject();
			body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system))));
			body.put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",parts)));
			// No temperature override: Google recommends the default for Gemini 3 models, and
			// forcing it low can degrade them. JSON mode is what guarantees parseable output.
			body.put("generationConfig",new JSONObject().put("responseMimeType","application/json"));
			return body.toString();
		} catch (JSONException e){
			throw new AiException(-3,"Could not build request: " + e.getMessage(),false);
		}
	}

	static String parseReply(String resp) throws AiException{
		try{
			JSONObject r= new JSONObject(resp);
			JSONArray cands= r.optJSONArray("candidates");
			if(cands == null || cands.length() == 0){
				JSONObject fb= r.optJSONObject("promptFeedback");
				String reason= fb == null ? "" : fb.optString("blockReason","");
				throw new AiException(200,"Gemini returned no answer" + (reason.isEmpty() ? "." : " (blocked: " + reason + ")."),false);
			}
			JSONObject content= cands.getJSONObject(0).optJSONObject("content");
			JSONArray parts= content == null ? null : content.optJSONArray("parts");
			if(parts == null || parts.length() == 0){
				String fin= cands.getJSONObject(0).optString("finishReason","");
				throw new AiException(200,"Gemini returned an empty answer" + (fin.isEmpty() ? "." : " (" + fin + ")."),true);
			}
			StringBuilder sb= new StringBuilder();
			for(int i= 0;i < parts.length();i++){
				JSONObject p= parts.getJSONObject(i);
				if(p.optBoolean("thought",false)) continue;
				sb.append(p.optString("text",""));
			}
			return sb.toString();
		} catch (JSONException e){
			throw new AiException(200,"Unreadable response from Gemini: " + e.getMessage(),true);
		}
	}
}
