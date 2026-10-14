package starking.eccles.access;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import starking.eccles.agent.EcclesApplication;

/**
 * A small always-on-top status pill with a Stop button, drawn as an accessibility overlay
 * (no "draw over other apps" permission needed). It also hosts the Allow/Deny buttons for
 * confirmations. Built in code because the accessibility service has no app theme to inflate
 * Material layouts against.
 */
public class EcclesOverlay {

	private static final String TAG= "EcclesOverlay";

	private final AccessibilityService svc;
	private final WindowManager wm;
	private final Handler ui= new Handler(Looper.getMainLooper());
	private final float density;

	private LinearLayout root;
	private TextView label;
	private LinearLayout confirmBox;
	private TextView question;
	private WindowManager.LayoutParams lp;
	private boolean attached;

	public EcclesOverlay(AccessibilityService svc){
		this.svc= svc;
		this.wm= (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
		this.density= svc.getResources().getDisplayMetrics().density;
	}

	private int dp(int v){
		return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,v,svc.getResources().getDisplayMetrics()));
	}

	// ---- public API (thread-safe) --------------------------------------------------------

	public void show(final String text){
		ui.post(() -> {
			ensureAttached();
			if(label != null) label.setText(text);
		});
	}

	public void setStatus(final String text){
		ui.post(() -> {
			if(attached && label != null) label.setText(text);
		});
	}

	public void hide(){
		ui.post(() -> {
			if(attached){
				try{ wm.removeView(root); } catch (Exception ignored){ }
				attached= false;
			}
		});
	}

	public void showConfirm(final String q,final Runnable onAllow,final Runnable onDeny){
		ui.post(() -> {
			ensureAttached();
			if(!attached) return;
			question.setText(q);
			confirmBox.setTag(new Runnable[]{onAllow,onDeny});
			confirmBox.setVisibility(View.VISIBLE);
		});
	}

	public void hideConfirm(){
		ui.post(() -> {
			if(confirmBox != null) confirmBox.setVisibility(View.GONE);
		});
	}

	/** Makes the pill invisible so it is not in a screenshot. Blocks briefly until applied. */
	public void setHiddenForCapture(final boolean hidden){
		runSync(() -> {
			if(attached && root != null) root.setAlpha(hidden ? 0f : 1f);
		});
		if(hidden) android.os.SystemClock.sleep(60); // let the compositor drop the frame
	}

	/** Lets injected gestures pass through the pill so it can never swallow a tap meant for an app. */
	public void setPassThrough(final boolean pass){
		runSync(() -> {
			if(!attached || lp == null) return;
			if(pass) lp.flags|= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
			else lp.flags&= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
			try{ wm.updateViewLayout(root,lp); } catch (Exception ignored){ }
		});
	}

	public void destroy(){
		hide();
	}

	// ---- internals -------------------------------------------------------------------------

	private void runSync(final Runnable r){
		if(Looper.myLooper() == Looper.getMainLooper()){
			r.run();
			return;
		}
		final CountDownLatch l= new CountDownLatch(1);
		ui.post(() -> { try{ r.run(); } finally { l.countDown(); } });
		try{ l.await(600,TimeUnit.MILLISECONDS); } catch (InterruptedException e){ Thread.currentThread().interrupt(); }
	}

	private void ensureAttached(){
		if(attached) return;
		try{
			build();
			lp= new WindowManager.LayoutParams(
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.WRAP_CONTENT,
				WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
				WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
				PixelFormat.TRANSLUCENT);
			lp.gravity= Gravity.TOP | Gravity.CENTER_HORIZONTAL;
			lp.y= dp(40);
			wm.addView(root,lp);
			attached= true;
		} catch (Exception e){
			Log.e(TAG,"Could not show overlay",e);
		}
	}

	private void build(){
		Context c= svc;
		root= new LinearLayout(c);
		root.setOrientation(LinearLayout.VERTICAL);
		root.setPadding(dp(14),dp(8),dp(8),dp(8));
		GradientDrawable bg= new GradientDrawable();
		bg.setColor(Color.parseColor("#F2101828"));
		bg.setCornerRadius(dp(22));
		bg.setStroke(dp(1),Color.parseColor("#33FFFFFF"));
		root.setBackground(bg);
		root.setElevation(dp(6));

		LinearLayout row= new LinearLayout(c);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);

		View dot= new View(c);
		GradientDrawable d= new GradientDrawable();
		d.setShape(GradientDrawable.OVAL);
		d.setColor(Color.parseColor("#4ADE80"));
		dot.setBackground(d);
		LinearLayout.LayoutParams dlp= new LinearLayout.LayoutParams(dp(8),dp(8));
		dlp.rightMargin= dp(10);
		row.addView(dot,dlp);

		label= new TextView(c);
		label.setTextColor(Color.WHITE);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
		label.setMaxLines(1);
		label.setEllipsize(TextUtils.TruncateAt.END);
		label.setMaxWidth(dp(210));
		row.addView(label);

		Button stop= pill(c,"Stop","#E5484D");
		stop.setOnClickListener(v -> ((EcclesApplication) svc.getApplication()).cancelTask());
		LinearLayout.LayoutParams slp= new LinearLayout.LayoutParams(ViewGroupLP.WRAP,dp(32));
		slp.leftMargin= dp(10);
		row.addView(stop,slp);
		root.addView(row);

		confirmBox= new LinearLayout(c);
		confirmBox.setOrientation(LinearLayout.VERTICAL);
		confirmBox.setVisibility(View.GONE);
		confirmBox.setPadding(0,dp(8),dp(6),0);
		question= new TextView(c);
		question.setTextColor(Color.parseColor("#E5E7EB"));
		question.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
		question.setMaxWidth(dp(280));
		confirmBox.addView(question);
		LinearLayout btns= new LinearLayout(c);
		btns.setOrientation(LinearLayout.HORIZONTAL);
		btns.setGravity(Gravity.END);
		Button deny= pill(c,"Deny","#475467");
		Button allow= pill(c,"Allow","#16A34A");
		deny.setOnClickListener(v -> answer(1));
		allow.setOnClickListener(v -> answer(0));
		LinearLayout.LayoutParams blp= new LinearLayout.LayoutParams(ViewGroupLP.WRAP,dp(34));
		blp.topMargin= dp(8);
		btns.addView(deny,blp);
		LinearLayout.LayoutParams blp2= new LinearLayout.LayoutParams(ViewGroupLP.WRAP,dp(34));
		blp2.topMargin= dp(8);
		blp2.leftMargin= dp(8);
		btns.addView(allow,blp2);
		confirmBox.addView(btns);
		root.addView(confirmBox);
	}

	private void answer(int idx){
		Object t= confirmBox.getTag();
		confirmBox.setVisibility(View.GONE);
		if(t instanceof Runnable[]){
			Runnable r= ((Runnable[]) t)[idx];
			if(r != null) r.run();
		}
	}

	private Button pill(Context c,String text,String color){
		Button b= new Button(c);
		b.setText(text);
		b.setAllCaps(false);
		b.setTextColor(Color.WHITE);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP,13);
		b.setMinHeight(0);
		b.setMinimumHeight(0);
		b.setMinWidth(0);
		b.setMinimumWidth(0);
		b.setPadding(dp(14),0,dp(14),0);
		GradientDrawable g= new GradientDrawable();
		g.setColor(Color.parseColor(color));
		g.setCornerRadius(dp(16));
		b.setBackground(g);
		return b;
	}

	private static final class ViewGroupLP {
		static final int WRAP= android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
	}
}
