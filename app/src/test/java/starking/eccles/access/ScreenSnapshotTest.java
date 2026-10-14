package starking.eccles.access;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class ScreenSnapshotTest {

	private static ScreenNode node(String text,String desc,String id,boolean click,int l,int t,int r,int b){
		ScreenNode n= new ScreenNode();
		n.text= text; n.desc= desc; n.id= id; n.clickable= click;
		n.cls= "android.widget.Button";
		n.left= l; n.top= t; n.right= r; n.bottom= b;
		return n;
	}

	@Test
	public void interactiveNodesSurviveTheBudgetAndOrderIsKept(){
		List<ScreenNode> raw= new ArrayList<>();
		for(int i= 0;i < 10;i++) raw.add(node("label" + i,"","",false,0,i,1,i + 1));
		raw.add(node("Post","","com.fb:id/post",true,5,5,9,9));
		for(int i= 0;i < 10;i++) raw.add(node("tail" + i,"","",false,0,i,1,i + 1));
		ScreenSnapshot s= ScreenSnapshot.build("com.fb",1080,2400,raw,5);
		assertEquals(5,s.nodes.size());
		assertEquals(16,s.hiddenCount);
		boolean found= false;
		for(int i= 0;i < s.nodes.size();i++){
			assertEquals(i,s.nodes.get(i).index);
			if("Post".equals(s.nodes.get(i).text)) found= true;
		}
		assertTrue("interactive node must not be dropped",found);
		assertEquals("label0",s.nodes.get(0).text);
	}

	@Test
	public void resolvePrefersIndexWhenConsistent(){
		List<ScreenNode> raw= new ArrayList<>();
		raw.add(node("Cancel","","",true,0,0,1,1));
		raw.add(node("Post","","",true,0,2,1,3));
		ScreenSnapshot s= ScreenSnapshot.build("p",100,100,raw,10);
		assertEquals("Post",s.resolve(1,"Post",null,null).text);
		assertEquals("Post",s.resolve(0,"Post",null,null).text);
	}

	@Test
	public void resolveByIdSuffixDescAndContains(){
		List<ScreenNode> raw= new ArrayList<>();
		raw.add(node("","Compose new post","com.fb:id/composer_button",true,0,0,1,1));
		raw.add(node("What's on your mind?","","com.fb:id/status",false,0,2,1,3));
		ScreenSnapshot s= ScreenSnapshot.build("p",100,100,raw,10);
		assertEquals("com.fb:id/composer_button",s.resolve(-1,null,null,"composer_button").id);
		assertEquals("com.fb:id/composer_button",s.resolve(-1,null,"compose new post",null).id);
		assertEquals("com.fb:id/status",s.resolve(-1,"on your mind",null,null).id);
		assertNull(s.resolve(-1,"nothing like this",null,null));
	}

	@Test
	public void promptNeutralisesHostileText(){
		List<ScreenNode> raw= new ArrayList<>();
		raw.add(node("line1\nline2 | \"quoted\"","","",true,0,0,10,10));
		String p= ScreenSnapshot.build("p",100,100,raw,10).toPrompt();
		String[] lines= p.split("\n");
		assertEquals(3,lines.length);
		assertFalse(lines[2].contains("\""));
		assertEquals(7,lines[2].split("\\|",-1).length);
	}

	@Test
	public void passwordFlagIsDetectedAndSparseDetection(){
		List<ScreenNode> raw= new ArrayList<>();
		ScreenNode pw= node("","","",false,0,0,1,1);
		pw.password= true; pw.editable= true;
		raw.add(pw);
		ScreenSnapshot s= ScreenSnapshot.build("p",100,100,raw,10);
		assertTrue(s.hasPassword);
		assertTrue(s.looksSparse());
		assertNotNull(s.byIndex(0));
	}

	@Test
	public void similarityFindsTheSameNodeInAFreshTree(){
		ScreenNode a= node("Post","","com.fb:id/post",true,5,5,9,9);
		ScreenNode same= node("Post","","com.fb:id/post",true,5,5,9,9);
		ScreenNode other= node("Cancel","","com.fb:id/cancel",true,0,0,3,3);
		assertTrue(a.similarity(same) > a.similarity(other));
	}
}
