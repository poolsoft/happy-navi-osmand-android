package net.osmand.plus.views;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.ValueHolder;
import net.osmand.access.AccessibleToast;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.data.QuadRect;
import net.osmand.data.QuadTree;
import net.osmand.data.RotatedTileBox;
import net.osmand.osm.PoiType;
import net.osmand.plus.ContextMenuAdapter;
import net.osmand.plus.ContextMenuAdapter.OnContextMenuClick;
import net.osmand.plus.OsmAndFormatter;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.OsmandSettings;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.helpers.FileNameTranslationHelper;
import net.osmand.plus.poi.PoiFiltersHelper;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.render.RenderingIcons;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.RoutingHelper.IRouteInformationListener;
import net.osmand.plus.views.MapTextLayer.MapTextProvider;
import net.osmand.util.Algorithms;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.PointF;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.widget.PopupMenu;
import android.support.v7.widget.Toolbar;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class POIMapLayer extends OsmandMapLayer implements ContextMenuLayer.IContextMenuProvider,
		MapTextProvider<Amenity>, IRouteInformationListener {
	private static final int startZoom = 9;

	public static final org.apache.commons.logging.Log log = PlatformUtil.getLog(POIMapLayer.class);

	private Paint paintIcon;

	private Paint paintIconBackground;
	private Bitmap poiBackground;
	private Bitmap poiBackgroundSmall;

	private OsmandMapTileView view;
	private final static int MAXIMUM_SHOW_AMENITIES = 5;

	private ResourceManager resourceManager;
	private RoutingHelper routingHelper;
	private PoiUIFilter filter;
	private MapTextLayer mapTextLayer;
	
	/// cache for displayed POI
	// Work with cache (for map copied from AmenityIndexRepositoryOdb)
	private MapLayerData<List<Amenity>> data;

	private OsmandSettings settings;

	private OsmandApplication app;


	public POIMapLayer(final MapActivity activity) {
		routingHelper = activity.getRoutingHelper();
		routingHelper.addListener(this);
		settings = activity.getMyApplication().getSettings();
		app = activity.getMyApplication();
		data = new OsmandMapLayer.MapLayerData<List<Amenity>>() {
			{
				ZOOM_THRESHOLD = 0;
			}
			
			@Override
			public boolean isInterrupted() {
				return super.isInterrupted();
			}
			
			@Override
			public void layerOnPostExecute() {
				activity.getMapView().refreshMap();
			}
			
			@Override
			protected List<Amenity> calculateResult(RotatedTileBox tileBox) {
				QuadRect latLonBounds = tileBox.getLatLonBounds();
				if (filter == null) {
					return new ArrayList<Amenity>();
				}
				int z = (int) Math.floor(tileBox.getZoom() + Math.log(view.getSettings().MAP_DENSITY.get()) / Math.log(2));

				List<Amenity> res = filter.searchAmenities(latLonBounds.top, latLonBounds.left,
						latLonBounds.bottom, latLonBounds.right, z , new ResultMatcher<Amenity>() {

					@Override
					public boolean publish(Amenity object) {
						return true;
					}

					@Override
					public boolean isCancelled() {
						return isInterrupted();
					}
				});

				Collections.sort(res, new Comparator<Amenity>() {
					@Override
					public int compare(Amenity lhs, Amenity rhs) {
						return lhs.getId() < rhs.getId() ? -1 : (lhs.getId().longValue() == rhs.getId().longValue() ? 0 : 1);
					}
				});

				return res;
			}
		};
	}


	public void getAmenityFromPoint(RotatedTileBox tb, PointF point, List<? super Amenity> am) {
		List<Amenity> objects = data.getResults();
		if (objects != null) {
			int ex = (int) point.x;
			int ey = (int) point.y;
			final int rp = getRadiusPoi(tb);
			int compare = rp;
			int radius = rp * 3 / 2;
			try {
				for (int i = 0; i < objects.size(); i++) {
					Amenity n = objects.get(i);
					int x = (int) tb.getPixXFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					int y = (int) tb.getPixYFromLatLon(n.getLocation().getLatitude(), n.getLocation().getLongitude());
					if (Math.abs(x - ex) <= compare && Math.abs(y - ey) <= compare) {
						compare = radius;
						am.add(n);
					}
				}
			} catch (IndexOutOfBoundsException e) {
				// that's really rare case, but is much efficient than introduce synchronized block
			}
		}
	}

	private StringBuilder buildPoiInformation(StringBuilder res, Amenity n) {
		String format = OsmAndFormatter.getPoiStringWithoutType(n,
				view.getSettings().MAP_PREFERRED_LOCALE.get());
		res.append(" " + format + "\n" + OsmAndFormatter.getAmenityDescriptionContent(view.getApplication(), n, true));
		return res;
	}

	@Override
	public void initLayer(OsmandMapTileView view) {
		this.view = view;

		paintIcon = new Paint();
		//paintIcon.setStrokeWidth(1);
		//paintIcon.setStyle(Style.STROKE);
		//paintIcon.setColor(Color.BLUE);
		paintIcon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
		paintIconBackground = new Paint();
		poiBackground = BitmapFactory.decodeResource(view.getResources(), R.drawable.map_white_orange_poi_shield);
		poiBackgroundSmall = BitmapFactory.decodeResource(view.getResources(), R.drawable.map_white_orange_poi_shield_small);

		resourceManager = view.getApplication().getResourceManager();
		mapTextLayer = view.getLayerByClass(MapTextLayer.class);
	}


	public int getRadiusPoi(RotatedTileBox tb) {
		int r = 0;
		final double zoom = tb.getZoom();
		if (zoom < startZoom) {
			r = 0;
		} else if (zoom <= 15) {
			r = 10;
		} else if (zoom <= 16) {
			r = 14;
		} else if (zoom <= 17) {
			r = 16;
		} else {
			r = 18;
		}
		return (int) (r * tb.getDensity());
	}

	private QuadRect calculateRect(int x, int y, int width, int height) {
		QuadRect rf;
		double left = x - width / 2;
		double top = y - height / 2;
		double right = left + width;
		double bottom = top + height;
		rf = new QuadRect(left, top, right, bottom);
		return rf;
	}

	private QuadRect calculateRect(float x, float y, float width, float height) {
		QuadRect rf;
		double left = x - width / 2.0d;
		double top = y - height / 2.0d;
		double right = left + width;
		double bottom = top + height;
		rf = new QuadRect(left, top, right, bottom);
		return rf;
	}

	@Override
	public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
		if(!Algorithms.objectEquals(this.settings.SELECTED_POI_FILTER_FOR_MAP.get(), 
				filter == null ? null : filter.getFilterId())) {
			if(this.settings.SELECTED_POI_FILTER_FOR_MAP.get() == null) {
				this.filter = null;
			} else {
				PoiFiltersHelper pfh = app.getPoiFilters();
				this.filter = pfh.getFilterById(this.settings.SELECTED_POI_FILTER_FOR_MAP.get());
			}
			data.clearCache();
		}

		List<Amenity> objects = Collections.emptyList();
		List<Amenity> fullObjects = new ArrayList<>();
		if (filter != null) {
			if (tileBox.getZoom() >= startZoom) {
				data.queryNewData(tileBox);
				objects = data.getResults();
				if (objects != null) {
					float iconSize = poiBackground.getWidth() * 3 / 2;
					QuadRect bounds = new QuadRect(0, 0, tileBox.getPixWidth(), tileBox.getPixHeight());
					bounds.inset(-bounds.width()/4, -bounds.height()/4);
					QuadTree<QuadRect> boundIntersections = new QuadTree<>(bounds, 4, 0.6f);
					List<QuadRect> result = new ArrayList<>();

					for (Amenity o : objects) {
						float x = tileBox.getPixXFromLatLon(o.getLocation().getLatitude(), o.getLocation()
								.getLongitude());
						float y = tileBox.getPixYFromLatLon(o.getLocation().getLatitude(), o.getLocation()
								.getLongitude());
						boolean intersects =false;
						QuadRect visibleRect = calculateRect(x, y, iconSize, iconSize);
						//canvas.drawRect(visibleRect, paintIcon);

						boundIntersections.queryInBox(new QuadRect(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom), result);
						for (QuadRect r : result) {
							if (QuadRect.intersects(r, visibleRect)) {
								intersects = true;
								break;
							}
						}

						if (intersects) {
							canvas.drawBitmap(poiBackgroundSmall, x - poiBackgroundSmall.getWidth() / 2, y - poiBackgroundSmall.getHeight() / 2, paintIconBackground);
						} else {
							boundIntersections.insert(visibleRect,
									new QuadRect(visibleRect.left, visibleRect.top, visibleRect.right, visibleRect.bottom));
							fullObjects.add(o);
						}
					}
					for (Amenity o : fullObjects) {
						int x = (int) tileBox.getPixXFromLatLon(o.getLocation().getLatitude(), o.getLocation()
								.getLongitude());
						int y = (int) tileBox.getPixYFromLatLon(o.getLocation().getLatitude(), o.getLocation()
								.getLongitude());
						canvas.drawBitmap(poiBackground, x - poiBackground.getWidth() / 2, y - poiBackground.getHeight() / 2, paintIconBackground);
						String id = null;
						PoiType st = o.getType().getPoiTypeByKeyName(o.getSubType());
						if (st != null) {
							if (RenderingIcons.containsSmallIcon(st.getIconKeyName())) {
								id = st.getIconKeyName();
							} else if (RenderingIcons.containsSmallIcon(st.getOsmTag() + "_" + st.getOsmValue())) {
								id = st.getOsmTag() + "_" + st.getOsmValue();
							}
						}
						if (id != null) {
							Bitmap bmp = RenderingIcons.getIcon(view.getContext(), id, false);
							if (bmp != null) {
								canvas.drawBitmap(bmp, x - bmp.getWidth() / 2, y - bmp.getHeight() / 2, paintIcon);
							}
						}
					}
				}
			}
		}
		mapTextLayer.putData(this, objects);
	}

	@Override
	public void onDraw(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
	}

	@Override
	public void destroyLayer() {
		routingHelper.removeListener(this);
	}

	@Override
	public boolean drawInScreenPixels() {
		return true;
	}

	@Override
	public void populateObjectContextMenu(Object o, ContextMenuAdapter adapter) {
		if (o instanceof Amenity) {
			final Amenity a = (Amenity) o;
			OnContextMenuClick listener = new ContextMenuAdapter.OnContextMenuClick() {
				@Override
				public boolean onContextMenuClick(ArrayAdapter<?> adapter, int itemId, int pos, boolean isChecked) {
					if (itemId == R.string.poi_context_menu_call) {
						try {
							Intent intent = new Intent(Intent.ACTION_VIEW);
							intent.setData(Uri.parse("tel:" + a.getPhone())); //$NON-NLS-1$
							view.getContext().startActivity(intent);
						} catch (RuntimeException e) {
							log.error("Failed to invoke call", e); //$NON-NLS-1$
							AccessibleToast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					} else if (itemId == R.string.poi_context_menu_website) {
						try {
							Intent intent = new Intent(Intent.ACTION_VIEW);
							intent.setData(Uri.parse(a.getSite()));
							view.getContext().startActivity(intent);
						} catch (RuntimeException e) {
							log.error("Failed to invoke call", e); //$NON-NLS-1$
							AccessibleToast.makeText(view.getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
						}
					} else if (itemId == R.string.poi_context_menu_showdescription) {
						showDescriptionDialog(view.getContext(), app, a);
					}
					return true;
				}
			};
			if (OsmAndFormatter.getAmenityDescriptionContent(view.getApplication(), a, false).length() > 0 || 
					a.getType().isWiki()) {
				adapter.item(R.string.poi_context_menu_showdescription)
						.iconColor(R.drawable.ic_action_note_dark).listen(listener).reg();
			}
			if (a.getPhone() != null) {
				adapter.item(R.string.poi_context_menu_call)
						.iconColor(R.drawable.ic_action_call_dark).listen(listener).reg();
			}
			if (a.getSite() != null) {
				adapter.item(R.string.poi_context_menu_website)
						.iconColor(R.drawable.ic_world_globe_dark).listen(listener)
						.reg();
			}
		}
	}

	public static void showDescriptionDialog(Context ctx, OsmandApplication app, Amenity a) {
		String lang = app.getSettings().MAP_PREFERRED_LOCALE.get();
		if (a.getType().isWiki()) {
			String preferredLang = lang;
			if(Algorithms.isEmpty(preferredLang)) {
				preferredLang = app.getLanguage();
			}
			showWiki(ctx, app, a, preferredLang);
		} else {
			String d = OsmAndFormatter.getAmenityDescriptionContent(app, a, false);
			SpannableString spannable = new SpannableString(d);
			Linkify.addLinks(spannable, Linkify.ALL);
			
			Builder bs = new AlertDialog.Builder(ctx);
			bs.setTitle(OsmAndFormatter.getPoiStringWithoutType(a, lang));
			bs.setMessage(spannable);
			bs.setPositiveButton(R.string.shared_string_ok, null);
			AlertDialog dialog = bs.show();
			// Make links clickable
			TextView textView = (TextView) dialog.findViewById(android.R.id.message);
			textView.setMovementMethod(LinkMovementMethod.getInstance());
			textView.setLinksClickable(true);
		}
	}

	static int getResIdFromAttribute(final Context ctx, final int attr) {
		if (attr == 0)
			return 0;
		final TypedValue typedvalueattr = new TypedValue();
		ctx.getTheme().resolveAttribute(attr, typedvalueattr, true);
		return typedvalueattr.resourceId;
	}
	
	private static void showWiki(final Context ctx,final OsmandApplication app, final Amenity a, final String lang ) {
		final Dialog dialog = new Dialog(ctx, 
				app.getSettings().isLightContent() ?
						R.style.OsmandLightTheme:
							R.style.OsmandDarkTheme);
		final String title = a.getName(lang);
		LinearLayout ll = new LinearLayout(ctx);
		ll.setOrientation(LinearLayout.VERTICAL);
		
		final Toolbar topBar = new Toolbar(ctx);
		topBar.setClickable(true);
		Drawable back = app.getIconsCache().getIcon(R.drawable.abc_ic_ab_back_mtrl_am_alpha);
		topBar.setNavigationIcon(back);
		topBar.setTitle(title);
		topBar.setBackgroundColor(ctx.getResources().getColor(getResIdFromAttribute(ctx, R.attr.pstsTabBackground)));
		topBar.setTitleTextColor(ctx.getResources().getColor(getResIdFromAttribute(ctx, R.attr.pstsTextColor)));

		String lng = a.getContentSelected("content", lang, "en");
		if(Algorithms.isEmpty(lng)) {
			lng = "en";
		}

		final String langSelected = lng;
		String content = a.getDescription(langSelected);
		final Button bottomBar = new Button(ctx);
		bottomBar.setText(R.string.read_full_article);
		bottomBar.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				String article = "http://"+langSelected.toLowerCase()+".wikipedia.org/wiki/" + title.replace(' ', '_');
				Intent i = new Intent(Intent.ACTION_VIEW);
				i.setData(Uri.parse(article));
				ctx.startActivity(i);
			}
		});
		MenuItem mi = topBar.getMenu().add(langSelected.toUpperCase()).setOnMenuItemClickListener(new OnMenuItemClickListener()  {
			@Override
			public boolean onMenuItemClick(final MenuItem item) {
				showPopupLangMenu(ctx, topBar, app, a, dialog);
				return true;
			}
		});
		MenuItemCompat.setShowAsAction(mi, MenuItem.SHOW_AS_ACTION_ALWAYS);
		topBar.setNavigationOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(final View v) {
				dialog.dismiss();
			}
		});
		final WebView wv = new WebView(ctx);
		WebSettings settings = wv.getSettings();
		settings.setDefaultTextEncodingName("utf-8");
		wv.loadDataWithBaseURL(null, content, "text/html", "UTF-8", null);
