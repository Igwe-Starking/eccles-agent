package starking.eccles.agent.pref;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import starking.eccles.agent.EcclesActivity;
import starking.eccles.agent.EcclesApplication;
import starking.eccles.agent.R;
import starking.eccles.agent.activities.ExcludedActivity;
import starking.eccles.agent.activities.HistoryActivity;
import starking.eccles.agent.service.Caller;
import starking.eccles.agent.service.CaptureService;
import starking.eccles.ai.EcclesProvider;
import starking.eccles.ai.EcclesProviders;
import starking.eccles.data.EcclesStorage;

public class EcclesPreference extends PreferenceFragmentCompat {

	private static final String TAG= "EcclesPreference";

	private final ActivityResultLauncher<Intent> captureConsent= registerForActivityResult(
		new ActivityResultContracts.StartActivityForResult(),r -> {
			if(r.getResultCode() == android.app.Activity.RESULT_OK && r.getData() != null){
				CaptureService.start(requireContext(),r.getResultCode(),r.getData());
				if(getView() != null) getView().postDelayed(this::refreshCaptureSummary,900);
			}
		});

	@Override
	public void onCreatePreferences(Bundle saved,String rootKey){
		setPreferencesFromResource(R.xml.preference,rootKey);
		wireProvider();
		wireActions();
		wireTheme();
	}

	@Override
	public void onResume(){
		super.onResume();
		refreshKeySummary();
		refreshAccessibilitySummary();
		refreshCaptureSummary();
	}

	private void refreshCaptureSummary(){
		Preference cap= findPreference("screen_capture");
		if(cap == null || !isAdded()) return;
		boolean builtIn= android.os.Build.VERSION.SDK_INT >= 30;
		cap.setTitle(builtIn ? "Backup screen capture (MediaProjection)" : "Screen capture (MediaProjection)");
		if(CaptureService.isActive()){
			cap.setSummary("On. Used " + (builtIn ? "if the accessibility screenshot fails" : "for all screenshots") + ". Tap to turn off");
		} else if(builtIn){
			cap.setSummary("Off. Not needed: screenshots use the accessibility service. Turn on only as a backup");
		} else {
			cap.setSummary("Off. Required on this Android version to take screenshots. Tap to allow");
		}
	}

	// ---- provider -----------------------------------------------------------------------------

	private void wireProvider(){
		ListPreference provider= findPreference(EcclesStorage.KEY_PROVIDER);
		if(provider != null){
			provider.setOnPreferenceChangeListener((p,v) -> {
				showProviderFields((String) v);
				return true;
			});
		}
		for(final String id : EcclesProviders.IDS){
			EditTextPreference m= findPreference(EcclesStorage.KEY_MODEL_PREFIX + id);
			if(m == null) continue;
			final String def= EcclesProviders.defaultModel(id);
			m.setSummaryProvider(p -> {
				String t= ((EditTextPreference) p).getText();
				if(t != null && !t.trim().isEmpty()) return t.trim();
				return def.isEmpty() ? "Required for this provider" : "Default: " + def;
			});
		}
		EditTextPreference base= findPreference(EcclesStorage.KEY_BASE_URL);
		if(base != null){
			base.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
			base.setSummaryProvider(p -> {
				String t= ((EditTextPreference) p).getText();
				return t == null || t.trim().isEmpty() ? "e.g. https://openrouter.ai/api/v1" : t.trim();
			});
		}
		EditTextPreference port= findPreference(EcclesStorage.KEY_ADB_PORT);
		if(port != null){
			port.setOnBindEditTextListener(e -> e.setInputType(InputType.TYPE_CLASS_NUMBER));
			port.setSummaryProvider(p -> {
				String t= ((EditTextPreference) p).getText();
				return t == null || t.trim().isEmpty() ? "5555" : t.trim();
			});
		}
		showProviderFields(EcclesStorage.provider(requireContext()));
	}

	/** Only the model (and base URL) fields for the selected provider are shown. */
	private void showProviderFields(String selected){
		for(String id : EcclesProviders.IDS){
			Preference m= findPreference(EcclesStorage.KEY_MODEL_PREFIX + id);
			if(m != null) m.setVisible(id.equals(selected));
		}
		Preference base= findPreference(EcclesStorage.KEY_BASE_URL);
		if(base != null) base.setVisible("custom".equals(selected));
		refreshKeySummary(selected);
	}

	// ---- actions ----------------------------------------------------------------------------------

