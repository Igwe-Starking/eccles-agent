package starking.eccles.engine;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import starking.eccles.guard.EcclesGuard;

/** Finds, describes and launches installed launcher apps. Ranking lives in {@link AppMatcher}. */
public class AppResolver {

	private static final String TAG= "AppResolver";

	private static List<ResolveInfo> launcherApps(Context c){
		Intent main= new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
		return c.getPackageManager().queryIntentActivities(main,0);
	}

	public static String resolve(Context c,String query){
		if(query == null || query.trim().isEmpty()) return null;
		PackageManager pm= c.getPackageManager();
		String best= null;
		long bestRank= 0;
		for(ResolveInfo ri : launcherApps(c)){
			String pkg= ri.activityInfo.packageName;
			long r= AppMatcher.rank(String.valueOf(ri.loadLabel(pm)),pkg,query);
			if(r > bestRank){
				bestRank= r;
				best= pkg;
			}
		}
		Log.d(TAG,"resolve(" + query + ") -> " + best);
		return best;
	}

	public static String label(Context c,String pkg){
		try{
			PackageManager pm= c.getPackageManager();
			return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg,0)));
		} catch (Exception e){
			return pkg;
		}
	}

	/** Every launcher app the agent is ALLOWED to operate (excluded/banking apps are left out). */
	public static List<String> installedLabels(Context c){
		PackageManager pm= c.getPackageManager();
		Set<String> seenPkg= new HashSet<>();
		Set<String> seenLabel= new HashSet<>();
		List<String> out= new ArrayList<>();
		for(ResolveInfo ri : launcherApps(c)){
			String pkg= ri.activityInfo.packageName;
			if(!seenPkg.add(pkg)) continue;
			if(EcclesGuard.check(c,pkg).blocked) continue;
			String l= String.valueOf(ri.loadLabel(pm)).trim();
			if(!l.isEmpty() && seenLabel.add(l.toLowerCase())) out.add(l);
		}
		Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** Closest names for an app that was not found (never includes excluded apps). */
	public static List<String> suggest(Context c,String query,int n){
		return AppMatcher.suggest(installedLabels(c),query,n);
	}

	/** The package of the launcher (home app), or null. */
	public static String homePackage(Context c){
		try{
			ResolveInfo ri= c.getPackageManager().resolveActivity(
				new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),PackageManager.MATCH_DEFAULT_ONLY);
			return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
		} catch (Exception e){
			return null;
		}
	}

	/** An explicit launcher component is sturdier than getLaunchIntentForPackage (null for some apps). */
	private static Intent launchIntent(Context c,String pkg){
		PackageManager pm= c.getPackageManager();
		Intent q= new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg);
		List<ResolveInfo> l= pm.queryIntentActivities(q,0);
		Intent i;
		if(l != null && !l.isEmpty()){
			ActivityInfoHolder h= new ActivityInfoHolder(l.get(0));
			i= new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(h.pkg,h.cls));
		} else {
			i= pm.getLaunchIntentForPackage(pkg);
		}
		if(i != null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
		return i;
	}

	private static final class ActivityInfoHolder {
		final String pkg,cls;
		ActivityInfoHolder(ResolveInfo ri){
			pkg= ri.activityInfo.packageName;
			cls= ri.activityInfo.name;
		}
	}

	/**
	 * Asks Android to start the app. Note startActivity() does NOT throw when Android 10+ blocks a
	 * background launch; it silently does nothing, so callers must verify the app really came forward.
	 */
	public static boolean launch(Context c,String pkg){
		try{
			Intent i= launchIntent(c,pkg);
			if(i == null) return false;
			c.startActivity(i);
			return true;
		} catch (Exception e){
			Log.e(TAG,"launch failed for " + pkg,e);
			return false;
		}
	}
}
