package starking.eccles.agent;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.snackbar.Snackbar;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.agent.activities.ExcludedActivity;
import starking.eccles.agent.activities.HistoryActivity;
import starking.eccles.agent.activities.PreferenceActivity;
import starking.eccles.agent.service.CaptureService;
import starking.eccles.agent.service.Listener;
import starking.eccles.ai.EcclesProviders;
import starking.eccles.data.EcclesStorage;

/** The console: status, setup guidance, live task timeline, and the command bar. */
public class MajorActivity extends EcclesActivity implements EcclesApplication.TimelineListener {

	private static final int REQ_NOTIF= 11;
	private static final int REQ_VOICE= 12;
	private static final String PREF_ASKED_NOTIF= "asked_notifications";
	private static final String PREF_CAPTURE_SKIPPED= "capture_prompt_skipped";
	/** "Eccles, open Facebook..." / "Hey Eccles open ..." : the wake word is stripped, not sent to the AI. */
	private static final Pattern WAKE= Pattern.compile("^\\s*(?:hey|ok|okay)?\\s*eccles\\s*[,:.\\-]?\\s*",Pattern.CASE_INSENSITIVE);

	private RecyclerView list;
	private EcclesAdapter adapter;
	private View emptyView,setupCard;
	private TextView setupTitle,setupText;
	private MaterialButton setupAction,setupSkip,btnSend;
	private View btnMic;
	private EditText input;
	private Chip chipAccess,chipAi,chipExec,chipCapture;
	private MaterialToolbar toolbar;
	private Runnable setupStep;

	/** Result of the system "start recording your screen?" dialog. */
	private final ActivityResultLauncher<Intent> captureConsent= registerForActivityResult(
		new ActivityResultContracts.StartActivityForResult(),r -> {
			if(r.getResultCode() == RESULT_OK && r.getData() != null){
				CaptureService.start(this,r.getResultCode(),r.getData());
				if(list != null) list.postDelayed(this::refreshStatus,900);
			} else {
				toast("Screen capture was not allowed");
			}
		});

	@Override
	protected void onCreate(Bundle b){
		super.onCreate(b);
		setContentView(R.layout.activity_major);

		toolbar= findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		list= findViewById(R.id.list);
		emptyView= findViewById(R.id.empty_view);
		setupCard= findViewById(R.id.setup_card);
		setupTitle= findViewById(R.id.setup_title);
		setupText= findViewById(R.id.setup_text);
		setupAction= findViewById(R.id.setup_action);
		setupSkip= findViewById(R.id.setup_skip);
		input= findViewById(R.id.input);
		btnSend= findViewById(R.id.btn_send);
		btnMic= findViewById(R.id.btn_mic);
		chipAccess= findViewById(R.id.chip_access);
		chipAi= findViewById(R.id.chip_ai);
		chipExec= findViewById(R.id.chip_exec);
		chipCapture= findViewById(R.id.chip_capture);

		adapter= new EcclesAdapter();
		LinearLayoutManager lm= new LinearLayoutManager(this);
		lm.setStackFromEnd(true);
		list.setLayoutManager(lm);
		list.setAdapter(adapter);
		list.setItemAnimator(null); // steps update in place; animations would flicker

		buildSuggestions();

		btnSend.setOnClickListener(v -> {
			if(app.isRunning()) app.cancelTask();
			else submit();
		});
		btnMic.setOnClickListener(v -> startVoice());
		input.setOnEditorActionListener((v,action,e) -> {
			if(action == EditorInfo.IME_ACTION_SEND){
				if(!app.isRunning()) submit();
				return true;
			}
			return false;
		});
		input.addTextChangedListener(new TextWatcher(){
			@Override public void beforeTextChanged(CharSequence s,int a,int b,int c){ }
			@Override public void onTextChanged(CharSequence s,int a,int b,int c){ }
			@Override public void afterTextChanged(Editable s){ updateSendButton(); }
		});
		chipAccess.setOnClickListener(v -> { if(!isAccessibilityEnabled(this)) openAccessibilitySettings(); });
		chipAi.setOnClickListener(v -> startActivity(new Intent(this,PreferenceActivity.class)));
		chipExec.setOnClickListener(v -> startActivity(new Intent(this,PreferenceActivity.class)));
		setupAction.setOnClickListener(v -> { if(setupStep != null) setupStep.run(); });
		setupSkip.setOnClickListener(v -> {
			EcclesStorage.prefs(this).edit().putBoolean(PREF_CAPTURE_SKIPPED,true).apply();
			refreshStatus();
		});
		chipCapture.setOnClickListener(v -> toggleCapture());
	}

	@Override
	protected void onStart(){
		super.onStart();
		app.timelineListener= this;
		adapter.setAll(new ArrayList<>(app.timeline));
		updateEmpty();
		scrollToEnd();
		updateSendButton();
	}

