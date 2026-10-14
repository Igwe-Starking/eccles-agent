package starking.eccles.adb;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/** Crypto helpers for the ADB AUTH handshake. Pure Java so it can be tested on the JVM. */
public final class AdbKeys {

	private AdbKeys(){
	}

	public static final int KEY_BITS= 2048;
	private static final int KEY_BYTES= KEY_BITS / 8;

	/** DER prefix of a SHA-1 DigestInfo; adbd verifies the 20-byte token as if it were a SHA-1 digest. */
	private static final byte[] SHA1_DIGEST_INFO= {
		0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14
	};

	public static KeyPair generate() throws GeneralSecurityException{
		KeyPairGenerator g= KeyPairGenerator.getInstance("RSA");
		g.initialize(KEY_BITS);
		return g.generateKeyPair();
	}

	/** Signs adbd's 20-byte challenge token (RSASSA-PKCS1-v1_5 over SHA-1 DigestInfo || token, no re-hash). */
	public static byte[] sign(PrivateKey key,byte[] token) throws GeneralSecurityException{
		byte[] payload= new byte[SHA1_DIGEST_INFO.length + token.length];
		System.arraycopy(SHA1_DIGEST_INFO,0,payload,0,SHA1_DIGEST_INFO.length);
		System.arraycopy(token,0,payload,SHA1_DIGEST_INFO.length,token.length);
		Signature s= Signature.getInstance("NONEwithRSA");
		s.initSign(key);
		s.update(payload);
		return s.sign();
	}

	/**
	 * Encodes a public key in Android's "RSAPublicKey" struct (524 bytes for RSA-2048):
	 * u32 len_words | u32 n0inv | u8 n[256] LE | u8 rr[256] LE | u32 exponent.
	 */
	public static byte[] encodePublicKey(RSAPublicKey pub){
		BigInteger n= pub.getModulus();
		BigInteger two32= BigInteger.ONE.shiftLeft(32);
		BigInteger n0= n.mod(two32);
		int n0inv= two32.subtract(n0.modInverse(two32)).intValue(); // -n^-1 mod 2^32
		BigInteger rr= BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);
		ByteBuffer b= ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(KEY_BYTES / 4);
		b.putInt(n0inv);
		b.put(littleEndian(n,KEY_BYTES));
		b.put(littleEndian(rr,KEY_BYTES));
		b.putInt(pub.getPublicExponent().intValue());
		return b.array();
	}

	/** The payload for AUTH_RSAPUBLICKEY: base64(key) + " name" + NUL. */
	public static byte[] publicKeyPayload(RSAPublicKey pub,String name){
		String line= java.util.Base64.getEncoder().encodeToString(encodePublicKey(pub)) + " " + name + "\0";
		return line.getBytes(StandardCharsets.UTF_8);
	}

	static byte[] littleEndian(BigInteger v,int len){
		byte[] be= v.toByteArray(); // big-endian, may carry a leading sign byte
		byte[] out= new byte[len];
		for(int i= 0;i < len && i < be.length;i++){
			out[i]= be[be.length - 1 - i];
		}
		return out;
	}
}
