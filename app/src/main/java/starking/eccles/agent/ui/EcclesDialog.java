package starking.eccles.agent.ui;

import android.app.Dialog;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * A confirm dialog that survives rotation: the answer is delivered through the FragmentManager
 * result API instead of a stored callback, which would be lost when the activity is recreated.
 * Register with {@code getSupportFragmentManager().setFragmentResultListener(KEY, this, ...)}
 * in the activity's onCreate and read {@link #RESULT_OK}.
 */
public class EcclesDialog extends DialogFragment {

	public static final String RESULT_OK= "ok";
	private static final String A_KEY= "key",A_TITLE= "title",A_MSG= "msg",A_YES= "yes";

	public static void confirm(FragmentActivity a,String requestKey,String title,String message,String yes){
		EcclesDialog d= new EcclesDialog();
		Bundle b= new Bundle();
		b.putString(A_KEY,requestKey);
		b.putString(A_TITLE,title);
		b.putString(A_MSG,message);
		b.putString(A_YES,yes);
		d.setArguments(b);
		d.show(a.getSupportFragmentManager(),"eccles_dialog_" + requestKey);
	}

	@NonNull
	@Override
	public Dialog onCreateDialog(Bundle saved){
		final Bundle a= getArguments() == null ? new Bundle() : getArguments();
		return new MaterialAlertDialogBuilder(requireContext())
			.setTitle(a.getString(A_TITLE))
			.setMessage(a.getString(A_MSG))
			.setPositiveButton(a.getString(A_YES,"OK"),(d,w) -> deliver(a,true))
			.setNegativeButton("Cancel",(d,w) -> deliver(a,false))
			.create();
	}

	private void deliver(Bundle a,boolean ok){
		Bundle r= new Bundle();
		r.putBoolean(RESULT_OK,ok);
		getParentFragmentManager().setFragmentResult(a.getString(A_KEY,""),r);
	}
}