	@Override
	protected void onResume(){
		super.onResume();
		refreshStatus();
		maybeAskNotifications();
	}

	@Override
	protected void onStop(){
		app.timelineListener= null;
		super.onStop();
	}

	// ---- menu ----------------------------------------------------------------------------------

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		getMenuInflater().inflate(R.menu.major_menu,m);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item){
		int id= item.getItemId();
		if(id == R.id.action_history) startActivity(new Intent(this,HistoryActivity.class));
		else if(id == R.id.action_excluded) startActivity(new Intent(this,ExcludedActivity.class));
		else if(id == R.id.action_settings) startActivity(new Intent(this,PreferenceActivity.class));
		else return super.onOptionsItemSelected(item);
		return true;
	}

	// ---- timeline listener -------------------------------------------------------------------

	@Override
	public void onEntry(EcclesPojo p,boolean added){
		int pos= adapter.upsert(p,added);
		updateEmpty();
		if(pos >= adapter.getItemCount() - 2) scrollToEnd();
	}

	@Override
	public void onCleared(){
		adapter.clear();
		updateEmpty();
	}

	@Override
	public void onRunState(boolean running){
		updateSendButton();
		input.setEnabled(!running);
		btnMic.setEnabled(!running);
		if(!running) refreshStatus();
	}

	// ---- actions -------------------------------------------------------------------------------

	private void submit(){
		String goal= WAKE.matcher(input.getText().toString()).replaceFirst("").trim();
		if(goal.isEmpty()) return;

		String id= EcclesStorage.provider(this);
		String mode= EcclesStorage.execMode(this);
		if(!EcclesProviders.keyOptional(id) && !EcclesStorage.hasApiKey(this,id)){
			snack(list,"Add your " + EcclesProviders.label(id) + " API key first").setAction("Settings",v -> startActivity(new Intent(this,PreferenceActivity.class)));
			return;
		}
		if(!mode.equals(EcclesStorage.EXEC_ADB) && !isAccessibilityEnabled(this)){
			snack(list,"Turn on the accessibility service so Eccles can control the phone").setAction("Turn on",v -> openAccessibilitySettings());
			return;
		}
		if(app.isRunning()){
			toast("A task is already running");
			return;
		}
		hideKeyboard();
		input.setText("");
		Intent i= new Intent(this,Listener.class).setAction(Listener.ACTION_RUN).putExtra(Listener.EXTRA_GOAL,goal);
		try{
			ContextCompat.startForegroundService(this,i);
		} catch (RuntimeException e){
			snack(list,"Could not start the agent: " + e.getMessage());
		}
	}

	private void toggleCapture(){
		if(CaptureService.isActive()){
			CaptureService.stop(this);
			list.postDelayed(this::refreshStatus,400);
		} else {
			captureConsent.launch(CaptureService.consentIntent(this));
		}
	}

	private void startVoice(){
		Intent i= new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
			.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
			.putExtra(RecognizerIntent.EXTRA_PROMPT,"Say what Eccles should do");
		try{
			startActivityForResult(i,REQ_VOICE);
		} catch (ActivityNotFoundException e){
			toast("No speech recognizer is installed on this device");
		}
	}

	@Override
	protected void onActivityResult(int req,int res,Intent data){
		super.onActivityResult(req,res,data);
		if(req != REQ_VOICE || res != RESULT_OK || data == null) return;
		ArrayList<String> r= data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
		if(r == null || r.isEmpty()) return;
		String said= r.get(0);
		input.setText(said);
		input.setSelection(input.getText().length());
		// Starting with the wake word ("Eccles, open Facebook...") is an explicit command: run it.
		// Anything else is left in the box for the user to check and send, so a misheard sentence never acts on its own.
		Matcher m= WAKE.matcher(said.toLowerCase(Locale.ROOT));
		if(m.find() && m.end() < said.length()) submit();
	}

	// ---- UI state ------------------------------------------------------------------------------

	private void buildSuggestions(){
		ChipGroup g= findViewById(R.id.suggestions);
		String[] ideas= {
			"Open Facebook and post hello everyone",
			"Turn on battery saver",
			"Open Chrome and search for today's weather",
			"Open YouTube and search for lo-fi music"
		};
		for(final String s : ideas){
			Chip c= new Chip(this,null,com.google.android.material.R.attr.chipStyle);
			c.setText(s);
			c.setOnClickListener(v -> {
				input.setText(s);
				input.setSelection(s.length());
				input.requestFocus();
			});
			g.addView(c);
		}
	}

	private void updateEmpty(){
		boolean empty= adapter.getItemCount() == 0;
		emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
		list.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
	}

	private void scrollToEnd(){
		int n= adapter.getItemCount();
		if(n > 0) list.scrollToPosition(n - 1);
	}

	private void updateSendButton(){
		boolean running= app.isRunning();
		boolean hasText= input.getText().length() > 0;
		btnSend.setIconResource(running ? R.drawable.ic_stop : R.drawable.ic_send);
		btnSend.setContentDescription(getString(running ? R.string.stop : R.string.send));
		btnSend.setEnabled(running || hasText);
		input.setHint(running ? R.string.hint_running : R.string.hint_idle);
		input.setEnabled(!running);
	}

	private void refreshStatus(){
		String id= EcclesStorage.provider(this);
		String mode= EcclesStorage.execMode(this);
		boolean accOn= isAccessibilityEnabled(this);
		boolean keyOk= EcclesProviders.keyOptional(id) || EcclesStorage.hasApiKey(this,id);
		boolean accNeeded= !mode.equals(EcclesStorage.EXEC_ADB);

		setChip(chipAccess,accNeeded ? (accOn ? "Accessibility on" : "Accessibility off") : "Accessibility not used",
			accNeeded ? (accOn ? R.color.eccles_success : R.color.eccles_error) : R.color.eccles_neutral);
		String model= EcclesStorage.model(this,id);
		setChip(chipAi,EcclesProviders.label(id).replace("Google ","").replace("Anthropic ",""),keyOk ? R.color.eccles_success : R.color.eccles_warning);
		boolean capOn= CaptureService.isActive();
		// Android 11+ screenshots come from the accessibility service itself: nothing to grant, so no chip
		// unless the optional MediaProjection backup is running (and can be switched off).
		boolean needsProjection= Build.VERSION.SDK_INT < 30;
		chipCapture.setVisibility(needsProjection || capOn ? View.VISIBLE : View.GONE);
		setChip(chipCapture,capOn ? "MediaProjection on" : "Capture off",capOn ? R.color.eccles_success : R.color.eccles_warning);
		setChip(chipExec,mode.equals(EcclesStorage.EXEC_ADB) ? "ADB" : mode.equals(EcclesStorage.EXEC_ACCESSIBILITY) ? "Accessibility only" : "Auto",R.color.eccles_neutral);
		toolbar.setSubtitle(model.isEmpty() ? EcclesProviders.defaultModel(id) : model);

		// First unmet requirement drives the setup card.
		setupStep= null;
		setupSkip.setVisibility(View.GONE);
		if(accNeeded && !accOn){
			showSetup("Turn on the accessibility service",
				"Eccles needs it to see the screen and tap on your behalf. Android will show a screen to switch it on.",
				"Open settings",this::openAccessibilitySettings);
		} else if(!keyOk){
			showSetup("Add your " + EcclesProviders.label(id) + " API key",
				"The key is stored encrypted on this device and only ever sent to " + EcclesProviders.label(id) + ".",
				"Open settings",() -> startActivity(new Intent(this,PreferenceActivity.class)));
		} else if(needsProjection && !capOn && !EcclesStorage.visionMode(this).equals(EcclesStorage.VISION_NEVER)
			&& !EcclesStorage.prefs(this).getBoolean(PREF_CAPTURE_SKIPPED,false)){
			// Android 8-10 cannot take screenshots through accessibility, so MediaProjection is the way to see
			// canvas/game/web screens. (On Android 11+ this step never appears.)
			showSetup("Allow screen capture",
				"This Android version cannot take screenshots through accessibility, so Eccles needs screen capture to see "
					+ "apps that hide their content. Choose “Entire screen” in the Android prompt. Android shows a recording "
					+ "indicator while it is on, and you can turn it off any time.",
				"Allow",this::toggleCapture);
			setupSkip.setVisibility(View.VISIBLE);
		} else {
			setupCard.setVisibility(View.GONE);
		}
	}

	private void showSetup(String title,String text,String action,Runnable step){
		setupTitle.setText(title);
		setupText.setText(text);
		setupAction.setText(action);
		setupStep= step;
		setupCard.setVisibility(View.VISIBLE);
	}

	private void setChip(Chip c,String text,int colorRes){
		c.setText(text);
		c.setChipIconTint(ColorStateList.valueOf(getColor(colorRes)));
	}

	private void maybeAskNotifications(){
		if(Build.VERSION.SDK_INT < 33) return;
		if(ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
		if(EcclesStorage.prefs(this).getBoolean(PREF_ASKED_NOTIF,false)) return;
		EcclesStorage.prefs(this).edit().putBoolean(PREF_ASKED_NOTIF,true).apply();
		ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.POST_NOTIFICATIONS},REQ_NOTIF);
	}

	private void hideKeyboard(){
		InputMethodManager imm= (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		if(imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(),0);
	}
}
