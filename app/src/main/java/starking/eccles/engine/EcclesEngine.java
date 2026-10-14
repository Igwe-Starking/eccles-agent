package starking.eccles.engine;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.agent.service.Caller;
import starking.eccles.agent.service.Reader;
import starking.eccles.ai.EcclesProvider;
import starking.eccles.data.EcclesStorage;
import starking.eccles.guard.EcclesGuard;
import starking.eccles.guard.EcclesRules;

/**
 * The agent loop: observe the screen, ask the AI for the next actions, execute them, repeat.
 * <p>
 * Reliability rules baked in:
 * <ul>
 *   <li>The guard is consulted before reading, before capturing, and before every action.</li>
 *   <li>A failed action stops the batch and forces a screenshot next step, so the model can give
 *       exact coordinates when the accessibility route did not work.</li>
 *   <li>The model's "done" is only believed when it comes with no actions (i.e. after it saw the result).</li>
 *   <li>Unparseable replies get one repair attempt; repeated identical steps are detected and stopped.</li>
 *   <li>Irreversible-looking actions need an explicit Allow (setting), and silence means Deny.</li>
 * </ul>
 */
public class EcclesEngine {

	private static final String TAG= "EcclesEngine";
	private static final long CONFIRM_TIMEOUT_MS= 60000;

	public interface Callback {
		/** Adds the entry, or refreshes it if the same object was delivered before. */
		void onEntry(EcclesPojo p);

		/** Short live status for the overlay/notification. */
		void onStatus(String text);

		/** Blocks until the user answers; true = allow. */
		boolean onConfirm(String question);
	}

	public enum Outcome { SUCCESS,FAILED,CANCELLED,BLOCKED,NEEDS_INPUT }

	public static class Result {
		public final Outcome outcome;
		public final String message;
		public final int steps;

		public Result(Outcome o,String m,int s){
			outcome= o;
			message= m;
			steps= s;
		}
	}

	private final Context ctx;
	private final EcclesRouter router;
	private final Reader reader;
	private final Caller caller;
	private final Callback cb;
	private volatile boolean cancelled;

	public EcclesEngine(Context c,EcclesRouter router,Reader reader,Caller caller,Callback cb){
		this.ctx= c.getApplicationContext();
		this.router= router;
		this.reader= reader;
		this.caller= caller;
		this.cb= cb;
	}

	public void cancel(){
		cancelled= true;
		caller.abort();
	}

