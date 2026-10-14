package starking.eccles.guard;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The pure (android-free) rules behind the excluded-apps guard and the confirmation gate.
 * Kept separate from {@link EcclesGuard} so they can be unit-tested and reasoned about alone.
 */
public final class EcclesRules {

	private EcclesRules(){
	}

	/**
	 * Packages pre-checked in the Excluded Apps screen on first run. This is a seed, not the
	 * mechanism: the heuristics below catch the long tail, and the user can add or remove
	 * anything from the picker.
	 */
	public static final String[] SEED= {
		"com.chase.sig.android",
		"com.infonow.bofa",
		"com.wf.wellsfargomobile",
		"com.citi.citimobile",
		"com.usaa.mobile.android.usaa",
		"com.konylabs.capitalone",
		"com.paypal.android.p2pmobile",
		"com.venmo",
		"com.squareup.cash",
		"com.revolut.revolut",
		"com.google.android.apps.walletnfcrel",
		"com.google.android.apps.nbu.paisa.user",
		"com.phonepe.app",
		"net.one97.paytm",
		"com.barclays.android.barclaysmobilebanking"
	};

	/** System security surfaces the agent must never operate, regardless of any user setting. */
	private static final String[] HARD_BLOCKED= {
		"com.google.android.permissioncontroller",
		"com.android.permissioncontroller",
		"com.android.packageinstaller",
		"com.google.android.packageinstaller"
	};

	/** Substrings that are distinctive enough to match anywhere in a package name or app label. */
	private static final String[] SUBSTRINGS= {
		"bank","banking","wallet","fintech","paypal","crypto","bitcoin","finance","financial",
		"brokerage","remit","forex","transferwise","revolut","monzo","coinbase","binance",
		"robinhood","schwab","vanguard","etrade","zelle","venmo","mpesa","gcash","paytm","phonepe",
		"moniepoint","palmpay","kuda","paystack","flutterwave","interswitch","quickteller",
		"stanbic","ecobank","zenithbank","gtbank","gtworld","fidelity"
	};

	/** Whole words (split on anything non-alphanumeric) that indicate money handling. */
	private static final String[] TOKENS= {
		"pay","payment","payments","money","credit","loan","loans","invest","investing","trading",
		"cash","upi","uba","gtco","fcmb","wema","n26","chime","sofi"
	};

	public static boolean isHardBlocked(String pkg,String ownPackage){
		if(pkg == null) return false;
		if(pkg.equals(ownPackage)) return true;
		for(String h : HARD_BLOCKED) if(h.equals(pkg)) return true;
		return false;
	}

	/** Heuristic: does this package/label look like a banking, wallet, payment or trading app? */
	public static boolean looksFinancial(String pkg,String label){
		String hay= ((pkg == null ? "" : pkg) + " " + (label == null ? "" : label)).toLowerCase(Locale.ROOT);
		for(String s : SUBSTRINGS) if(hay.contains(s)) return true;
		for(String tok : hay.split("[^a-z0-9]+")){
			if(tok.isEmpty()) continue;
			for(String t : TOKENS) if(tok.equals(t)) return true;
			// opay, palmpay, gpay, applepay, googlepay ... and paycheck, payroll
			if(tok.length() > 3 && (tok.endsWith("pay") || tok.startsWith("pay"))) return true;
		}
		return false;
	}

	private static final Pattern IRREVERSIBLE= Pattern.compile(
		"(?i)\\b(send|post|publish|submit|share|tweet|reply|comment|pay|buy|purchase|order|checkout|"
		+ "place order|delete|remove|erase|confirm|transfer|subscribe|sign out|log out|logout|"
		+ "uninstall|reset|clear all|block|report|unfollow|unfriend|donate)\\b");

	/** True when a button label suggests the click cannot be undone or leaves the device. */
	public static boolean looksIrreversible(String label){
		return label != null && !label.isEmpty() && IRREVERSIBLE.matcher(label).find();
	}
}
