package starking.eccles.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;

/**
 * A small ADB client that talks to the device's own adbd over TCP (normally 127.0.0.1:5555).
 * <p>
 * Setup is a one-time "adb tcpip 5555" from a computer (it resets on reboot); the first
 * connection then shows the standard "Allow USB debugging?" prompt on the phone. Each command
 * uses a fresh connection, which costs a few tens of milliseconds but means there is no
 * long-lived socket state to go stale, time out, or desynchronise.
 * <p>
 * Wireless-debugging (TLS + pairing code) is not supported by this class.
 */
public class TcpAdbTransport implements AdbTransport {

	private static final int LOCAL_ID= 1;
	private static final int MAX_OUTPUT= 24 * 1024 * 1024;
	private static final int CONNECT_TIMEOUT_MS= 3000;
	/** How long we wait for the user to tap Allow on the first connection. */
	private static final int AUTH_PROMPT_TIMEOUT_MS= 30000;

	private final String host;
	private final int port;
	private final KeyPair keys;
	private final String keyName;

	public TcpAdbTransport(String host,int port,KeyPair keys,String keyName){
		this.host= host;
		this.port= port;
		this.keys= keys;
		this.keyName= keyName;
	}

	@Override
	public String name(){
		return "ADB (" + host + ":" + port + ")";
	}

	@Override
	public synchronized byte[] exec(String command,int timeoutMs) throws IOException{
		try(Socket s= open()){
			InputStream in= s.getInputStream();
			OutputStream out= s.getOutputStream();
			handshake(s,in,out);

			s.setSoTimeout(timeoutMs);
			send(out,new AdbMessage(AdbMessage.A_OPEN,LOCAL_ID,0,cstr("exec:" + command)));

			int remoteId= -1;
			ByteArrayOutputStream result= new ByteArrayOutputStream();
			long deadline= System.currentTimeMillis() + timeoutMs;
			while(true){
				if(System.currentTimeMillis() > deadline) throw new IOException("ADB command timed out");
				AdbMessage m= AdbMessage.read(in);
				switch(m.command){
					case AdbMessage.A_OKAY:
						remoteId= m.arg0;
						break;
					case AdbMessage.A_WRTE:
						result.write(m.data);
						if(result.size() > MAX_OUTPUT) throw new IOException("ADB output too large");
						send(out,new AdbMessage(AdbMessage.A_OKAY,LOCAL_ID,m.arg0,null));
						break;
					case AdbMessage.A_CLSE:
						if(remoteId == -1 && result.size() == 0){
							throw new IOException("adbd refused the command (service not available)");
						}
						send(out,new AdbMessage(AdbMessage.A_CLSE,LOCAL_ID,m.arg0,null));
						return result.toByteArray();
					default:
						throw new IOException("Unexpected ADB packet " + AdbMessage.name(m.command));
				}
			}
		}
	}

	@Override
	public boolean isAvailable(){
		try{
			byte[] r= exec("echo eccles",5000);
			return new String(r,StandardCharsets.UTF_8).contains("eccles");
		} catch (Exception e){
			return false;
		}
	}

	@Override
	public void close(){
	}

	private Socket open() throws IOException{
		Socket s= new Socket();
		s.setTcpNoDelay(true);
		s.connect(new InetSocketAddress(host,port),CONNECT_TIMEOUT_MS);
		return s;
	}

	private void handshake(Socket s,InputStream in,OutputStream out) throws IOException{
		send(out,new AdbMessage(AdbMessage.A_CNXN,AdbMessage.VERSION,AdbMessage.MAX_PAYLOAD,cstr("host::features=cmd")));
		s.setSoTimeout(CONNECT_TIMEOUT_MS * 2);
		boolean signed= false;
		for(int i= 0;i < 6;i++){
			AdbMessage m= AdbMessage.read(in);
			switch(m.command){
				case AdbMessage.A_CNXN:
					return;
				case AdbMessage.A_STLS:
					throw new IOException("This adbd requires TLS (Wireless debugging). Run \"adb tcpip " + port + "\" from a computer first.");
				case AdbMessage.A_AUTH:
					if(m.arg0 != AdbMessage.AUTH_TOKEN) throw new IOException("Unexpected AUTH type " + m.arg0);
					if(!signed){
						signed= true;
						try{
							send(out,new AdbMessage(AdbMessage.A_AUTH,AdbMessage.AUTH_SIGNATURE,0,AdbKeys.sign(keys.getPrivate(),m.data)));
						} catch (GeneralSecurityException e){
							throw new IOException("Could not sign ADB challenge: " + e.getMessage());
						}
					} else {
						// Our key is not trusted yet: offer it and wait for the user to approve on screen.
						send(out,new AdbMessage(AdbMessage.A_AUTH,AdbMessage.AUTH_RSAPUBLICKEY,0,
							AdbKeys.publicKeyPayload((RSAPublicKey) keys.getPublic(),keyName)));
						s.setSoTimeout(AUTH_PROMPT_TIMEOUT_MS);
					}
					break;
				default:
					throw new IOException("Unexpected ADB packet " + AdbMessage.name(m.command) + " during connect");
			}
		}
		throw new IOException("ADB authorisation was not accepted. Tap Allow on the phone and try again.");
	}

	private static void send(OutputStream out,AdbMessage m) throws IOException{
		out.write(m.encode());
		out.flush();
	}

	private static byte[] cstr(String s){
		return (s + "\0").getBytes(StandardCharsets.UTF_8);
	}
}
