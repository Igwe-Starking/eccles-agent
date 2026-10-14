package starking.eccles.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EcclesParserTest {

	@Test
	public void parsesTheFacebookExample() throws Exception{
		String raw= "{\"thought\":\"Open Facebook first\",\"status\":\"continue\",\"actions\":["
			+ "{\"type\":\"open_app\",\"app\":\"Facebook\"}]}";
		EcclesPlan p= EcclesParser.parse(raw);
		assertEquals(EcclesPlan.CONTINUE,p.status);
		assertEquals(1,p.actions.size());
		assertEquals(EcclesAction.OPEN_APP,p.actions.get(0).type);
		assertEquals("Facebook",p.actions.get(0).app);
	}

	@Test
	public void toleratesCodeFencesAndProse() throws Exception{
		String raw= "Sure! Here you go:\n```json\n{\"status\":\"done\",\"message\":\"Posted\"}\n```\nHope that helps.";
		EcclesPlan p= EcclesParser.parse(raw);
		assertEquals(EcclesPlan.DONE,p.status);
		assertEquals("Posted",p.message);
	}

	@Test
	public void clickWithOnlyCoordinatesBecomesTap() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"actions\":[{\"type\":\"click\",\"x\":540,\"y\":1200}]}");
		EcclesAction a= p.actions.get(0);
		assertEquals(EcclesAction.TAP,a.type);
		assertEquals(540,a.x);
		assertEquals(1200,a.y);
		assertEquals(100,a.durationMs);
	}

	@Test
	public void clampsDurations() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"actions\":["
			+ "{\"type\":\"long_press\",\"x\":1,\"y\":2,\"duration_ms\":999999},"
			+ "{\"type\":\"swipe\",\"x\":1,\"y\":2,\"x2\":3,\"y2\":4,\"duration_ms\":1},"
			+ "{\"type\":\"wait\",\"duration_ms\":600000}]}");
		assertEquals(5000,p.actions.get(0).durationMs);
		assertEquals(50,p.actions.get(1).durationMs);
		assertEquals(15000,p.actions.get(2).durationMs);
	}

	@Test
	public void parsesTargetObjectAndDecimals() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"actions\":[{\"type\":\"click\",\"target\":{\"index\":12.0,\"text\":\"Post\"}}]}");
		EcclesAction a= p.actions.get(0);
		assertEquals(12,a.index);
		assertEquals("Post",a.targetText);
	}

	@Test
	public void acceptsSingleActionObject() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"action\":{\"type\":\"key\",\"key\":\"back\"}}");
		assertEquals(EcclesAction.KEY_BACK,p.actions.get(0).key);
	}

	@Test
	public void mapsStatusSynonyms() throws Exception{
		assertEquals(EcclesPlan.DONE,EcclesParser.parse("{\"status\":\"Completed\"}").status);
		assertEquals(EcclesPlan.FAILED,EcclesParser.parse("{\"status\":\"error\",\"message\":\"x\"}").status);
		assertEquals(EcclesPlan.ASK_USER,EcclesParser.parse("{\"status\":\"needs input\",\"message\":\"which one?\"}").status);
	}

	@Test
	public void needVisionWithNoActionsIsValid() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"status\":\"continue\",\"need_vision\":true}");
		assertTrue(p.needVision);
		assertTrue(p.actions.isEmpty());
	}

	@Test
	public void typeKeepsUnicodeAndQuotes() throws Exception{
		EcclesPlan p= EcclesParser.parse("{\"actions\":[{\"type\":\"type\",\"text\":\"h\u00e9llo \\\"quoted\\\"\"}]}");
		assertEquals("h\u00e9llo \"quoted\"",p.actions.get(0).text);
	}

	@Test
	public void capsActionsPerStep() throws Exception{
		StringBuilder sb= new StringBuilder("{\"actions\":[");
		for(int i= 0;i < 20;i++){ if(i > 0) sb.append(','); sb.append("{\"type\":\"wait\"}"); }
		sb.append("]}");
		assertEquals(EcclesParser.MAX_ACTIONS_PER_STEP,EcclesParser.parse(sb.toString()).actions.size());
	}

	@Test(expected= EcclesParser.ParseException.class)
	public void rejectsUnknownActionType() throws Exception{
		EcclesParser.parse("{\"actions\":[{\"type\":\"rm_rf\"}]}");
	}

	@Test(expected= EcclesParser.ParseException.class)
	public void rejectsContinueWithNothingToDo() throws Exception{
		EcclesParser.parse("{\"status\":\"continue\",\"actions\":[]}");
	}

	@Test(expected= EcclesParser.ParseException.class)
	public void rejectsTapWithoutCoordinates() throws Exception{
		EcclesParser.parse("{\"actions\":[{\"type\":\"tap\"}]}");
	}

	@Test(expected= EcclesParser.ParseException.class)
	public void rejectsGarbage() throws Exception{
		EcclesParser.parse("I could not do that, sorry.");
	}

	@Test(expected= EcclesParser.ParseException.class)
	public void rejectsUnknownStatus() throws Exception{
		EcclesParser.parse("{\"status\":\"banana\",\"actions\":[{\"type\":\"wait\"}]}");
	}

	@Test
	public void describeNeverLeaksLongTypedText() throws Exception{
		EcclesAction a= new EcclesAction();
		a.type= EcclesAction.TYPE;
		StringBuilder sb= new StringBuilder();
		for(int i= 0;i < 200;i++) sb.append('a');
		a.text= sb.toString();
		assertTrue(a.describe().length() < 60);
		assertFalse(a.describe().contains(sb.toString()));
	}
}