	public Result run(String goal){
		final int max= EcclesStorage.maxSteps(ctx);
		final long delay= EcclesStorage.stepDelayMs(ctx);
		final String vision= EcclesStorage.visionMode(ctx);
		final List<String> history= new ArrayList<>();

		cb.onEntry(EcclesPojo.task(goal));

		if(!router.anyAvailable()){
			return end(Outcome.FAILED,"I have no way to control the phone. Turn on the Eccles Agent accessibility service in Android settings, or set up ADB.",0);
		}
		if(!router.hasTree() && vision.equals(EcclesStorage.VISION_NEVER)){
			return end(Outcome.FAILED,"Screenshots are turned off, but without the accessibility service the agent can only work from screenshots. Change this in Settings.",0);
		}
		cb.onEntry(EcclesPojo.note("Using " + router.describe() + " · " + caller.label(),""));

		boolean wantVision= vision.equals(EcclesStorage.VISION_ALWAYS);
		boolean listApps= true; // exact names make open_app far more reliable; sent on step 1 and after a failed open
		String lastSig= "";
		int repeats= 0;
		int done= 0;

		for(int step= 1;step <= max;step++){
			if(cancelled) return end(Outcome.CANCELLED,"Stopped.",done);

			cb.onStatus("Step " + step + " · looking at the screen");
			Reader.Observation obs= reader.observe(wantVision);
			if(cancelled) return end(Outcome.CANCELLED,"Stopped.",done);
			if(obs.blockedReason != null){
				return end(Outcome.BLOCKED,obs.blockedReason + " I did not read or send anything from that screen. Switch to another app and ask again.",done);
			}
			if(obs.sensitiveScreen){
				return end(Outcome.NEEDS_INPUT,"A password field is on screen, so I stopped before reading it. Sign in yourself, then ask me again.",done);
			}
			wantVision= vision.equals(EcclesStorage.VISION_ALWAYS);

			cb.onStatus("Step " + step + " · thinking");
			String apps= null;
			if(listApps){
				apps= installedAppsLine();
				listApps= false;
			}
			String prompt= EcclesPrompt.user(goal,step,max,history,obs.screenText,obs.jpeg != null,obs.obsW,obs.obsH,obs.note,apps);
			EcclesPlan plan;
			try{
				plan= plan(prompt,obs.jpeg);
			} catch (EcclesProvider.AiException e){
				if(cancelled) return end(Outcome.CANCELLED,"Stopped.",done);
				return end(Outcome.FAILED,e.getMessage(),done);
			} catch (EcclesParser.ParseException e){
				return end(Outcome.FAILED,"The AI kept answering in a format I cannot use (" + e.getMessage() + "). Try again, or pick a different model in Settings.",done);
			}
			if(cancelled) return end(Outcome.CANCELLED,"Stopped.",done);

			if(!plan.thought.isEmpty()) cb.onEntry(EcclesPojo.note(plan.thought,""));

			if(plan.status.equals(EcclesPlan.FAILED)){
				return end(Outcome.FAILED,orElse(plan.message,"The AI could not complete the task."),done);
			}
			if(plan.status.equals(EcclesPlan.ASK_USER)){
				return end(Outcome.NEEDS_INPUT,orElse(plan.message,"I need more information to continue."),done);
			}
			if(plan.status.equals(EcclesPlan.DONE) && plan.actions.isEmpty()){
				return end(Outcome.SUCCESS,orElse(plan.message,"Done."),done);
			}
			if(plan.actions.isEmpty()){
				// need_vision with nothing to do: grant it and look again.
				if(vision.equals(EcclesStorage.VISION_NEVER)){
					history.add(step + ". (asked for a screenshot, but screenshots are turned off)");
				} else {
					wantVision= true;
					history.add(step + ". (asked for a screenshot)");
				}
				continue;
			}
			if(plan.needVision && !vision.equals(EcclesStorage.VISION_NEVER)) wantVision= true;

			// The image is part of the signature: without an accessibility tree (ADB-only) the screen text
			// is constant, and scrolling the same feed three times must not look like being stuck.
			String sig= obs.pkg + "|" + obs.screenText.hashCode() + "|" + java.util.Arrays.hashCode(obs.jpeg) + "|" + signature(plan);
			repeats= sig.equals(lastSig) ? repeats + 1 : 0;
			lastSig= sig;
			if(repeats >= 2){
				return end(Outcome.FAILED,"I kept repeating the same action without the screen changing, so I stopped.",done);
			}

			for(int i= 0;i < plan.actions.size();i++){
				if(cancelled) return end(Outcome.CANCELLED,"Stopped.",done);
				EcclesAction act= plan.actions.get(i).toScreen(obs.scale);

				// Re-check the foreground app before EVERY action: a previous action may have navigated somewhere off-limits.
				String fg= router.foregroundPackage();
				if(fg != null){
					EcclesGuard.Decision d= EcclesGuard.check(ctx,fg);
					boolean selfLaunchPad= fg.equals(ctx.getPackageName()) && isNavigation(act);
					if(d.blocked && !selfLaunchPad){
						return end(Outcome.BLOCKED,d.reason + " I stopped before doing anything there.",done);
					}
				}

				if(needsConfirm(act)){
					cb.onStatus("Waiting for your confirmation");
					String q= "Eccles wants to: " + act.describe() + (obs.label.isEmpty() ? "" : " in " + obs.label) + "\nTask: " + goal;
					if(!cb.onConfirm(q)) return end(Outcome.CANCELLED,"You declined: " + act.describe(),done);
				}

				EcclesPojo row= EcclesPojo.step(act.describe(),"");
				cb.onEntry(row);
				cb.onStatus(act.describe());
				EcclesResult r;
				if(act.type.equals(EcclesAction.WAIT)){
					SystemClock.sleep(act.durationMs);
					r= EcclesResult.ok();
				} else {
					r= router.execute(act);
				}
				row.state= r.ok ? EcclesPojo.OK : EcclesPojo.FAIL;
				row.detail= r.detail;
				cb.onEntry(row);
				history.add(step + "." + (i + 1) + " " + act.describe() + " -> " + (r.ok ? "ok" : "FAILED: " + r.detail));

				if(!r.ok && act.type.equals(EcclesAction.OPEN_APP)) listApps= true;
				if(!r.ok){
					// The remaining actions assumed this one worked. Give the model a screenshot and let it re-plan.
					if(!vision.equals(EcclesStorage.VISION_NEVER)) wantVision= true;
					if(i + 1 < plan.actions.size()) history.add(step + ". (remaining actions skipped after the failure)");
					break;
				}
			}
			done++;
			router.settle(delay);
		}
		return end(Outcome.FAILED,"I reached the step limit (" + max + ") before finishing. You can raise it in Settings.",done);
	}