//		wv.loadUrl(OsMoService.SIGN_IN_URL + app.getSettings().OSMO_DEVICE_KEY.get());
		ScrollView scrollView = new ScrollView(ctx);
		ll.addView(topBar);
		LayoutParams lp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0);
		lp.weight = 1;
		ll.addView(scrollView, lp);
		ll.addView(bottomBar, new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
		scrollView.addView(wv);
		dialog.setContentView(ll);
		wv.setFocusable(true);
        wv.setFocusableInTouchMode(true);
		wv.requestFocus(View.FOCUS_DOWN);
		wv.setOnTouchListener(new View.OnTouchListener() {
			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getAction()) {
				case MotionEvent.ACTION_DOWN:
				case MotionEvent.ACTION_UP:
					if (!v.hasFocus()) {
						v.requestFocus();
					}
					break;
				}
				return false;
			}
		});
		
		dialog.setCancelable(true);
		dialog.show();		
//		wv.setWebViewClient();		
	}


	protected static void showPopupLangMenu(final Context ctx, Toolbar tb, 
			final OsmandApplication app, final Amenity a, final Dialog dialog) {
		final PopupMenu optionsMenu = new PopupMenu(ctx, tb, Gravity.RIGHT);
		Set<String> names = new TreeSet<String>(); 
		names.addAll(a.getNames("content", "en"));
		names.addAll(a.getNames("description", "en"));
		
		for (final String n : names) {
			String vn = FileNameTranslationHelper.getVoiceName(ctx, n);
			MenuItem item = optionsMenu.getMenu().add(vn);
			item.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
				@Override
				public boolean onMenuItemClick(MenuItem item) {
					dialog.dismiss();
					showWiki(ctx, app, a, n);
					return true;
				}
			});
		}
		optionsMenu.show();
		
	}


	@Override
	public String getObjectDescription(Object o) {
		if (o instanceof Amenity) {
			return buildPoiInformation(new StringBuilder(), (Amenity) o).toString();
		}
		return null;
	}

	@Override
	public PointDescription getObjectName(Object o) {
		if (o instanceof Amenity) {
			return new PointDescription(PointDescription.POINT_TYPE_POI, ((Amenity) o).getName(
					view.getSettings().MAP_PREFERRED_LOCALE.get())); 
		}
		return null;
	}

	@Override
	public boolean disableSingleTap() {
		return false;
	}

	@Override
	public boolean disableLongPressOnMap() {
		return false;
	}

	@Override
	public void collectObjectsFromPoint(PointF point, RotatedTileBox tileBox, List<Object> objects) {
		getAmenityFromPoint(tileBox, point, objects);
	}

	@Override
	public LatLon getObjectLocation(Object o) {
		if (o instanceof Amenity) {
			return ((Amenity) o).getLocation();
		}
		return null;
	}
	
	@Override
	public LatLon getTextLocation(Amenity o) {
		return o.getLocation();
	}

	@Override
	public int getTextShift(Amenity o, RotatedTileBox rb) {
		return getRadiusPoi(rb);
	}

	@Override
	public String getText(Amenity o) {
		return o.getName(view.getSettings().MAP_PREFERRED_LOCALE.get());
	}

	@Override
	public void newRouteIsCalculated(boolean newRoute, ValueHolder<Boolean> showToast) {
	}

	@Override
	public void routeWasCancelled() {
	}


}
