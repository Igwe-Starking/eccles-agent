package starking.eccles.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class AppMatcherTest {

	private static String best(String query,String[][] apps){
		long bestRank= 0;
		String best= null;
		for(String[] a : apps){
			long r= AppMatcher.rank(a[0],a[1],query);
			if(r > bestRank){ bestRank= r; best= a[1]; }
		}
		return best;
	}

	private static final String[][] APPS= {
		{"Facebook","com.facebook.katana"},
		{"Facebook Lite","com.facebook.lite"},
		{"Messenger","com.facebook.orca"},
		{"Google Maps","com.google.android.apps.maps"},
		{"Maps","com.example.othermaps"},
		{"WhatsApp","com.whatsapp"},
		{"YouTube","com.google.android.youtube"},
		{"Chrome","com.android.chrome"}
	};

	@Test
	public void exactLabelBeatsLongerLabelsThatContainIt(){
		assertEquals("com.facebook.katana",best("Facebook",APPS));
		assertEquals("com.facebook.katana",best("facebook",APPS));
		assertEquals("com.example.othermaps",best("Maps",APPS));
	}

	@Test
	public void toleratesPunctuationCaseAndTrailingWords(){
		assertEquals("com.whatsapp",best("whats app",APPS));
		assertEquals("com.google.android.youtube",best("you-tube",APPS));
		assertEquals("com.facebook.katana",best("Facebook app",APPS));
		assertEquals("com.android.chrome",best("open chrome",APPS));
	}

	@Test
	public void packageNameWorksAndShortQueriesDoNotMatchPackages(){
		assertEquals("com.whatsapp",best("com.whatsapp",APPS));
		assertEquals(null,best("zzz",APPS));
		assertEquals(0,AppMatcher.score("Camera","com.android.camera","ra"));
		assertEquals(0,AppMatcher.score("Camera","com.android.camera",""));
	}

	@Test
	public void veryShortQueriesMatchOnlyExactly(){
		assertEquals(0,AppMatcher.score("Google","com.google.android.googlequicksearchbox","go"));
		assertEquals(100,AppMatcher.score("X","com.twitter.android","x"));
		assertEquals(100,AppMatcher.score("Mi","com.mi.global","mi"));
		assertEquals(80,AppMatcher.score("Camera","com.android.camera","cam")); // three characters or more: prefix match
	}

	@Test
	public void suggestionsCatchTyposAndPartialNames(){
		List<String> labels= Arrays.asList("Facebook","Facebook Lite","WhatsApp","YouTube","Chrome","Messenger");
		assertEquals("WhatsApp",AppMatcher.suggest(labels,"Whatsap",3).get(0));
		assertEquals("Facebook",AppMatcher.suggest(labels,"Facbook",3).get(0));
		assertTrue(AppMatcher.suggest(labels,"face",3).contains("Facebook Lite"));
		assertTrue(AppMatcher.suggest(labels,"qqqqqqqq",3).isEmpty());
		assertTrue(AppMatcher.suggest(labels,"",3).isEmpty());
		assertEquals(2,AppMatcher.suggest(labels,"face",2).size());
	}

	@Test
	public void editDistance(){
		assertEquals(0,AppMatcher.distance("abc","abc"));
		assertEquals(1,AppMatcher.distance("abc","abd"));
		assertEquals(3,AppMatcher.distance("abc",""));
		assertEquals(1,AppMatcher.distance("whatsapp","whatsap"));
		assertFalse(AppMatcher.distance("chrome","firefox") <= 2);
	}
}
