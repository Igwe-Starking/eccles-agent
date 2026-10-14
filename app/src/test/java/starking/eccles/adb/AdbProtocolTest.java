package starking.eccles.adb;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AdbProtocolTest {

	@Test
	public void messageRoundTrip() throws Exception{
		AdbMessage m= new AdbMessage(AdbMessage.A_WRTE,7,9,"hello".getBytes("UTF-8"));
		byte[] wire= m.encode();
		assertEquals(24 + 5,wire.length);
		AdbMessage r= AdbMessage.read(new ByteArrayInputStream(wire));
		assertEquals(AdbMessage.A_WRTE,r.command);
		assertEquals(7,r.arg0);
		assertEquals(9,r.arg1);
		assertArrayEquals("hello".getBytes("UTF-8"),r.data);
	}

	@Test
	public void headerMatchesTheDocumentedLayout() throws Exception{
		byte[] wire= new AdbMessage(AdbMessage.A_OPEN,1,0,new byte[]{0x41,0x42}).encode();
		ByteBuffer b= ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(0x4e45504f,b.getInt());       // "OPEN"
		assertEquals(1,b.getInt());
		assertEquals(0,b.getInt());
		assertEquals(2,b.getInt());                // data length
		assertEquals(0x41 + 0x42,b.getInt());      // checksum
		assertEquals(0x4e45504f ^ 0xffffffff,b.getInt()); // magic
	}

	@Test(expected= IOException.class)
	public void rejectsCorruptMagic() throws Exception{
		byte[] wire= new AdbMessage(AdbMessage.A_OKAY,1,2,null).encode();
		wire[20]^= 0x55;
		AdbMessage.read(new ByteArrayInputStream(wire));
	}

	@Test
	public void publicKeyEncodingIsTheAndroidStruct() throws Exception{
		KeyPair kp= AdbKeys.generate();
		RSAPublicKey pub= (RSAPublicKey) kp.getPublic();
		byte[] enc= AdbKeys.encodePublicKey(pub);
		assertEquals(524,enc.length);
		ByteBuffer b= ByteBuffer.wrap(enc).order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(64,b.getInt());
		int n0inv= b.getInt();
		byte[] nle= new byte[256]; b.get(nle);
		byte[] rrle= new byte[256]; b.get(rrle);
		assertEquals(65537,b.getInt());

		BigInteger n= new BigInteger(1,reverse(nle));
		assertEquals(pub.getModulus(),n);
		// n0inv * n[0] == -1 (mod 2^32)
		long lhs= ((long) n0inv & 0xffffffffL) * (n.longValue() & 0xffffffffL) & 0xffffffffL;
		assertEquals(0xffffffffL,lhs);
		BigInteger rr= new BigInteger(1,reverse(rrle));
		assertEquals(BigInteger.ONE.shiftLeft(4096).mod(n),rr);

		String payload= new String(AdbKeys.publicKeyPayload(pub,"eccles@agent"),"UTF-8");
		assertTrue(payload.endsWith(" eccles@agent\0"));
	}

	@Test
	public void tokenSignatureVerifiesAsPkcs1Sha1DigestInfo() throws Exception{
		KeyPair kp= AdbKeys.generate();
		byte[] token= new byte[20];
		new java.util.Random(42).nextBytes(token);
		byte[] sig= AdbKeys.sign(kp.getPrivate(),token);
		assertEquals(256,sig.length);
		// adbd verifies with RSA_verify(NID_sha1, token, 20, sig): i.e. SHA1-DigestInfo || token
		byte[] prefix= {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
		byte[] payload= new byte[prefix.length + token.length];
		System.arraycopy(prefix,0,payload,0,prefix.length);
		System.arraycopy(token,0,payload,prefix.length,token.length);
		Signature v= Signature.getInstance("NONEwithRSA");
		v.initVerify(kp.getPublic());
		v.update(payload);
		assertTrue(v.verify(sig));
	}

	private static byte[] reverse(byte[] a){
		byte[] r= new byte[a.length];
		for(int i= 0;i < a.length;i++) r[i]= a[a.length - 1 - i];
		return r;
	}

	// ---------------------------------------------------------------------------------
	// End-to-end against a mock adbd running on a real localhost socket.
	// ---------------------------------------------------------------------------------

	/** Minimal adbd: AUTH (token -> signature -> pubkey), then OPEN exec: and stream a canned reply. */
	static class MockAdbd implements Runnable {
		final ServerSocket server;
		final KeyPair trusted;           // null = trust nobody until a pubkey is offered
		volatile boolean sawPubKey;
		volatile String lastCommand;
		final byte[] reply;
		final AtomicInteger connections= new AtomicInteger();
		volatile boolean requireTls;
		volatile PublicKeyHolder accepted= new PublicKeyHolder();

		static class PublicKeyHolder { volatile java.security.PublicKey key; }

		MockAdbd(byte[] reply) throws IOException{
			this.server= new ServerSocket(0,5,java.net.InetAddress.getLoopbackAddress());
			this.trusted= null;
			this.reply= reply;
			Thread t= new Thread(this,"mock-adbd");
			t.setDaemon(true);
			t.start();
		}

		int port(){ return server.getLocalPort(); }

		@Override public void run(){
			while(!server.isClosed()){
				try{
					final Socket s= server.accept();
					connections.incrementAndGet();
					Thread t= new Thread(() -> { try{ serve(s); } catch (Exception ignored){ } finally { try{ s.close(); } catch (IOException ig){ } } });
					t.setDaemon(true);
					t.start();
				} catch (IOException e){ return; }
			}
		}

		void serve(Socket s) throws Exception{
			InputStream in= s.getInputStream();
			OutputStream out= s.getOutputStream();
			AdbMessage cnxn= AdbMessage.read(in);
			assertEquals(AdbMessage.A_CNXN,cnxn.command);
			if(requireTls){ out.write(new AdbMessage(AdbMessage.A_STLS,1,0,null).encode()); return; }
			boolean authed= false;
			byte[] token= new byte[20];
			new java.util.Random().nextBytes(token);
			out.write(new AdbMessage(AdbMessage.A_AUTH,AdbMessage.AUTH_TOKEN,0,token).encode());
			while(!authed){
				AdbMessage m= AdbMessage.read(in);
				if(m.command != AdbMessage.A_AUTH) return;
				if(m.arg0 == AdbMessage.AUTH_SIGNATURE){
					java.security.PublicKey k= accepted.key;
					if(k != null){
						byte[] prefix= {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
						byte[] payload= new byte[35];
						System.arraycopy(prefix,0,payload,0,15);
						System.arraycopy(token,0,payload,15,20);
						Signature v= Signature.getInstance("NONEwithRSA");
						v.initVerify(k);
						v.update(payload);
						if(v.verify(m.data)){ authed= true; break; }
					}
					out.write(new AdbMessage(AdbMessage.A_AUTH,AdbMessage.AUTH_TOKEN,0,token).encode());
				} else if(m.arg0 == AdbMessage.AUTH_RSAPUBLICKEY){
					sawPubKey= true;
					// "user taps Allow": remember the key from the payload's modulus
					String b64= new String(m.data,"UTF-8").split(" ")[0];
					byte[] enc= java.util.Base64.getDecoder().decode(b64);
					ByteBuffer bb= ByteBuffer.wrap(enc).order(ByteOrder.LITTLE_ENDIAN);
					bb.getInt(); bb.getInt();
					byte[] nle= new byte[256]; bb.get(nle);
					java.math.BigInteger n= new java.math.BigInteger(1,reverse(nle));
					accepted.key= java.security.KeyFactory.getInstance("RSA").generatePublic(
						new java.security.spec.RSAPublicKeySpec(n,java.math.BigInteger.valueOf(65537)));
					authed= true;
				}
			}
			out.write(new AdbMessage(AdbMessage.A_CNXN,AdbMessage.VERSION,4096,"device::".getBytes()).encode());

			AdbMessage open= AdbMessage.read(in);
			assertEquals(AdbMessage.A_OPEN,open.command);
			lastCommand= open.dataString();
			int remote= 77;
			out.write(new AdbMessage(AdbMessage.A_OKAY,remote,open.arg0,null).encode());
			// "echo x" answers like a real shell; anything else returns the canned payload
			byte[] body= lastCommand.startsWith("exec:echo ")
				? (lastCommand.substring(10) + "\n").getBytes("UTF-8") : reply;
			// stream the reply in 1000-byte WRTE chunks, waiting for the client's OKAY each time
			for(int off= 0;off < body.length;off+= 1000){
				int len= Math.min(1000,body.length - off);
				byte[] chunk= new byte[len];
				System.arraycopy(body,off,chunk,0,len);
				out.write(new AdbMessage(AdbMessage.A_WRTE,remote,open.arg0,chunk).encode());
				AdbMessage ack= AdbMessage.read(in);
				assertEquals(AdbMessage.A_OKAY,ack.command);
			}
			out.write(new AdbMessage(AdbMessage.A_CLSE,remote,open.arg0,null).encode());
			try{ AdbMessage.read(in); } catch (IOException ignored){ }
		}
	}

	@Test
	public void firstConnectionOffersTheKeyThenLaterOnesSign() throws Exception{
		byte[] payload= new byte[5321]; // several chunks, not a multiple of 1000, includes high bytes
		new java.util.Random(7).nextBytes(payload);
		MockAdbd d= new MockAdbd(payload);
		try{
			KeyPair kp= AdbKeys.generate();
			TcpAdbTransport t= new TcpAdbTransport("127.0.0.1",d.port(),kp,"eccles-agent@test");

			byte[] got= t.exec("screencap -p",10000);
			assertTrue("first connect should have offered the public key",d.sawPubKey);
			assertEquals("exec:screencap -p",d.lastCommand);
			assertArrayEquals("binary output must arrive intact",payload,got);

			d.sawPubKey= false;
			byte[] again= t.exec("input tap 1 2",10000);
			assertFalse("key is now trusted, must authenticate by signature only",d.sawPubKey);
			assertEquals("exec:input tap 1 2",d.lastCommand);
			assertArrayEquals(payload,again);
			assertTrue(t.isAvailable());
		} finally { d.server.close(); }
	}

	@Test
	public void tlsOnlyAdbdGivesAnActionableError() throws Exception{
		MockAdbd d= new MockAdbd(new byte[0]);
		d.requireTls= true;
		try{
			new TcpAdbTransport("127.0.0.1",d.port(),AdbKeys.generate(),"x").exec("id",5000);
			throw new AssertionError("expected failure");
		} catch (IOException e){
			assertTrue(e.getMessage(),e.getMessage().contains("tcpip"));
		} finally { d.server.close(); }
	}

	@Test
	public void unreachableAdbdIsReportedNotHung() throws Exception{
		ServerSocket s= new ServerSocket(0);
		int port= s.getLocalPort();
		s.close();
		TcpAdbTransport t= new TcpAdbTransport("127.0.0.1",port,AdbKeys.generate(),"x");
		assertFalse(t.isAvailable());
	}
}
