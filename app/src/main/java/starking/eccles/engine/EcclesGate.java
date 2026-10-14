package starking.eccles.engine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot rendezvous between the engine thread (which must wait) and whichever UI answers a
 * confirmation first: the on-screen overlay buttons or the notification actions.
 * Silence or any failure means "deny": the safe default for an irreversible action.
 */
public class EcclesGate {

	private volatile CountDownLatch latch;
	private volatile boolean allowed;

	public synchronized void open(){
		allowed= false;
		latch= new CountDownLatch(1);
	}

	public void resolve(boolean allow){
		CountDownLatch l= latch;
		if(l == null) return;
		allowed= allow;
		l.countDown();
	}

	public boolean await(long timeoutMs){
		CountDownLatch l= latch;
		if(l == null) return false;
		try{
			return l.await(timeoutMs,TimeUnit.MILLISECONDS) && allowed;
		} catch (InterruptedException e){
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
