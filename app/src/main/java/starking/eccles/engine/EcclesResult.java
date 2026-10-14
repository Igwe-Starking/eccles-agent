package starking.eccles.engine;

public class EcclesResult {

	public final boolean ok;
	public final String detail;

	private EcclesResult(boolean ok,String detail){
		this.ok= ok;
		this.detail= detail == null ? "" : detail;
	}

	public static EcclesResult ok(){
		return new EcclesResult(true,"");
	}

	public static EcclesResult ok(String detail){
		return new EcclesResult(true,detail);
	}

	public static EcclesResult fail(String detail){
		return new EcclesResult(false,detail);
	}
}
