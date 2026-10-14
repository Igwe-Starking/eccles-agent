package starking.eccles.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Set;
import starking.eccles.data.EcclesStorage;

/**
 * Decides whether the agent may look at or operate an app. This is the single choke point:
 * the engine consults it before reading the screen, before capturing a screenshot, before
 * opening an app, and again before every action, so nothing from an excluded app is ever
 * read, captured or sent to the AI.
 * <p>
 * Layers, in order: hard blocks (own app, permission dialogs, installer) that no setting can
 * lift; the user's explicit "allowed" overrides; the user's excluded list (pre-seeded with
 * well-known banking/payment apps); and finally the "smart detect" heuristic for the long tail.
 */
public class EcclesGuard {

	public static class Decision {
		public final boolean blocked;
		public final String reason;
		/** The app's display name, when it could be resolved. */
		public final String label;

		Decision(boolean blocked,String reason,String label){
			this.blocked= blocked;
			this.reason= reason;
			this.label= label;
		}

		static final Decision ALLOW= new Decision(false,"","");
	}

	private static final String FILE= "eccles_guard";
	private static final String K_EXCLUDED= "excluded";
	private static final String K_ALLOWED= "allowed";
	private static final String K_SEEDED= "seeded";

	private static SharedPreferences sp(Context c){
		return c.getSharedPreferences(FILE,Context.MODE_PRIVATE);
	}

	/** Adds the seed list once. Later removals by the user are respected and never re-seeded. */
	public static synchronized void seedIfNeeded(Context c){
		SharedPreferences p= sp(c);
		if(p.getBoolean(K_SEEDED,false)) return;
		Set<String> ex= excluded(c);
		for(String s : EcclesRules.SEED) ex.add(s);
		p.edit().putStringSet(K_EXCLUDED,ex).putBoolean(K_SEEDED,true).apply();
	}

	public static Set<String> excluded(Context c){
		return new HashSet<>(sp(c).getStringSet(K_EXCLUDED,new HashSet<String>()));
	}

	public static Set<String> allowed(Context c){
		return new HashSet<>(sp(c).getStringSet(K_ALLOWED,new HashSet<String>()));
	}

	/** Marks a package as excluded (true) or explicitly allowed (false). */
	public static synchronized void setExcluded(Context c,String pkg,boolean excluded){
		Set<String> ex= excluded(c);
		Set<String> al= allowed(c);
		if(excluded){
			ex.add(pkg);
			al.remove(pkg);
		} else {
			ex.remove(pkg);
			al.add(pkg); // so smart-detect does not immediately re-block it
		}
		sp(c).edit().putStringSet(K_EXCLUDED,ex).putStringSet(K_ALLOWED,al).apply();
	}

	/** Restores the default list: seed apps excluded, all overrides cleared. */
	public static synchronized void reset(Context c){
		Set<String> ex= new HashSet<>();
		for(String s : EcclesRules.SEED) ex.add(s);
		sp(c).edit().putStringSet(K_EXCLUDED,ex).putStringSet(K_ALLOWED,new HashSet<String>()).putBoolean(K_SEEDED,true).apply();
	}

	public static String labelOf(Context c,String pkg){
		try{
			PackageManager pm= c.getPackageManager();
			ApplicationInfo ai= pm.getApplicationInfo(pkg,0);
			CharSequence l= pm.getApplicationLabel(ai);
			return l == null ? pkg : l.toString();
		} catch (Exception e){
			return pkg; // not visible to us (package-visibility) or not installed
		}
	}

	/** Is this app excluded, and why? Never throws. */
	public static Decision check(Context c,String pkg){
		if(pkg == null || pkg.isEmpty()) return Decision.ALLOW;
		try{
			String label= labelOf(c,pkg);
			if(pkg.equals(c.getPackageName())){
				return new Decision(true,"Eccles Agent never reads or operates its own screen.",label);
			}
			if(EcclesRules.isHardBlocked(pkg,c.getPackageName())){
				return new Decision(true,"This is a system security screen, which the agent never operates.",label);
			}
			if(allowed(c).contains(pkg)) return Decision.ALLOW;
			if(excluded(c).contains(pkg)){
				return new Decision(true,label + " is on your excluded apps list.",label);
			}
			if(EcclesStorage.smartDetect(c) && EcclesRules.looksFinancial(pkg,label.equals(pkg) ? "" : label)){
				return new Decision(true,label + " looks like a banking, wallet or payment app (auto-detected).",label);
			}
			return Decision.ALLOW;
		} catch (Exception e){
			// Fail closed: if the check itself breaks, do not touch the app.
			return new Decision(true,"Could not verify that this app is safe to automate.",pkg);
		}
	}
}
