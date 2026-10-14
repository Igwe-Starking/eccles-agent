package starking.eccles.Surface;

import java.util.concurrent.atomic.AtomicLong;

/** One row of the task timeline. Mutable on purpose: a step is shown as running, then updated in place. */
public class EcclesPojo {

	public static final int TASK= 0;
	public static final int STEP= 1;
	public static final int RESULT= 2;

	public static final int RUNNING= 0;
	public static final int OK= 1;
	public static final int FAIL= 2;
	public static final int INFO= 3;
	public static final int WARN= 4;

	private static final AtomicLong IDS= new AtomicLong(1);

	public final long id= IDS.getAndIncrement();
	public final int type;
	public volatile int state;
	public volatile String title;
	public volatile String detail;
	public final long time= System.currentTimeMillis();

	public EcclesPojo(int type,int state,String title,String detail){
		this.type= type;
		this.state= state;
		this.title= title;
		this.detail= detail == null ? "" : detail;
	}

	public static EcclesPojo task(String goal){
		return new EcclesPojo(TASK,INFO,goal,"");
	}

	public static EcclesPojo step(String title,String detail){
		return new EcclesPojo(STEP,RUNNING,title,detail);
	}

	public static EcclesPojo note(String title,String detail){
		return new EcclesPojo(STEP,INFO,title,detail);
	}

	public static EcclesPojo result(int state,String title,String detail){
		return new EcclesPojo(RESULT,state,title,detail);
	}
}
