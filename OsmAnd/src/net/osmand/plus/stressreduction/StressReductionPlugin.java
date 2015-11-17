package net.osmand.plus.stressreduction;

import net.osmand.PlatformUtil;
import net.osmand.StateChangedListener;
import net.osmand.ValueHolder;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandPlugin;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.routing.RouteProvider;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.stressreduction.connectivity.ConnectionHandler;
import net.osmand.plus.stressreduction.connectivity.ConnectionReceiver;
import net.osmand.plus.stressreduction.database.DataHandler;
import net.osmand.plus.stressreduction.fragments.FragmentHandler;
import net.osmand.plus.stressreduction.sensors.SensorHandler;
import net.osmand.plus.stressreduction.tools.Calculation;
import net.osmand.plus.stressreduction.tools.UUIDCreator;
import net.osmand.plus.stressreduction.voice.SRPocketSphinx;

import org.apache.commons.logging.Log;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.app.AlertDialog;

/**
 * This class is the stress reduction plugin for OsmAnd.
 *
 * @author Tobias
 */
public class StressReductionPlugin extends OsmandPlugin
		implements RoutingHelper.IRouteInformationListener,
		StateChangedListener<RouteProvider.RouteService> {

	private static final Log log = PlatformUtil.getLog(StressReductionPlugin.class);

	private static String UNIQUE_ID;

	private final OsmandApplication osmandApplication;
	private final DataHandler dataHandler;
	private final FragmentHandler fragmentHandler;
	private final SensorHandler sensorHandler;
	private Activity currentActivity;
	private boolean firstRun;
	private boolean wasClosed;
	private static boolean routing;

	public StressReductionPlugin(OsmandApplication osmandApplication) {
		this.osmandApplication = osmandApplication;

		UNIQUE_ID = UUIDCreator.id(osmandApplication);
		// for debugging
		if (BuildConfig.DEBUG) {
			UNIQUE_ID = "Test_ID_16/11/15";
		}

		dataHandler = new DataHandler(osmandApplication);
		fragmentHandler = new FragmentHandler(osmandApplication);
		sensorHandler = new SensorHandler(osmandApplication, dataHandler, fragmentHandler);

		firstRun = true;
		wasClosed = false;
		routing = false;
		osmandApplication.getRoutingHelper().addListener(this);

		// lifecycle callbacks for detecting if app gets closed
		osmandApplication.registerActivityLifecycleCallbacks(new CurrentActivityCallbacks());

		// enable the plugin by default if router service is osmand
		if (osmandApplication.getSettings().ROUTER_SERVICE.get() !=
				RouteProvider.RouteService.OSMAND) {
			StressReductionPlugin.enablePlugin(null, osmandApplication, this, false);
		} else {
			StressReductionPlugin.enablePlugin(null, osmandApplication, this, true);
		}
		osmandApplication.getSettings().ROUTER_SERVICE.addListener(this);

		// write uuid to database
		dataHandler.initDatabase();
		dataHandler.writeUserToDatabase();
		dataHandler.writeAppLogToDatabase(Calculation.getCurrentDateTime());
		log.debug("wrote User and AppLog to Database");

		// download stress reduction database
		ConnectionHandler.downloadSRData(osmandApplication);

		// init speech
		SRPocketSphinx.getInstance().init(osmandApplication, osmandApplication.getSettings()
				.SR_SPEECH_VALUE.get());
	}

	/**
	 * Initialize plugin runs just after creation
	 *
	 * @param osmandApplication The OsmandApplication
	 * @param activity          the currently running activity
	 */
	@Override
	public boolean init(OsmandApplication osmandApplication, Activity activity) {
		return true;
	}

	/**
	 * Getter for the anonymous unique id of the database
	 *
	 * @return unique id string
	 */
	public static String getUUID() {
		return UNIQUE_ID;
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
		routing = true;
	}

	@Override
	public void routeWasCancelled() {
		routing = false;
	}

	@Override
	public void stateChanged(RouteProvider.RouteService change) {
		log.debug("stateChanged(): routeService=" + change);
		if (!change.getName().equals(RouteProvider.RouteService.OSMAND.getName())) {
			// tell user sr plugin not available in other modes, disable plugin
			StressReductionPlugin.enablePlugin(null, osmandApplication, this, false);
			sensorHandler.stopSensors();
			showSrPluginDisabledDialog();
		} else {
			// enable plugin
			StressReductionPlugin.enablePlugin(null, osmandApplication, this, true);
			showSrPluginEnabledDialog();
		}
	}

	private void showSrPluginDisabledDialog() {
		AlertDialog.Builder dialog = new AlertDialog.Builder(currentActivity);
		dialog.setIcon(R.drawable.sr_plugin_icon_bg_disabled);
		dialog.setTitle(R.string.sr_plugin_disabled);
		dialog.setMessage(R.string.sr_plugin_disabled_info);
		dialog.setPositiveButton(R.string.shared_string_ok, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		dialog.show();
	}

	private void showSrPluginEnabledDialog() {
		AlertDialog.Builder dialog = new AlertDialog.Builder(currentActivity);
		dialog.setIcon(R.drawable.sr_plugin_icon_bg_enabled);
		dialog.setTitle(R.string.sr_plugin_enabled);
		dialog.setMessage(R.string.sr_plugin_enabled_info);
		dialog.setPositiveButton(R.string.shared_string_ok, new DialogInterface.OnClickListener() {
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		dialog.show();
	}

	/**
	 * This class is called each time a activity inside the osmand application changes its state.
	 * It is used to monitor if the application is in background and stopped to upload data
	 *
	 * http://stackoverflow.com/questions/3667022/checking-if-an-android-application-is-running-in
	 * -the-background
	 */
	private class CurrentActivityCallbacks implements OsmandApplication
			.ActivityLifecycleCallbacks {

		int foreground = 0;
		int visible = 0;

		@Override
		public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

		}

		@Override
		public void onActivityStarted(Activity activity) {
			visible += 1;
			log.debug("onActivityStarted(): Activity = " +
					activity.getComponentName().getClassName() +
					", application visible = " + (visible > 0) + ", visible = " + visible);
		}

		@Override
		public void onActivityResumed(Activity activity) {
			foreground += 1;
			log.debug("onActivityResumed(): Activity = " +
					activity.getComponentName().getClassName() +
					", application foreground = " + (foreground > 0) + ", foreground = " +
					foreground);
			currentActivity = activity;
		}

		@Override
		public void onActivityPaused(Activity activity) {
			foreground -= 1;
			log.debug("onActivityPaused(): Activity = " +
					activity.getComponentName().getClassName() +
					", application foreground = " + (foreground > 0) + ", foreground = " +
					foreground + ", ready for upload = " + isReadyForUpload());
		}

		@Override
		public void onActivityStopped(Activity activity) {
			visible -= 1;
			log.debug("onActivityStopped(): Activity = " +
					activity.getComponentName().getClassName() +
					", application visible = " + (visible > 0) + ", visible = " + visible +
					", ready for upload = " + isReadyForUpload());
		}

		@Override
		public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

		}

		@Override
		public void onActivityDestroyed(Activity activity) {
			log.debug("onActivityDestroyed(): Activity = " +
					activity.getComponentName().getClassName());
		}

		private boolean isReadyForUpload() {
			if (foreground < 1 && visible < 1 && !routing) {
				sensorHandler.stopSensors();
				wasClosed = true;
				initUpload();
			}
			return foreground < 1 && visible < 1 && !routing;
		}
	}

	@Override
	public String getId() {
		return Constants.PLUGIN_ID;
	}

	@Override
	public String getDescription() {
		return osmandApplication.getString(R.string.sr_plugin_description);
	}

	@Override
	public String getName() {
		return osmandApplication.getString(R.string.sr_plugin_name);
	}

	@Override
	public int getLogoResourceId() {
		return R.drawable.sr_plugin_icon;
	}

	@Override
	public int getAssetResourceName() {
		return 0;
	}

	@Override
	public String getHelpFileName() {
		return "feature_articles/sr-plugin.html";
	}

	@Override
	public void mapActivityResume(MapActivity activity) {
		super.mapActivityResume(activity);

		// set mapActivity
		fragmentHandler.setMapActivity(activity);

		// if first time, show dialogs
		if (firstRun) {
			firstRun = false;
			fragmentHandler.showStartDialogs();
		}

		if (wasClosed) {
			wasClosed = false;
			// write uuid to database
			dataHandler.writeUserToDatabase();
			dataHandler.writeAppLogToDatabase(Calculation.getCurrentDateTime());
			log.debug("wrote User and AppLog to Database");

			// disable receiver
			if (ConnectionReceiver.isReceiverEnabled(osmandApplication)) {
				ConnectionReceiver.disableReceiver(osmandApplication);
			}
		}

		// start sensors
		sensorHandler.startSensors();
	}

	@Override
	public void mapActivityPause(MapActivity activity) {
		super.mapActivityPause(activity);

		// set mapActivity not available
		fragmentHandler.setMapActivity(null);
	}

	@Override
	public Class<? extends Activity> getSettingsActivity() {
		return StressReductionSettings.class;
	}

	private void initUpload() {
		ConnectionHandler.uploadData(osmandApplication);
	}

}