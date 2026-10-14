package starking.eccles.adb;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** One ADB protocol packet: a 24-byte little-endian header followed by an optional payload. */
public class AdbMessage {

	public static final int A_SYNC= 0x434e5953;
	public static final int A_CNXN= 0x4e584e43;
	public static final int A_OPEN= 0x4e45504f;
	public static final int A_OKAY= 0x59414b4f;
	public static final int A_CLSE= 0x45534c43;
	public static final int A_WRTE= 0x45545257;
	public static final int A_AUTH= 0x48545541;
	public static final int A_STLS= 0x534c5453;

	public static final int AUTH_TOKEN= 1;
	public static final int AUTH_SIGNATURE= 2;
	public static final int AUTH_RSAPUBLICKEY= 3;

	/** Protocol version that tells the peer it may skip payload checksums. */
	public static final int VERSION= 0x01000001;
	public static final int MAX_PAYLOAD= 256 * 1024;
	/** Sanity cap on what we will accept from the other side. */
	private static final int MAX_ACCEPTED= 1024 * 1024 + 4096;

	public final int command,arg0,arg1;
	public final byte[] data;

	public AdbMessage(int command,int arg0,int arg1,byte[] data){
		this.command= command;
		this.arg0= arg0;
		this.arg1= arg1;
		this.data= data == null ? new byte[0] : data;
	}

	public byte[] encode(){
		ByteBuffer b= ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum(data)).putInt(command ^ 0xffffffff);
		b.put(data);
		return b.array();
	}

	public static AdbMessage read(InputStream in) throws IOException{
		DataInputStream d= new DataInputStream(in);
		byte[] h= new byte[24];
		d.readFully(h);
		ByteBuffer b= ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
		int cmd= b.getInt(), a0= b.getInt(), a1= b.getInt(), len= b.getInt();
		b.getInt(); // checksum: ignored, version >= 0x01000001 peers do not verify it
		int magic= b.getInt();
		if(magic != (cmd ^ 0xffffffff)) throw new IOException("Bad ADB packet (magic mismatch)");
		if(len < 0 || len > MAX_ACCEPTED) throw new IOException("Bad ADB packet (length " + len + ")");
		byte[] data= new byte[len];
		if(len > 0) d.readFully(data);
		return new AdbMessage(cmd,a0,a1,data);
	}

	static int checksum(byte[] data){
		int s= 0;
		for(byte x : data) s+= x & 0xff;
		return s;
	}

	public String dataString(){
		int n= data.length;
		while(n > 0 && data[n - 1] == 0) n--;
		return new String(data,0,n,java.nio.charset.StandardCharsets.UTF_8);
	}

	public static String name(int cmd){
		switch(cmd){
			case A_CNXN: return "CNXN";
			case A_OPEN: return "OPEN";
			case A_OKAY: return "OKAY";
			case A_CLSE: return "CLSE";
			case A_WRTE: return "WRTE";
			case A_AUTH: return "AUTH";
			case A_STLS: return "STLS";
			case A_SYNC: return "SYNC";
			default: return String.format("0x%08x",cmd);
		}
	}
}
