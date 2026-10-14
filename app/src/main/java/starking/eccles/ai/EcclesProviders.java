package starking.eccles.ai;

/** Registry of the supported providers: ids, labels, default models, and construction. Pure Java. */
public final class EcclesProviders {

	private EcclesProviders(){
	}

	/** Gemini is the default: it is Google's own stack, and Android's native assistant lineage. */
	public static final String DEFAULT= GeminiProvider.ID;

	public static final String[] IDS= {
		GeminiProvider.ID,OpenAiProvider.ID,ClaudeProvider.ID,OpenAiProvider.CUSTOM_ID
	};

	public static String label(String id){
		switch(id){
			case OpenAiProvider.ID: return "OpenAI";
			case ClaudeProvider.ID: return "Anthropic Claude";
			case OpenAiProvider.CUSTOM_ID: return "Custom (OpenAI-compatible)";
			default: return "Google Gemini";
		}
	}

	public static String defaultModel(String id){
		switch(id){
			case OpenAiProvider.ID: return OpenAiProvider.DEFAULT_MODEL;
			case ClaudeProvider.ID: return ClaudeProvider.DEFAULT_MODEL;
			case OpenAiProvider.CUSTOM_ID: return "";
			default: return GeminiProvider.DEFAULT_MODEL;
		}
	}

	public static boolean keyOptional(String id){
		return OpenAiProvider.CUSTOM_ID.equals(id);
	}

	public static EcclesProvider create(String id,String apiKey,String model,String baseUrl){
		switch(id){
			case OpenAiProvider.ID: return new OpenAiProvider(apiKey,model,"",false);
			case ClaudeProvider.ID: return new ClaudeProvider(apiKey,model);
			case OpenAiProvider.CUSTOM_ID: return new OpenAiProvider(apiKey,model,baseUrl,true);
			default: return new GeminiProvider(apiKey,model);
		}
	}
}
