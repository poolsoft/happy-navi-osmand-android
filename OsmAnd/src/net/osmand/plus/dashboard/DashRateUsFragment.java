package net.osmand.plus.dashboard;

import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import java.util.Calendar;

public class DashRateUsFragment extends DashBaseFragment {
    public static final String TAG = "DASH_RATE_US_FRAGMENT";

    // TODO move to resources
    public static final String EMAIL = "hcilab@gmail.com"; //"support@osmand.net";

    // Imported in shouldShow method
    private static OsmandSettings settings;
    private FragmentState state = FragmentState.INITIAL_STATE;
	private RateUsDismissListener mRateUsDismissListener;
    @Override
    public void onOpenDash() {

    }

    @Override
    public View initView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = getActivity().getLayoutInflater().inflate(R.layout.dash_rate_us_fragment, container, false);
        TextView header = (TextView) view.findViewById(R.id.header);
        TextView subheader = (TextView) view.findViewById(R.id.subheader);
        Button positiveButton = (Button) view.findViewById(R.id.positive_button);
        Button negativeButton = (Button) view.findViewById(R.id.negative_button);
        positiveButton.setOnClickListener(
                new PositiveButtonListener(header, subheader, positiveButton, negativeButton));
        negativeButton.setOnClickListener(
                new NegativeButtonListener(header, subheader, positiveButton, negativeButton));
		mRateUsDismissListener = new RateUsDismissListener(dashboard, settings);
        return view;
    }

	public static boolean shouldShow(OsmandSettings settings) {
		if(!settings.LAST_DISPLAY_TIME.isSet()) {
			settings.LAST_DISPLAY_TIME.set(System.currentTimeMillis());
		}
		DashRateUsFragment.settings = settings;
		long lastDisplayTimeInMillis = settings.LAST_DISPLAY_TIME.get();
		int numberOfApplicationRuns = settings.NUMBER_OF_APPLICATION_STARTS.get();
		RateUsState state = settings.RATE_US_STATE.get();

		Calendar modifiedTime = Calendar.getInstance();
		Calendar lastDisplayTime = Calendar.getInstance();
		lastDisplayTime.setTimeInMillis(lastDisplayTimeInMillis);

		int bannerFreeRuns = 0;

		switch (state) {
			case LIKED:
				return false;
			case INITIAL_STATE:
				break;
			case IGNORED:
				modifiedTime.add(Calendar.WEEK_OF_YEAR, -1);
				bannerFreeRuns = 5;
				break;
			case DISLIKED_WITH_MESSAGE:
				modifiedTime.add(Calendar.MONTH, -3);
				bannerFreeRuns = 3;
				break;
			case DISLIKED_WITHOUT_MESSAGE:
				modifiedTime.add(Calendar.MONTH, -2);
				break;
			default:
				throw new IllegalStateException("Unexpected state:" + state);
		}

		if (state != RateUsState.INITIAL_STATE) {
			if (modifiedTime.after(lastDisplayTime) && numberOfApplicationRuns >= bannerFreeRuns) {
				settings.RATE_US_STATE.set(RateUsState.INITIAL_STATE);
				modifiedTime = Calendar.getInstance();
			} else {
				return false;
			}
		}
		// Initial state now
		modifiedTime.add(Calendar.HOUR, -72);
		bannerFreeRuns = 6;
		return modifiedTime.after(lastDisplayTime) && numberOfApplicationRuns >= bannerFreeRuns;
	}

	@Override
	public DismissListener getDismissCallback() {
		return mRateUsDismissListener;
	}

	public class PositiveButtonListener implements View.OnClickListener {
        private TextView header;
        private TextView subheader;
        private Button positiveButton;
        private Button negativeButton;

        public PositiveButtonListener(TextView header, TextView subheader, Button positiveButton,
                                      Button negativeButton) {
            this.header = header;
            this.subheader = subheader;
            this.positiveButton = positiveButton;
            this.negativeButton = negativeButton;
        }

        @Override
        public void onClick(View v) {
            switch (state) {
                case INITIAL_STATE:
                    state = FragmentState.USER_LIKES_APP;

                    header.setText(getResources().getString(R.string.rate_this_app));
                    subheader.setText(getResources().getString(R.string.rate_this_app_long));
                    positiveButton.setText(getResources().getString(R.string.shared_string_ok));
                    negativeButton.setText(getResources().getString(R.string.shared_string_no_thanks));
                    return;
                case USER_LIKES_APP:
                    settings.RATE_US_STATE.set(RateUsState.LIKED);
                    // Assuming GooglePlay
                    Uri uri = Uri.parse("market://details?id=org.hcilab.projects.happy.navi");
//                            + getActivity().getPackageName());
                    Intent goToMarket = new Intent(Intent.ACTION_VIEW, uri);
                    try {
                        startActivity(goToMarket);
                    } catch (ActivityNotFoundException e) {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("http://play.google.com/store/apps/details?id=org" +
                                        ".hcilab.projects.happy.navi")));
//                                        + getActivity().getPackageName())));
                    }
                    dashboard.refreshDashboardFragments();
                    return;
                case USER_HATES_APP:
                    settings.RATE_US_STATE.set(RateUsState.DISLIKED_WITH_MESSAGE);
                    settings.NUMBER_OF_APPLICATION_STARTS.set(0);
                    settings.LAST_DISPLAY_TIME.set(System.currentTimeMillis());
                    dashboard.refreshDashboardFragments();
                    Intent sendEmail = new Intent(Intent.ACTION_SENDTO);
                    sendEmail.setType("text/plain");
                    sendEmail.setData(Uri.parse("mailto:" + EMAIL));
                    sendEmail.putExtra(Intent.EXTRA_EMAIL, EMAIL);
                    startActivity(sendEmail);
                    break;
            }
        }
    }


    public class NegativeButtonListener implements View.OnClickListener {
        private TextView header;
        private TextView subheader;
        private Button positiveButton;
        private Button negativeButton;

        public NegativeButtonListener(TextView header, TextView subheader, Button positiveButton,
                                      Button negativeButton) {
            this.header = header;
            this.subheader = subheader;
            this.positiveButton = positiveButton;
            this.negativeButton = negativeButton;
        }

        @Override
        public void onClick(View v) {
            switch (state) {
                case INITIAL_STATE:
                    state = FragmentState.USER_HATES_APP;

                    header.setText(getResources().getString(R.string.user_hates_app_get_feedback));
                    subheader.setText(getResources().getString(R.string.user_hates_app_get_feedback_long));
                    positiveButton.setText(getResources().getString(R.string.shared_string_ok));
                    negativeButton.setText(getResources().getString(R.string.shared_string_no_thanks));
                    return;
                case USER_LIKES_APP:
                    settings.RATE_US_STATE.set(RateUsState.IGNORED);
                    break;
                case USER_HATES_APP:
                    settings.RATE_US_STATE.set(RateUsState.DISLIKED_WITHOUT_MESSAGE);
                    break;
            }
            settings.NUMBER_OF_APPLICATION_STARTS.set(0);
            settings.LAST_DISPLAY_TIME.set(System.currentTimeMillis());
            dashboard.refreshDashboardFragments();
        }
    }

    private enum FragmentState {
        INITIAL_STATE,
        USER_LIKES_APP,
        USER_HATES_APP
    }

    public enum RateUsState {
        INITIAL_STATE,
        IGNORED,
        LIKED,
        DISLIKED_WITH_MESSAGE,
        DISLIKED_WITHOUT_MESSAGE
    }

    public static class RateUsShouldShow extends DashboardOnMap.SettingsShouldShow {
        @Override
        public boolean shouldShow(OsmandSettings settings, MapActivity activity, String tag) {
            return DashRateUsFragment.shouldShow(settings)
					&& super.shouldShow(settings, activity, tag);
        }
    }

	private static class RateUsDismissListener implements DismissListener {
		private DashboardOnMap dashboardOnMap;
		private OsmandSettings settings;
		public RateUsDismissListener(DashboardOnMap dashboardOnMap, OsmandSettings settings) {
			this.dashboardOnMap = dashboardOnMap;
			this.settings = settings;
		}

		@Override
		public void onDismiss() {
			settings.RATE_US_STATE.set(RateUsState.IGNORED);
			settings.NUMBER_OF_APPLICATION_STARTS.set(0);
			settings.LAST_DISPLAY_TIME.set(System.currentTimeMillis());
			dashboardOnMap.refreshDashboardFragments();
		}
	}
}
