package starking.eccles.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Ranks installed apps against what the model (or user) called them. Pure Java so the matching is
 * unit-tested; AppResolver feeds it labels from PackageManager.
 */
public final class AppMatcher {

	private AppMatcher(){
	}

	public static String norm(String s){
		return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","");
	}

	/**
	 * Base score for one app, 0 = no match. Higher is better:
	 * exact package 1000 > exact label 100 > label starts with query 80 > label contains query 60 >
	 * query contains label ("facebook app") 50 > package contains query 30. Queries shorter than 3
	 * characters match only exactly.
	 */
	public static int score(String label,String pkg,String rawQuery){
		String q= norm(rawQuery);
		if(q.isEmpty()) return 0;
		String l= norm(label);
		String p= pkg == null ? "" : pkg.toLowerCase(Locale.ROOT);
		if(rawQuery != null && pkg != null && pkg.equalsIgnoreCase(rawQuery.trim())) return 1000;
		if(l.equals(q)) return 100;
		// Fuzzy matching on one or two characters would open the wrong app ("ra" is inside "Camera"):
		// below three characters only an exact label (or package) counts.
		if(q.length() < 3) return 0;
		if(!l.isEmpty() && l.startsWith(q)) return 80;
		if(!l.isEmpty() && l.contains(q)) return 60;
		if(!l.isEmpty() && l.length() >= 3 && q.contains(l)) return 50;
		if(q.length() >= 4 && p.contains(q)) return 30;
		return 0;
	}

	/** Score with a tie-break: among equal scores the SHORTER label wins ("Facebook" over "Facebook Lite for Work"). */
	public static long rank(String label,String pkg,String query){
		int s= score(label,pkg,query);
		return s == 0 ? 0 : s * 1000L - norm(label).length();
	}

	/** Levenshtein distance, for "did you mean" suggestions on typos. */
	public static int distance(String a,String b){
		int[] prev= new int[b.length() + 1];
		int[] cur= new int[b.length() + 1];
		for(int j= 0;j <= b.length();j++) prev[j]= j;
		for(int i= 1;i <= a.length();i++){
			cur[0]= i;
			for(int j= 1;j <= b.length();j++){
				int cost= a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
				cur[j]= Math.min(Math.min(cur[j - 1] + 1,prev[j] + 1),prev[j - 1] + cost);
			}
			int[] t= prev;
			prev= cur;
			cur= t;
		}
		return prev[b.length()];
	}

	/** Up to n labels the user probably meant: substring matches first, then near-typos. */
	public static List<String> suggest(List<String> labels,String query,int n){
		final String q= norm(query);
		List<String> out= new ArrayList<>();
		if(q.isEmpty()) return out;
		List<String[]> scored= new ArrayList<>();
		for(String label : labels){
			String l= norm(label);
			if(l.isEmpty()) continue;
			int d;
			if(l.contains(q) || q.contains(l)) d= 0;
			else d= distance(l,q);
			int limit= Math.max(2,q.length() / 3);
			if(d <= limit) scored.add(new String[]{String.format("%03d",d),label});
		}
		Collections.sort(scored,(x,y) -> x[0].equals(y[0]) ? x[1].compareToIgnoreCase(y[1]) : x[0].compareTo(y[0]));
		for(String[] s : scored){
			if(out.size() >= n) break;
			out.add(s[1]);
		}
		return out;
	}
}
