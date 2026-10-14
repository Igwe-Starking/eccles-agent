package starking.eccles.access;

/** One on-screen element, flattened out of the accessibility tree. Pure data. */
public class ScreenNode {

	public int index;
	public String cls= "";
	public String text= "";
	public String desc= "";
	public String id= "";
	public int left,top,right,bottom;
	public boolean clickable,longClickable,editable,scrollable,checkable,checked,focused,enabled= true,password;

	public int centerX(){
		return (left + right) / 2;
	}

	public int centerY(){
		return (top + bottom) / 2;
	}

	public boolean interactive(){
		return clickable || longClickable || editable || scrollable || checkable;
	}

	public boolean hasLabel(){
		return !text.isEmpty() || !desc.isEmpty();
	}

	/** Same element, judged by identity-ish fields (used to re-find it in a fresh tree). */
	public int similarity(ScreenNode o){
		int s= 0;
		if(!id.isEmpty() && id.equals(o.id)) s+= 4;
		if(!text.isEmpty() && text.equals(o.text)) s+= 3;
		if(!desc.isEmpty() && desc.equals(o.desc)) s+= 3;
		if(left == o.left && top == o.top && right == o.right && bottom == o.bottom) s+= 5;
		if(cls.equals(o.cls)) s+= 1;
		return s;
	}
}
