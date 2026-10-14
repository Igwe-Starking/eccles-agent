package starking.eccles.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/** Lives in the same package as the providers to reach their package-private request/response code. */
public class ProviderBodyTest {

	private static final byte[] IMG= {1,2,3,4};

	@Test
	public void geminiRequestShape() throws Exception{
		GeminiProvider g= new GeminiProvider("k","");
		assertEquals(GeminiProvider.DEFAULT_MODEL,g.model());
		JSONObject b= new JSONObject(g.buildBody("SYS","USER",IMG));
		assertEquals("SYS",b.optJSONObject("systemInstruction").optJSONArray("parts").optJSONObject(0).optString("text"));
		assertEquals("application/json",b.optJSONObject("generationConfig").optString("responseMimeType"));
		assertFalse("no temperature override",b.optJSONObject("generationConfig").has("temperature"));
		org.json.JSONArray parts= b.optJSONArray("contents").optJSONObject(0).optJSONArray("parts");
		assertEquals(2,parts.length());
		assertEquals("image/jpeg",parts.optJSONObject(1).optJSONObject("inline_data").optString("mime_type"));
		assertEquals(java.util.Base64.getEncoder().encodeToString(IMG),parts.optJSONObject(1).optJSONObject("inline_data").optString("data"));
	}

	@Test
	public void geminiTextOnlyHasNoImagePart() throws Exception{
		JSONObject b= new JSONObject(new GeminiProvider("k","m").buildBody("S","U",null));
		assertEquals(1,b.optJSONArray("contents").optJSONObject(0).optJSONArray("parts").length());
	}

	@Test
	public void geminiParsesAndSkipsThoughtParts() throws Exception{
		String r= "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"{\\\"status\\\":\\\"done\\\"}\"}]}}]}";
		assertEquals("{\"status\":\"done\"}",GeminiProvider.parseReply(r));
	}

	@Test
	public void geminiBlockedPromptIsNotRetryable() throws Exception{
		try{
			GeminiProvider.parseReply("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}");
			throw new AssertionError("should have thrown");
		} catch (EcclesProvider.AiException e){
			assertFalse(e.retryable);
			assertTrue(e.getMessage().contains("SAFETY"));
		}
	}

	@Test
	public void openAiRequestShapeAndUrl() throws Exception{
		OpenAiProvider o= new OpenAiProvider("k","","",false);
		assertEquals(OpenAiProvider.DEFAULT_MODEL,o.model());
		assertEquals("https://api.openai.com/v1/chat/completions",o.url());
		JSONObject b= new JSONObject(o.buildBody("SYS","USER",IMG));
		assertEquals("json_object",b.optJSONObject("response_format").optString("type"));
		assertFalse(b.has("temperature"));
		assertFalse(b.has("max_tokens"));
		org.json.JSONArray content= b.optJSONArray("messages").optJSONObject(1).optJSONArray("content");
		assertTrue(content.optJSONObject(1).optJSONObject("image_url").optString("url").startsWith("data:image/jpeg;base64,"));
	}

	@Test
	public void customProviderNeedsBaseUrlAndModelButNotKey() throws Exception{
		OpenAiProvider c= new OpenAiProvider("","","",true);
		assertTrue(c.keyOptional());
		try{ c.url(); throw new AssertionError("expected failure"); } catch (EcclesProvider.AiException e){ assertFalse(e.retryable); }
		OpenAiProvider ok= new OpenAiProvider("","llama3","http://localhost:11434/v1/",true);
		assertEquals("http://localhost:11434/v1/chat/completions",ok.url());
	}

	@Test
	public void openAiParsesStringAndArrayContent() throws Exception{
		assertEquals("hi",OpenAiProvider.parseReply("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));
		assertEquals("ab",OpenAiProvider.parseReply("{\"choices\":[{\"message\":{\"content\":[{\"text\":\"a\"},{\"text\":\"b\"}]}}]}"));
	}

	@Test
	public void claudeRequestShapePutsImageFirst() throws Exception{
		ClaudeProvider c= new ClaudeProvider("k","");
		assertEquals(ClaudeProvider.DEFAULT_MODEL,c.model());
		JSONObject b= new JSONObject(c.buildBody("SYS","USER",IMG));
		assertEquals("SYS",b.optString("system"));
		org.json.JSONArray content= b.optJSONArray("messages").optJSONObject(0).optJSONArray("content");
		assertEquals("image",content.optJSONObject(0).optString("type"));
		assertEquals("text",content.optJSONObject(1).optString("type"));
		assertEquals("image/jpeg",content.optJSONObject(0).optJSONObject("source").optString("media_type"));
	}

	@Test
	public void claudeParsesTextBlocks() throws Exception{
		assertEquals("xy",ClaudeProvider.parseReply("{\"content\":[{\"type\":\"text\",\"text\":\"x\"},{\"type\":\"text\",\"text\":\"y\"}]}"));
	}

	@Test
	public void errorMessagesAreHumanAndKeyedByStatus(){
		String m401= EcclesHttp.describe(401,"{\"error\":{\"message\":\"API key not valid\"}}");
		assertTrue(m401.contains("API key") && m401.contains("Settings"));
		assertTrue(EcclesHttp.describe(404,"").contains("model"));
		assertTrue(EcclesHttp.describe(429,"{}").contains("Rate limit"));
		assertTrue(EcclesHttp.describe(503,"oops").contains("503"));
	}

	@Test
	public void missingKeyFailsFastWithoutNetwork() throws Exception{
		try{
			new GeminiProvider("","").generate("s","u",null);
			throw new AssertionError("expected failure");
		} catch (EcclesProvider.AiException e){
			assertEquals(401,e.code);
			assertFalse(e.retryable);
		}
	}
}