	/** Allowed app names, capped so the prompt stays small. Excluded apps are never listed. */
	private String installedAppsLine(){
		try{
			List<String> labels= AppResolver.installedLabels(ctx);
			StringBuilder sb= new StringBuilder();
			int n= 0;
			for(String l : labels){
				if(n++ >= 150) break;
				if(sb.length() > 0) sb.append(", ");
				sb.append(l);
			}
			return sb.toString();
		} catch (Exception e){
			Log.w(TAG,"could not list apps: " + e.getMessage());
			return null;
		}
	}

	/** One repair attempt for malformed replies; transport errors are the Caller's job. */
	private EcclesPlan plan(String prompt,byte[] jpeg) throws EcclesProvider.AiException,EcclesParser.ParseException{
		String raw= caller.call(EcclesPrompt.SYSTEM,prompt,jpeg);
		try{
			return EcclesParser.parse(raw);
		} catch (EcclesParser.ParseException first){
			Log.w(TAG,"unusable reply: " + first.getMessage());
			String repair= prompt + "\n\nYour previous reply could not be used (" + first.getMessage()
				+ "). Reply again with ONLY the JSON object described in the instructions.";
			return EcclesParser.parse(caller.call(EcclesPrompt.SYSTEM,repair,jpeg));
		}
	}

	/** Actions that only move away from the current screen; the sole thing allowed on a blocked launch pad. */
	static boolean isNavigation(EcclesAction a){
		return a.type.equals(EcclesAction.OPEN_APP) || a.type.equals(EcclesAction.KEY) || a.type.equals(EcclesAction.WAIT);
	}

	private boolean needsConfirm(EcclesAction a){
		if(!EcclesStorage.confirmSensitive(ctx)) return false;
		if(a.sensitive) return true;
		if(a.type.equals(EcclesAction.CLICK)) return EcclesRules.looksIrreversible(router.labelOf(a));
		return false;
	}

	private static String signature(EcclesPlan p){
		StringBuilder sb= new StringBuilder();
		for(EcclesAction a : p.actions) sb.append(a.describe()).append(';');
		return sb.toString();
	}

	private static String orElse(String s,String d){
		return s == null || s.trim().isEmpty() ? d : s;
	}

	private Result end(Outcome o,String msg,int steps){
		int state= o == Outcome.SUCCESS ? EcclesPojo.OK
			: (o == Outcome.CANCELLED || o == Outcome.NEEDS_INPUT || o == Outcome.BLOCKED) ? EcclesPojo.WARN : EcclesPojo.FAIL;
		String title;
		switch(o){
			case SUCCESS: title= "Done"; break;
			case CANCELLED: title= "Stopped"; break;
			case BLOCKED: title= "Stopped for your protection"; break;
			case NEEDS_INPUT: title= "Needs your input"; break;
			default: title= "Could not finish";
		}
		cb.onEntry(EcclesPojo.result(state,title,msg));
		return new Result(o,msg,steps);
	}
}
