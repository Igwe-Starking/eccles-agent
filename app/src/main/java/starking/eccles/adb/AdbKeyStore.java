package starking.eccles.adb;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.interfaces.RSAPrivateCrtKey;
import starking.eccles.crypto.KeystoreAes;

/**
 * Persists the ADB identity key. The private key is wrapped with an Android Keystore AES key
 * (the same KeystoreAes the rest of the Eccles apps use), so the file on disk is useless
 * without this device's hardware-backed key. Generating once and reusing matters: the phone
 * only asks "Allow USB debugging?" for keys it has not seen before.
 */
public class AdbKeyStore {

	private static final String FILE= "adb_identity.bin";
	private static final KeystoreAes WRAP= new KeystoreAes("eccles_agent_adb_key");
	private static KeyPair cached;

	public static synchronized KeyPair get(Context c) throws Exception{
		if(cached != null) return cached;
		File f= new File(c.getNoBackupFilesDir(),FILE);
		if(f.exists()){
			try{
				byte[] enc= new byte[(int) f.length()];
				try(FileInputStream in= new FileInputStream(f)){
					int off= 0;
					while(off < enc.length){
						int n= in.read(enc,off,enc.length - off);
						if(n < 0) break;
						off+= n;
					}
				}
				byte[] pkcs8= WRAP.decrypt(enc);
				KeyFactory kf= KeyFactory.getInstance("RSA");
				PrivateKey priv= kf.generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
				RSAPrivateCrtKey crt= (RSAPrivateCrtKey) priv;
				PublicKey pub= kf.generatePublic(new RSAPublicKeySpec(crt.getModulus(),crt.getPublicExponent()));
				cached= new KeyPair(pub,priv);
				return cached;
			} catch (Exception e){
				Log.e("AdbKeyStore","Stored ADB key unreadable, generating a new one",e);
			}
		}
		KeyPair kp= AdbKeys.generate();
		byte[] enc= WRAP.encrypt(kp.getPrivate().getEncoded());
		try(FileOutputStream out= new FileOutputStream(f)){
			out.write(enc);
			out.flush();
		}
		cached= kp;
		return kp;
	}
}
