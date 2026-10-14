package starking.eccles.guard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EcclesRulesTest {

	@Test
	public void seedIsPopulated(){
		assertTrue(EcclesRules.SEED.length > 10);
	}

	@Test
	public void catchesBanksWalletsAndPaymentAppsByHeuristic(){
		assertTrue(EcclesRules.looksFinancial("com.kudabank.app","Kuda"));
		assertTrue(EcclesRules.looksFinancial("team.opay.pay","OPay"));
		assertTrue(EcclesRules.looksFinancial("com.transsnet.palmpay","PalmPay"));
		assertTrue(EcclesRules.looksFinancial("com.moniepoint.personal","Moniepoint"));
		assertTrue(EcclesRules.looksFinancial("com.samsung.android.spay","Samsung Pay"));
		assertTrue(EcclesRules.looksFinancial("com.example.x","My Mobile Banking"));
		assertTrue(EcclesRules.looksFinancial("com.coinbase.android","Coinbase"));
		assertTrue(EcclesRules.looksFinancial("com.unknown.bank.app",null));
	}

	@Test
	public void doesNotFlagOrdinaryApps(){
		assertFalse(EcclesRules.looksFinancial("com.facebook.katana","Facebook"));
		assertFalse(EcclesRules.looksFinancial("com.whatsapp","WhatsApp"));
		assertFalse(EcclesRules.looksFinancial("com.google.android.youtube","YouTube"));
		assertFalse(EcclesRules.looksFinancial("com.spotify.music","Spotify"));
		assertFalse(EcclesRules.looksFinancial("com.android.chrome","Chrome"));
		assertFalse(EcclesRules.looksFinancial("com.google.android.apps.photos","Photos"));
		assertFalse(EcclesRules.looksFinancial("com.example.display","Display Settings"));
		assertFalse(EcclesRules.looksFinancial("com.example.blank","Blank Canvas"));
		assertFalse(EcclesRules.looksFinancial("com.google.android.apps.maps","Maps"));
	}

	@Test
	public void hardBlockCoversOwnPackageAndPermissionDialogs(){
		assertTrue(EcclesRules.isHardBlocked("starking.eccles.agent","starking.eccles.agent"));
		assertTrue(EcclesRules.isHardBlocked("com.google.android.permissioncontroller","x"));
		assertTrue(EcclesRules.isHardBlocked("com.android.packageinstaller","x"));
		assertFalse(EcclesRules.isHardBlocked("com.facebook.katana","x"));
		assertFalse(EcclesRules.isHardBlocked(null,"x"));
	}

	@Test
	public void irreversibleLabels(){
		assertTrue(EcclesRules.looksIrreversible("Post"));
		assertTrue(EcclesRules.looksIrreversible("Send"));
		assertTrue(EcclesRules.looksIrreversible("Delete account"));
		assertTrue(EcclesRules.looksIrreversible("Place order"));
		assertFalse(EcclesRules.looksIrreversible("What's on your mind?"));
		assertFalse(EcclesRules.looksIrreversible("Search"));
		assertFalse(EcclesRules.looksIrreversible("Postal code"));
		assertFalse(EcclesRules.looksIrreversible(null));
	}
}
