package starking.eccles.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import starking.eccles.access.ScreenNode;
import starking.eccles.access.ScreenSnapshot;

/**
 * The model works in "observation space": the downscaled screenshot when one is attached, the
 * real screen otherwise. These tests pin down that the same space is used for what it SEES
 * (element bounds in the prompt) and what it ANSWERS (x,y), and that answers map back to real pixels.
 */
public class EcclesCoordinatesTest {

	@Test
	public void toScreenDividesByScaleAndRounds() throws Exception{
		// 1080x2400 screen shown as a 540x1200 image: scale 0.5
		EcclesAction a= EcclesParser.parse("{\"actions\":[{\"type\":\"tap\",\"x\":270,\"y\":600}]}").actions.get(0);
		EcclesAction s= a.toScreen(0.5);
		assertEquals(540,s.x);
		assertEquals(1200,s.y);
		assertEquals("original must be untouched",270,a.x);
	}

	@Test
	public void toScreenMapsAllFourSwipeCoordinatesAndKeepsDuration() throws Exception{
		EcclesAction a= EcclesParser.parse("{\"actions\":[{\"type\":\"swipe\",\"x\":100,\"y\":900,\"x2\":100,\"y2\":300,\"duration_ms\":450}]}").actions.get(0);
		EcclesAction s= a.toScreen(0.4);
		assertEquals(250,s.x);
		assertEquals(2250,s.y);
		assertEquals(250,s.x2);
		assertEquals(750,s.y2);
		assertEquals(450,s.durationMs);
	}

	@Test
	public void identityScaleAndNonPointActionsAreUnchanged() throws Exception{
		EcclesAction open= EcclesParser.parse("{\"actions\":[{\"type\":\"open_app\",\"app\":\"Facebook\"}]}").actions.get(0);
		assertTrue(open.toScreen(0.5) != null && open.toScreen(0.5).x == -1);
		EcclesAction tap= EcclesParser.parse("{\"actions\":[{\"type\":\"tap\",\"x\":7,\"y\":9}]}").actions.get(0);
		assertTrue(tap.toScreen(1.0) == tap);
		assertTrue(tap.toScreen(0) == tap);
	}

	@Test
	public void promptBoundsAndAnswersRoundTripThroughTheSameScale(){
		ScreenNode n= new ScreenNode();
		n.text= "Post"; n.clickable= true; n.cls= "android.widget.Button";
		n.left= 800; n.top= 2000; n.right= 1000; n.bottom= 2100; // real screen px
		List<ScreenNode> raw= new ArrayList<>();
		raw.add(n);
		ScreenSnapshot s= ScreenSnapshot.build("com.fb",1080,2400,raw,10);
		double scale= 540.0 / 1080.0;
		String prompt= s.toPrompt(scale);
		assertTrue(prompt.contains("screen=540x1200"));
		assertTrue("bounds are shown in image space",prompt.contains("400,1000,500,1050"));
		// the model taps the centre of what it saw: (450,1025) -> real (900,2050), inside the real button
		EcclesAction tap= new EcclesAction();
		tap.type= EcclesAction.TAP; tap.x= 450; tap.y= 1025; tap.durationMs= 50;
		EcclesAction real= tap.toScreen(scale);
		assertTrue(real.x >= n.left && real.x <= n.right);
		assertTrue(real.y >= n.top && real.y <= n.bottom);
	}

	@Test
	public void userPromptStatesTheCoordinateSpaceAndTrimsHistory(){
		List<String> h= new ArrayList<>();
		for(int i= 1;i <= 30;i++) h.add(i + ". step");
		String p= EcclesPrompt.user("Open Facebook",31,40,h,"SCREEN",true,540,1200,"",null);
		assertTrue(p.contains("GOAL: Open Facebook"));
		assertTrue(p.contains("540x1200"));
		assertTrue(p.contains("IMAGE of the current screen is attached"));
		assertTrue(p.contains("(18 earlier steps omitted)"));
		assertTrue(p.contains("30. step"));
		assertTrue(!p.contains("\n1. step\n"));
		String noImg= EcclesPrompt.user("x",1,5,new ArrayList<String>(),"S",false,1080,2400,"",null);
		assertTrue(noImg.contains("No image is attached"));
		assertTrue(noImg.contains("(none yet)"));
		assertTrue(!noImg.contains("INSTALLED APPS"));
	}

	@Test
	public void installedAppsAreListedWhenProvidedAndTheRuleExists(){
		String p= EcclesPrompt.user("x",1,5,new ArrayList<String>(),"S",false,1080,2400,"","Chrome, Facebook, WhatsApp");
		assertTrue(p.contains("INSTALLED APPS (names for open_app): Chrome, Facebook, WhatsApp"));
		assertTrue(EcclesPrompt.SYSTEM.contains("INSTALLED APPS"));
	}

	@Test
	public void systemPromptDefinesEveryActionTheParserAccepts(){
		for(String t : new String[]{"open_app","click","tap","long_press","swipe","type","key","scroll","wait"}){
			assertTrue("system prompt must teach " + t,EcclesPrompt.SYSTEM.contains("\"" + t + "\""));
		}
		assertTrue(EcclesPrompt.SYSTEM.contains("untrusted"));
	}
}
