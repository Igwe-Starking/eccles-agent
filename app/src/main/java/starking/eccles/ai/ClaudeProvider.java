package starking.eccles.ai;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Anthropic Claude via the Messages API. */
public class ClaudeProvider extends EcclesProvider {

	public static final String ID= "claude";
	public static final String DEFAULT_MODEL= "claude-sonnet-5-5";
	private static final String ENDPOINT= "https://api.anthropic.com/v1/messages";
	private static final String VERSION= "2023-06-01";

	public ClaudeProvider(String apiKey,String model){
		super(apiKey,model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model,"");
	}

	@Override public String id(){ return ID; }
	@Override public String label(){ return "Anthropic Claude"; }

	@Override
	public String generate(String system,String user,byte[] jpeg) throws AiException{
		requireKey();
		Map<String,String> h= new HashMap<>();
		h.put("x-api-key",apiKey);
		h.put("anthropic-version",VERSION);
		String resp= http.postJson(ENDPOINT,h,buildBody(system,user,jpeg));
		return parseReply(resp);
	}

	String buildBody(String system,String user,byte[] jpeg) throws AiException{
		try{
			JSONArray content= new JSONArray();
			// Anthropic recommends the image before the text it relates to.
			if(jpeg != null){
				content.put(new JSONObject().put("type","image").put("source",new JSONObject()
					.put("type","base64").put("media_type","image/jpeg").put("data",b64(jpeg))));
			}
			content.put(new JSONObject().put("type","text").put("text",user));
			JSONObject body= new JSONObject();
			body.put("model",model);
			body.put("max_tokens",2048);
			body.put("system",system);
			body.put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",content)));
			return body.toString();
		} catch (JSONException e){
			throw new AiException(-3,"Could not build request: " + e.getMessage(),false);
		}
	}

	static String parseReply(String resp) throws AiException{
		try{
			JSONObject r= new JSONObject(resp);
			JSONArray content= r.optJSONArray("content");
			if(content == null || content.length() == 0){
				throw new AiException(200,"Claude returned an empty answer (" + r.optString("stop_reason","unknown") + ").",true);
			}
			StringBuilder sb= new StringBuilder();
			for(int i= 0;i < content.length();i++){
				JSONObject b= content.getJSONObject(i);
				if("text".equals(b.optString("type"))) sb.append(b.optString("text",""));
			}
			return sb.toString();
		} catch (JSONException e){
			throw new AiException(200,"Unreadable response from Claude: " + e.getMessage(),true);
		}
	}
}