	private void wireActions(){
		Preference key= findPreference("api_key");
		if(key != null) key.setOnPreferenceClickListener(p -> { showKeyDialog(); return true; });

		Preference test= findPreference("test_connection");
		if(test != null) test.setOnPreferenceClickListener(p -> { testConnection(p); return true; });

		Preference acc= findPreference("open_accessibility");
		if(acc != null) acc.setOnPreferenceClickListener(p -> {
			((EcclesActivity) requireActivity()).openAccessibilitySettings();
			return true;
		});

		Preference cap= findPreference("screen_capture");
		if(cap != null) cap.setOnPreferenceClickListener(p -> {
			if(CaptureService.isActive()){
				CaptureService.stop(requireContext());
				if(getView() != null) getView().postDelayed(this::refreshCaptureSummary,400);
			} else {
				captureConsent.launch(CaptureService.consentIntent(requireContext()));
			}
			return true;
		});

		Preference ex= findPreference("excluded_apps");
		if(ex != null) ex.setOnPreferenceClickListener(p -> {
			startActivity(new Intent(requireContext(),ExcludedActivity.class));
			return true;
		});

		Preference hist= findPreference("history");
		if(hist != null) hist.setOnPreferenceClickListener(p -> {
			startActivity(new Intent(requireContext(),HistoryActivity.class));
			return true;
		});

		Preference ver= findPreference("version");
		if(ver != null){
			try{
				PackageInfo pi= requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(),0);
				ver.setSummary(pi.versionName);
			} catch (Exception ignored){
			}
		}
	}

	private void wireTheme(){
		ListPreference t= findPreference(EcclesStorage.KEY_THEME);
		if(t != null) t.setOnPreferenceChangeListener((p,v) -> {
			EcclesApplication.applyTheme((String) v);
			return true;
		});
	}

	private void refreshKeySummary(){
		refreshKeySummary(EcclesStorage.provider(requireContext()));
	}

	private void refreshKeySummary(String id){
		Preference key= findPreference("api_key");
		if(key == null) return;
		Context c= requireContext();
		String masked= EcclesStorage.maskedKey(c,id);
		key.setTitle(EcclesProviders.label(id) + " API key");
		if(masked.isEmpty()){
			key.setSummary(EcclesProviders.keyOptional(id) ? "Optional for local servers" : "Not set. Tap to add");
		} else {
			key.setSummary("Saved " + masked + " · stored encrypted on this device");
		}
	}

	private void refreshAccessibilitySummary(){
		Preference acc= findPreference("open_accessibility");
		if(acc == null) return;
		acc.setSummary(EcclesActivity.isAccessibilityEnabled(requireContext())
			? "On. Eccles can see and operate the screen"
			: "Off. Tap to turn on in Android settings");
	}

	private void showKeyDialog(){
		final Context c= requireContext();
		final String id= EcclesStorage.provider(c);
		final EditText e= new EditText(c);
		e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
		e.setSingleLine(true);
		e.setHint("Paste your API key");
		FrameLayout box= new FrameLayout(c);
		int pad= (int) (20 * c.getResources().getDisplayMetrics().density);
		box.setPadding(pad,pad / 2,pad,0);
		box.addView(e);
		MaterialAlertDialogBuilder b= new MaterialAlertDialogBuilder(c)
			.setTitle(EcclesProviders.label(id) + " API key")
			.setMessage("Stored encrypted with a key that never leaves this device's secure hardware.")
			.setView(box)
			.setPositiveButton("Save",(d,w) -> {
				EcclesStorage.saveApiKey(c,id,e.getText().toString());
				refreshKeySummary(id);
			})
			.setNegativeButton("Cancel",null);
		if(EcclesStorage.hasApiKey(c,id)){
			b.setNeutralButton("Remove",(d,w) -> {
				EcclesStorage.saveApiKey(c,id,"");
				refreshKeySummary(id);
			});
		}
		b.show();
	}

	private void testConnection(final Preference p){
		final Context c= requireContext().getApplicationContext();
		p.setSummary("Testing…");
		new Thread(() -> {
			String msg;
			try{
				EcclesProvider prov= Caller.providerFor(c);
				String r= prov.generate("Reply with the JSON object {\"ok\":true} and nothing else.","ping",null);
				msg= r.contains("ok") ? "Connected to " + prov.label() + " (" + prov.model() + ")" : "Connected, but the reply was unexpected";
			} catch (EcclesProvider.AiException ex){
				msg= ex.getMessage();
			} catch (Exception ex){
				Log.e(TAG,"test failed",ex);
				msg= "Failed: " + ex.getMessage();
			}
			final String shown= msg;
			if(isAdded() && getActivity() != null){
				requireActivity().runOnUiThread(() -> {
					p.setSummary(shown);
					if(getView() != null) Snackbar.make(getView(),shown,Snackbar.LENGTH_LONG).show();
				});
			}
		},"eccles-test").start();
	}
}
