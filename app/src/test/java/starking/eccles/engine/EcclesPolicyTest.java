package starking.eccles.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EcclesPolicyTest {

	@Test
	public void changedFinishesUnavailableFallsBackNoChangeReplans(){
		assertEquals(EcclesPolicy.Next.DONE,EcclesPolicy.afterNodeAction(EcclesPolicy.CHANGED));
		assertEquals(EcclesPolicy.Next.GESTURE,EcclesPolicy.afterNodeAction(EcclesPolicy.UNAVAILABLE));
		// never gesture-retry an accepted click: it could double-activate a toggle/post
		assertEquals(EcclesPolicy.Next.REPLAN,EcclesPolicy.afterNodeAction(EcclesPolicy.NO_CHANGE));
	}

	@Test
	public void unknownOutcomeIsSafe(){
		assertEquals(EcclesPolicy.Next.GESTURE,EcclesPolicy.afterNodeAction(99));
	}

	@Test
	public void pressClassification(){
		assertTrue(EcclesPolicy.isTap(100));
		assertTrue(EcclesPolicy.isTap(300));
		assertFalse(EcclesPolicy.isTap(301));
		assertTrue(EcclesPolicy.isLongPress(400));
		assertTrue(EcclesPolicy.isLongPress(800));
		assertFalse(EcclesPolicy.isLongPress(399));
		// 301..399 is neither: a deliberate gesture, handled as a gesture
		assertFalse(EcclesPolicy.isTap(350) || EcclesPolicy.isLongPress(350));
	}

	@Test
	public void scrollDirections(){
		assertTrue(EcclesPolicy.scrollsForward("down"));
		assertTrue(EcclesPolicy.scrollsForward("right"));
		assertFalse(EcclesPolicy.scrollsForward("up"));
		assertFalse(EcclesPolicy.scrollsForward("left"));
	}

	@Test
	public void noChangeMessageTellsTheModelWhatToDo(){
		assertTrue(EcclesPolicy.NO_CHANGE_MESSAGE.contains("screenshot"));
	}
}
